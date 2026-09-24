package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.database.AppDatabase
import java.util.concurrent.TimeUnit

private const val TAG = "MailboxSchedule"

/** The one periodic check, replaced rather than stacked when the schedule is applied again. */
private const val WORK_NAME = "mailbox_periodic_collection"

/**
 * Checks the friends mailbox while the app is closed.
 *
 * Every check costs a read against a quota shared by everyone using this app, and nothing arriving
 * here is urgent, so this is deliberately slow: [CHECK_HOURS] apart, only while the mailbox is on,
 * and only with a network. It exists so that a parcel sent this morning has been collected -- and
 * notified -- before the app is next opened, not so that one arrives within the minute.
 *
 * There is no push to do this better: sending one needs a server, Cloud Functions need the Blaze
 * plan, and a service account key shipped inside the app would let anyone holding the APK write as
 * this project.
 */
object MailboxSchedule {

    const val CHECK_HOURS: Long = 2

    /** Starts the periodic check when the mailbox is on, and stops it when it is not. */
    suspend fun applyTo(context: Context) {
        val appContext = context.applicationContext
        val on = try {
            MailboxIdentityRepository(appContext, AppDatabase.getInstance(appContext)).isSignedIn()
        } catch (error: Exception) {
            Log.w(TAG, "Could not tell whether the mailbox is on", error)
            return
        }
        val work = WorkManager.getInstance(appContext)
        if (!on) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        work.enqueueUniquePeriodicWork(
            WORK_NAME,
            // KEEP: re-applying on every launch must not reset the interval, or a phone opened often
            // would never reach the end of one and never check in the background at all.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MailboxWorker>(CHECK_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

/**
 * One background check. [MailboxCollection] swallows its own failures and [com.varun.upitracker.data.mailbox.MailboxSync]
 * throttles, so a run that finds nothing, or cannot reach the server, costs nothing but the attempt.
 */
class MailboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        MailboxCollection(applicationContext).run()
        return Result.success()
    }
}
