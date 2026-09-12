package com.varun.upitracker.domain.backup

/** What this phone holds right now. */
data class LocalBackupState(
    val transactionCount: Int,
    val accountCount: Int,
    val friendCount: Int,
    val transferCount: Int,
    val snapshotCount: Int
) {
    /**
     * Whether this install has anything of the user's in it.
     *
     * **Not the same as "the database is empty."** `AppDatabase`'s `onCreate` callback seeds the
     * default categories on every fresh install, so a database that has never been used still has
     * rows. Only these five tables mean a person has actually put something in.
     */
    val isUntouched: Boolean
        get() = transactionCount == 0 && accountCount == 0 && friendCount == 0 &&
            transferCount == 0 && snapshotCount == 0
}

/** What is sitting in Drive, read from the file listing without downloading it. */
data class RemoteBackupInfo(
    val fileId: String,
    val schemaVersion: Int,
    val createdAtEpoch: Long,
    val deviceId: String,
    val revision: Long,
    val transactionCount: Int,
    val accountCount: Int
)

sealed interface BackupDecision {

    /** Nothing in Drive would be lost. Go ahead without asking. */
    data object SafeUpload : BackupDecision

    /** Another device has written since we last synced. Overwriting would destroy their work. */
    data class UploadWouldOverwriteNewer(val remote: RemoteBackupInfo) : BackupDecision

    /** This phone holds nothing of the user's, so there is nothing a restore could destroy. */
    data class SafeRestore(val remote: RemoteBackupInfo) : BackupDecision

    /** Restoring would replace real data. The counts are here so the dialog can say what is at stake. */
    data class RestoreWouldReplaceLocal(
        val local: LocalBackupState,
        val remote: RemoteBackupInfo
    ) : BackupDecision

    data class Nothing(val reason: String) : BackupDecision

    data class Incompatible(val reason: String) : BackupDecision
}

/**
 * Whether an upload or a restore is safe to do without asking.
 *
 * This object exists because signing in must never cost the user data they already had. Every rule
 * below is one way that could happen, written down once and tested, rather than spread across a
 * ViewModel where it would be argued about per screen.
 *
 * Two rules are absolute:
 *  - **Nothing here ever returns a decision that restores automatically.** The most permissive
 *    restore verdict is [BackupDecision.SafeRestore], which still means "ask, but you have nothing
 *    to lose". A caller that restores without asking is the bug, and the tests say so.
 *  - **An upload never silently overwrites a backup this install did not write.** Revisions, not
 *    timestamps, decide what is newer -- device clocks disagree and are user-settable.
 */
object BackupPolicy {

    /**
     * The oldest schema this app can still open. `AppDatabase` registers no migration below 8, so a
     * backup older than that has nowhere to migrate from and Room would throw on the first read.
     */
    const val OLDEST_SUPPORTED_SCHEMA = 8

    fun decideUpload(
        local: LocalBackupState,
        remote: RemoteBackupInfo?,
        lastSyncedRevision: Long,
        ourDeviceId: String
    ): BackupDecision {
        if (local.isUntouched) {
            // Uploading nothing over a real backup is the same destruction as any other, and it is
            // the likeliest accident: a fresh install signing in before the user restores.
            return if (remote == null) {
                BackupDecision.Nothing("There is nothing to back up yet.")
            } else {
                BackupDecision.UploadWouldOverwriteNewer(remote)
            }
        }
        if (remote == null) return BackupDecision.SafeUpload

        // Our own backup, or one we have already seen and accepted: ours to replace.
        if (remote.deviceId == ourDeviceId) return BackupDecision.SafeUpload
        if (remote.revision <= lastSyncedRevision) return BackupDecision.SafeUpload

        return BackupDecision.UploadWouldOverwriteNewer(remote)
    }

    fun decideRestore(
        local: LocalBackupState,
        remote: RemoteBackupInfo?,
        appSchemaVersion: Int
    ): BackupDecision {
        if (remote == null) return BackupDecision.Nothing("There is no backup in Drive yet.")

        if (remote.schemaVersion > appSchemaVersion) {
            return BackupDecision.Incompatible(
                "This backup was made by a newer version of the app. Update, then try again."
            )
        }
        if (remote.schemaVersion < OLDEST_SUPPORTED_SCHEMA) {
            return BackupDecision.Incompatible(
                "This backup is too old for this version of the app to read."
            )
        }

        return if (local.isUntouched) {
            BackupDecision.SafeRestore(remote)
        } else {
            BackupDecision.RestoreWouldReplaceLocal(local, remote)
        }
    }
}
