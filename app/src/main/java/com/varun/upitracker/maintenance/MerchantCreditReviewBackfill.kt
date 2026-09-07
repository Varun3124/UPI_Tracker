package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.ActorType

private const val TAG = "MerchantCreditBackfill"

/**
 * One-time backfill: flags merchant credits that still carry expense category splits, so they
 * surface for review under the refund/income split.
 *
 * Before refunds could be linked, `persistCategories` gated on ME's share summed across *both*
 * sides, so a `MERCHANT -> ME` credit wrote category splits exactly as a purchase did, and the old
 * blanket "subtract every merchant credit" spend rule cancelled them out. Neither of those is true
 * any more: a credit is now either a refund linked to the purchase it reverses, or income.
 *
 * The reporting queries already ignore these rows -- they require ME's share to sit on the side
 * matching the category kind -- so this is about getting the data right, not the totals. Flagging
 * puts each one back in front of the user through the existing review flow instead of guessing
 * which are refunds and which are income, or silently deleting a categorisation they made.
 *
 * Guarded by [PREF_DONE] so it only ever scans once.
 */
class MerchantCreditReviewBackfill(private val context: Context) {

    companion object {
        const val PREF_NAME = SmsBacklogScanner.PREF_NAME
        const val PREF_DONE = "merchant_credit_review_backfill_v1_done"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun run() {
        if (prefs.getBoolean(PREF_DONE, false)) return

        val db = AppDatabase.getInstance(context)
        val transactions = db.transactionDao().getTransactionsSinceSync(0L)
        var flagged = 0

        db.withTransaction {
            transactions.forEach { tx ->
                if (tx.isPending) return@forEach
                // Only a merchant paying ME. A purchase keeps its splits untouched.
                if (tx.payerActorType != ActorType.MERCHANT || tx.payeeActorType != ActorType.ME) {
                    return@forEach
                }
                // Already reconciled against the purchase it reverses.
                if (tx.refundsTransactionId != null) return@forEach
                if (db.categorySplitDao().getForTransaction(tx.id).isEmpty()) return@forEach

                db.transactionDao().update(tx.copy(isPending = true))
                flagged++
            }
        }

        prefs.edit().putBoolean(PREF_DONE, true).apply()
        Log.d(TAG, "Merchant credit review backfill complete: flagged=$flagged of scanned=${transactions.size}")
    }
}
