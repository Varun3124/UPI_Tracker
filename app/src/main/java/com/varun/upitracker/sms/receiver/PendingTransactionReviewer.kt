package com.varun.upitracker.sms.receiver

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.payeeActorRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object PendingTransactionReviewer {

    /**
     * Shared with the manual entry screen on purpose. This file used to carry its own copy, which
     * had drifted: it settled a FRIEND -> ME transaction even when it carried shares, and never
     * reached the payee-side legs at all.
     */
    private val ledgerPostingService = LedgerPostingService()

    suspend fun review(context: Context, transactionId: Long): Boolean {
        val db = AppDatabase.getInstance(context)
        val tx = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(transactionId) } ?: return false
        if (!tx.isPending) return true
        // A tagged transaction posts nothing to the base ledger, and confirming it has to recompute
        // its chapter. Both are the entry screen's business -- and the screen is also where the user
        // can see which chapter they are confirming into.
        if (tx.chapterId != null) return false
        if (!PendingReviewRules.canAutoReview(tx)) return false

        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        if (!PendingReviewRules.sharesAreValid(tx, shares)) return false

        return withContext(Dispatchers.IO) {
            db.runInTransaction<Boolean> {
                runBlocking {
                    // Written down, not just used: a row still missing one would otherwise look to
                    // IouRecoveryBackfill like one whose entries the old inference posted, and get
                    // corrected a second time.
                    val iouRecovery = IouLegs.resolve(tx, shares)
                    val updated = tx.copy(isPending = false, iouRecovery = iouRecovery)
                    db.transactionDao().update(updated)
                    db.iouDao().deleteForTransaction(tx.id)
                    ledgerPostingService.postLedger(
                        LedgerManager(db), tx.id, tx.payerActorRef(), tx.payeeActorRef(),
                        shares, tx.amountPaise, tx.ledgerEffect, iouRecovery
                    )
                    true
                }
            }
        }
    }
}
