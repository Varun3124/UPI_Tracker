package com.varun.upitracker.data.backup

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.backup.BackupCodec
import com.varun.upitracker.domain.backup.BackupDecision
import com.varun.upitracker.domain.backup.BackupDocument
import com.varun.upitracker.domain.backup.BackupFormat
import com.varun.upitracker.domain.backup.BackupMeta
import com.varun.upitracker.domain.backup.BackupParseResult
import com.varun.upitracker.domain.backup.BackupPolicy
import com.varun.upitracker.domain.backup.BackupPrefKeys
import com.varun.upitracker.domain.backup.LocalBackupState
import com.varun.upitracker.domain.backup.RemoteBackupInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UploadSummary(val revision: Long, val sizeBytes: Int, val fileId: String)

/** A local copy taken just before a restore, so a wrong choice is never final. */
data class LocalSnapshot(val file: File, val takenAtEpoch: Long, val sizeBytes: Long)

class BackupException(message: String) : IllegalStateException(message)

/**
 * Ties the pieces together: dump, encode, upload, download, restore.
 *
 * Deliberately knows nothing about Activities or consent screens. It is handed an access token and
 * hands back outcomes, which keeps the awkward Google Play services UI dance in one place
 * ([GoogleAccountRepository]) and this class testable-by-inspection.
 *
 * The safety rules that make signing in non-destructive live in
 * [com.varun.upitracker.domain.backup.BackupPolicy]; this class asks it before doing anything, and
 * takes a local snapshot before every restore.
 */
class BackupRepository(
    context: Context,
    private val db: AppDatabase,
    private val drive: DriveAppDataClient = DriveAppDataClient()
) {

    companion object {
        private const val TAG = "BackupRepository"

        private const val FILE_PREFIX = "dhanmoney-backup-"
        private const val FILE_SUFFIX = ".dhmb"

        /**
         * One live backup plus one previous. A failed or truncated upload can only ever damage the
         * newest, so there is always an intact copy behind it.
         */
        private const val KEEP_REMOTE = 2

        /** Pre-restore snapshots kept on the device. Three is enough to undo a run of mistakes. */
        private const val KEEP_SNAPSHOTS = 3

        private const val PROP_SCHEMA = "schemaVersion"
        private const val PROP_APP_VERSION = "appVersionCode"
        private const val PROP_CREATED_AT = "createdAt"
        private const val PROP_DEVICE = "deviceId"
        private const val PROP_REVISION = "revision"
        private const val PROP_TRANSACTIONS = "transactionCount"
        private const val PROP_ACCOUNTS = "accountCount"
    }

    private val appContext = context.applicationContext
    private val prefs = AppPrefs.of(appContext)
    private val dump = DatabaseDumpRepository(db)
    private val prefsBackup = PrefsBackupRepository(appContext)

    private val snapshotDir: File
        get() = File(appContext.filesDir, "backups").apply { mkdirs() }

    // --- identity and bookkeeping ---------------------------------------------------------------

    /**
     * A random id for this install, minted on first use.
     *
     * Identifies *this phone's* backups so an upload can tell its own work from another device's.
     * Deliberately not derived from anything about the user or the hardware -- it only has to be
     * unique, and it is never sent anywhere except into the backup's own header.
     */
    fun deviceId(): String = prefs.getString(BackupPrefKeys.DEVICE_ID, null) ?: run {
        val minted = UUID.randomUUID().toString()
        prefs.edit().putString(BackupPrefKeys.DEVICE_ID, minted).apply()
        minted
    }

    fun lastSuccessEpoch(): Long? =
        prefs.getLong(BackupPrefKeys.LAST_SUCCESS_EPOCH, 0L).takeIf { it > 0L }

    private fun revision(): Long = prefs.getLong(BackupPrefKeys.REVISION, 0L)

    private fun lastSyncedRevision(): Long = prefs.getLong(BackupPrefKeys.LAST_SYNCED_REVISION, 0L)

    suspend fun localState(): LocalBackupState = withContext(Dispatchers.IO) { dump.localState() }

    // --- reading what is in Drive ---------------------------------------------------------------

    /**
     * The newest backup in Drive, described from the file listing alone.
     *
     * Nothing is downloaded to answer this: the header is mirrored into Drive's `appProperties`
     * precisely so a conflict dialog can be built cheaply. A file whose properties are missing or
     * unreadable is ignored rather than guessed at -- better to treat it as absent than to compare
     * against a fabricated revision.
     */
    suspend fun remoteInfo(accessToken: String): RemoteBackupInfo? =
        currentAndOlder(accessToken).first

    private suspend fun currentAndOlder(accessToken: String): Pair<RemoteBackupInfo?, List<DriveFile>> {
        val ours = drive.list(accessToken)
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .sortedByDescending { it.appProperties[PROP_CREATED_AT]?.toLongOrNull() ?: 0L }
        val newest = ours.firstOrNull()?.let(::toInfo)
        return newest to ours.drop(KEEP_REMOTE)
    }

    private fun toInfo(file: DriveFile): RemoteBackupInfo? {
        val properties = file.appProperties
        val schema = properties[PROP_SCHEMA]?.toIntOrNull() ?: return null
        val device = properties[PROP_DEVICE] ?: return null
        return RemoteBackupInfo(
            fileId = file.id,
            schemaVersion = schema,
            createdAtEpoch = properties[PROP_CREATED_AT]?.toLongOrNull() ?: 0L,
            deviceId = device,
            revision = properties[PROP_REVISION]?.toLongOrNull() ?: 0L,
            transactionCount = properties[PROP_TRANSACTIONS]?.toIntOrNull() ?: 0,
            accountCount = properties[PROP_ACCOUNTS]?.toIntOrNull() ?: 0
        )
    }

    suspend fun planUpload(accessToken: String): BackupDecision = BackupPolicy.decideUpload(
        local = localState(),
        remote = remoteInfo(accessToken),
        lastSyncedRevision = lastSyncedRevision(),
        ourDeviceId = deviceId()
    )

    suspend fun planRestore(accessToken: String): BackupDecision = BackupPolicy.decideRestore(
        local = localState(),
        remote = remoteInfo(accessToken),
        appSchemaVersion = withContext(Dispatchers.IO) { dump.schemaVersion() }
    )

    // --- writing --------------------------------------------------------------------------------

    private suspend fun buildDocument(): BackupDocument = withContext(Dispatchers.IO) {
        BackupDocument(
            meta = BackupMeta(
                schemaVersion = dump.schemaVersion(),
                appVersionCode = appVersionCode(),
                createdAtEpoch = System.currentTimeMillis(),
                deviceId = deviceId(),
                revision = revision() + 1
            ),
            prefs = prefsBackup.dump(),
            tables = dump.dump()
        )
    }

    /**
     * Writes a new backup to Drive and prunes anything older than the two most recent.
     *
     * The caller is expected to have asked [planUpload] first and dealt with anything but
     * [BackupDecision.SafeUpload]; this does the work, it does not re-adjudicate it.
     */
    suspend fun upload(accessToken: String): UploadSummary {
        val document = buildDocument()
        val bytes = BackupCodec.encode(BackupFormat.format(document))
        val meta = document.meta

        val file = drive.upload(
            accessToken = accessToken,
            // Always a new file. Creating rather than overwriting is what leaves the previous
            // backup intact if this upload dies half-written.
            fileId = null,
            name = FILE_PREFIX + meta.createdAtEpoch + FILE_SUFFIX,
            bytes = bytes,
            appProperties = mapOf(
                PROP_SCHEMA to meta.schemaVersion.toString(),
                PROP_APP_VERSION to meta.appVersionCode.toString(),
                PROP_CREATED_AT to meta.createdAtEpoch.toString(),
                PROP_DEVICE to meta.deviceId,
                PROP_REVISION to meta.revision.toString(),
                PROP_TRANSACTIONS to document.rowCount("transactions").toString(),
                PROP_ACCOUNTS to document.rowCount("account").toString()
            )
        )

        prefs.edit()
            .putLong(BackupPrefKeys.REVISION, meta.revision)
            .putLong(BackupPrefKeys.LAST_SYNCED_REVISION, meta.revision)
            .putString(BackupPrefKeys.LAST_SYNCED_DEVICE_ID, meta.deviceId)
            .putLong(BackupPrefKeys.LAST_SUCCESS_EPOCH, meta.createdAtEpoch)
            .putString(BackupPrefKeys.DRIVE_FILE_ID, file.id)
            .apply()

        // After the new one is safely up, never before.
        prune(accessToken)
        return UploadSummary(meta.revision, bytes.size, file.id)
    }

    private suspend fun prune(accessToken: String) {
        runCatching { currentAndOlder(accessToken).second }
            .getOrElse {
                Log.w(TAG, "Could not list backups to prune", it)
                emptyList()
            }
            .forEach { stale ->
                // A failed prune is untidy, never harmful: the newest backup is already safe.
                runCatching { drive.delete(accessToken, stale.id) }
                    .onFailure { Log.w(TAG, "Could not delete old backup ${stale.name}", it) }
            }
    }

    // --- restoring ------------------------------------------------------------------------------

    /**
     * Replaces everything on this device with [remote].
     *
     * Takes a local snapshot first, unconditionally. That snapshot is the whole answer to "what if I
     * picked wrong" -- see [undoLastRestore].
     */
    suspend fun restore(accessToken: String, remote: RemoteBackupInfo): RestoreReport {
        snapshotNow()
        val bytes = drive.download(accessToken, remote.fileId)
        return applyEncoded(bytes)
    }

    private suspend fun applyEncoded(bytes: ByteArray): RestoreReport = withContext(Dispatchers.IO) {
        when (val parsed = BackupCodec.decode(bytes)) {
            is BackupParseResult.Failed -> throw BackupException(parsed.reason)
            is BackupParseResult.Ok -> {
                val document = parsed.document
                val schema = dump.schemaVersion()
                if (document.meta.schemaVersion > schema) {
                    throw BackupException(
                        "This backup was made by a newer version of the app. Update, then try again."
                    )
                }
                val report = dump.restore(document.tables)
                // Preferences last: if the table restore throws, this install keeps the settings that
                // match the data it still has.
                prefsBackup.restore(document.prefs)
                report
            }
        }
    }

    // --- the local safety net -------------------------------------------------------------------

    /** Newest first. */
    fun snapshots(): List<LocalSnapshot> = snapshotDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
        ?.mapNotNull { file ->
            val epoch = file.nameWithoutExtension.substringAfterLast('-').toLongOrNull() ?: return@mapNotNull null
            LocalSnapshot(file, epoch, file.length())
        }
        ?.sortedByDescending { it.takenAtEpoch }
        .orEmpty()

    suspend fun snapshotNow(): LocalSnapshot = withContext(Dispatchers.IO) {
        val document = buildDocument()
        val file = File(snapshotDir, "pre-restore-${document.meta.createdAtEpoch}$FILE_SUFFIX")
        file.writeBytes(BackupCodec.encode(BackupFormat.format(document)))

        snapshots().drop(KEEP_SNAPSHOTS).forEach { old ->
            runCatching { old.file.delete() }
                .onFailure { Log.w(TAG, "Could not delete old snapshot ${old.file.name}", it) }
        }
        LocalSnapshot(file, document.meta.createdAtEpoch, file.length())
    }

    /**
     * Puts back the state captured just before the most recent restore.
     *
     * The snapshot is consumed, so undoing twice walks two restores back rather than replaying the
     * same one. Returns null when there is nothing to undo.
     */
    suspend fun undoLastRestore(): RestoreReport? {
        val snapshot = snapshots().firstOrNull() ?: return null
        val report = applyEncoded(withContext(Dispatchers.IO) { snapshot.file.readBytes() })
        withContext(Dispatchers.IO) { snapshot.file.delete() }
        return report
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(0)
}
