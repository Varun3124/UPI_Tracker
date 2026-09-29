package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.varun.upitracker.data.chapter.SharedChapterRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.ChapterShareMode
import kotlinx.coroutines.CancellationException

private const val TAG = "ChapterPublishing"

/** One queue of sends, so a change made while one is running is sent after it rather than lost. */
private const val WORK_NAME = "chapter_publish"

/**
 * Sends shared chapters to members who are behind, soon -- whether or not the screen that changed one
 * is still open. The next mailbox check would send them anyway; this only saves the wait, which
 * matters most right after a save, when the screen that made it is already closing.
 */
object ChapterPublishing {

    fun soon(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            // A run already sending may have read the chapter before this change; the one chained
            // behind it sends what it missed. One that finds nobody behind costs a query.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ChapterPublishWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    /** [soon], when any of [chapterIds] is a shared chapter of the user's own. Reads the database. */
    suspend fun soonIfShared(context: Context, db: AppDatabase, chapterIds: Collection<Long?>) {
        val shared = chapterIds.filterNotNull().distinct().any { id ->
            db.chapterDao().getById(id)?.let { it.isOwn && it.mode == ChapterShareMode.SHARED } == true
        }
        if (shared) soon(context)
    }
}

/** Never fails: whoever could not be reached is still behind at the next mailbox check. */
class ChapterPublishWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        try {
            SharedChapterRepository(applicationContext, AppDatabase.getInstance(applicationContext)).publishPending()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not send shared chapters", error)
        }
        return Result.success()
    }
}
