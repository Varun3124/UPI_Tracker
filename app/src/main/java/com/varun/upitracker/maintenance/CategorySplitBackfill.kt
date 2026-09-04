package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking

private const val TAG = "CategorySplitBackfill"

/**
 * One-time backfill: flags already-saved merchant transactions whose category splits
 * don't sum to ME's share as pending, so they surface for review under the new
 * mandatory-category-split rule. Guarded by [PREF_DONE] so it only ever scans once.
 */
class CategorySplitBackfill(private val context: Context) {

    companion object {
        const val PREF_NAME = SmsBacklogScanner.PREF_NAME
        const val PREF_DONE = "category_split_backfill_v1_done"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun run() {
        if (prefs.getBoolean(PREF_DONE, false)) return

        val db = AppDatabase.getInstance(context)
        val transactions = db.transactionDao().getTransactionsSinceSync(0L)
        var flagged = 0

        db.runInTransaction {
            runBlocking {
                transactions.forEach { tx ->
                    val merchantInvolved =
                        tx.payerActorType == ActorType.MERCHANT || tx.payeeActorType == ActorType.MERCHANT
                    if (!merchantInvolved || tx.isPending) return@forEach

                    val meSharePaise = db.transactionShareDao().getSharesForTransaction(tx.id)
                        .filter { it.participantType == ActorType.ME }
                        .sumOf { it.amountPaise }
                    if (meSharePaise <= 0L) return@forEach

                    val allocatedPaise = db.categorySplitDao().getForTransaction(tx.id)
                        .sumOf { it.myAmountPaise }

                    if (allocatedPaise != meSharePaise) {
                        db.transactionDao().update(tx.copy(isPending = true))
                        flagged++
                    }
                }
            }
        }

        prefs.edit().putBoolean(PREF_DONE, true).apply()
        Log.d(TAG, "Category split backfill complete: flagged=$flagged of scanned=${transactions.size}")
    }
}
