package com.varun.upitracker.sms.receiver

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.ActorType
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
        if (!canAutoReview(tx)) return false

        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        if (!sharesAreValid(tx, shares)) return false

        return withContext(Dispatchers.IO) {
            db.runInTransaction<Boolean> {
                runBlocking {
                    val updated = tx.copy(isPending = false)
                    db.transactionDao().update(updated)
                    db.iouDao().deleteForTransaction(tx.id)
                    ledgerPostingService.postLedger(
                        LedgerManager(db), tx.id, tx.payerActorRef(), tx.payeeActorRef(),
                        shares, tx.amountPaise, tx.ledgerEffect
                    )
                    true
                }
            }
        }
    }

    private fun sharesAreValid(tx: com.varun.upitracker.database.entity.Transaction, shares: List<com.varun.upitracker.database.entity.TransactionShare>): Boolean {
        val hasSides = shares.any { it.side != null }
        if (!hasSides) return shares.sumOf { it.amountPaise } <= tx.amountPaise
        if (tx.payerActorType != ActorType.MERCHANT) {
            val payerSum = shares.filter { it.side == "PAYER" }.sumOf { it.amountPaise }
            if (payerSum != tx.amountPaise) return false
        }
        if (tx.payeeActorType != ActorType.MERCHANT) {
            val payeeSum = shares.filter { it.side == "PAYEE" }.sumOf { it.amountPaise }
            if (payeeSum != tx.amountPaise) return false
        }
        return true
    }

    private fun canAutoReview(tx: com.varun.upitracker.database.entity.Transaction): Boolean {
        // Money arriving from a friend is either a repayment or a gift, and nothing in an SMS
        // says which. Auto-reviewing it as a repayment settles debt that may still be owed, so
        // leave the notification up and let the user declare it.
        if (tx.payerActorType == ActorType.FRIEND && tx.payeeActorType == ActorType.ME) return false
        val payerKnown = tx.payerActorType != ActorType.UNKNOWN
        val payeeKnown = tx.payeeActorType != ActorType.UNKNOWN
        val payerLabelPresent = tx.payerActorType == ActorType.ME || !tx.payerRawLabel.isNullOrBlank() || tx.payerFriendId != null || tx.payerMerchantId != null
        val payeeLabelPresent = tx.payeeActorType == ActorType.ME || !tx.payeeRawLabel.isNullOrBlank() || tx.payeeFriendId != null || tx.payeeMerchantId != null
        return payerKnown && payeeKnown && payerLabelPresent && payeeLabelPresent
    }

}
