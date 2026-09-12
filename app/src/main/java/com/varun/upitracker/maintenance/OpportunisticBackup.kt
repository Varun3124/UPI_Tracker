package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.backup.BackupRepository
import com.varun.upitracker.data.backup.DriveAuthResult
import com.varun.upitracker.data.backup.GoogleAccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.backup.BackupDecision
import java.util.concurrent.TimeUnit

private const val TAG = "OpportunisticBackup"

/**
 * Backs up at launch, quietly, when it is safe and overdue.
 *
 * Backups people have to remember are backups that do not exist, so this runs itself. Everything
 * about it is conservative, because nobody is watching:
 *  - it needs consent to already stand. There is no Activity here to show a consent screen on, so a
 *    missing grant means skip, not prompt.
 *  - it acts only on [BackupDecision.SafeUpload]. Anything the policy wants a human for -- a backup
 *    another device wrote since we last synced, or this install having nothing worth sending -- is
 *    left for the Backup screen. **An unattended run must never resolve a conflict.**
 *  - every failure is swallowed after a log. A backup that could not happen is not worth a crash or
 *    a toast on somebody's dashboard.
 *
 * Run *after* the three backfills rather than beside them: they write to the database, and a dump
 * taken while they were mid-flight would capture a half-migrated state.
 */
class OpportunisticBackup(private val context: Context) {

    private companion object {
        private val INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)
    }

    suspend fun run() {
        try {
            val accounts = GoogleAccountRepository(context)
            if (accounts.signedInAccount() == null) return

            val repository = BackupRepository(context, AppDatabase.getInstance(context))
            val last = repository.lastSuccessEpoch() ?: 0L
            if (System.currentTimeMillis() - last < INTERVAL_MILLIS) return

            val token = when (val auth = accounts.authorizeDriveSilently()) {
                is DriveAuthResult.Authorized -> auth.accessToken
                else -> {
                    Log.d(TAG, "Skipping: Drive access needs the user.")
                    return
                }
            }

            when (val decision = repository.planUpload(token)) {
                is BackupDecision.SafeUpload -> {
                    val summary = repository.upload(token)
                    Log.d(TAG, "Backed up revision ${summary.revision}, ${summary.sizeBytes} bytes.")
                }
                else -> Log.d(TAG, "Skipping: $decision needs the user to decide.")
            }
        } catch (error: Exception) {
            // Including cancellation: this runs on a detached scope that outlives the launcher
            // activity, and there is no one to tell.
            Log.w(TAG, "Automatic backup did not run", error)
        }
    }
}
