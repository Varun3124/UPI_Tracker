package com.varun.upitracker.sms.receiver

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.meShareOnSide
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.payeeActorRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object PendingTransactionReviewer {

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
                    postLedger(db, tx.id, tx.payerActorRef(), tx.payeeActorRef(), shares, tx.amountPaise)
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
        val payerKnown = tx.payerActorType != ActorType.UNKNOWN
        val payeeKnown = tx.payeeActorType != ActorType.UNKNOWN
        val payerLabelPresent = tx.payerActorType == ActorType.ME || !tx.payerRawLabel.isNullOrBlank() || tx.payerFriendId != null || tx.payerMerchantId != null
        val payeeLabelPresent = tx.payeeActorType == ActorType.ME || !tx.payeeRawLabel.isNullOrBlank() || tx.payeeFriendId != null || tx.payeeMerchantId != null
        return payerKnown && payeeKnown && payerLabelPresent && payeeLabelPresent
    }

    private suspend fun postLedger(
        db: AppDatabase,
        transactionId: Long,
        payer: ActorRef,
        payee: ActorRef,
        shares: List<com.varun.upitracker.database.entity.TransactionShare>,
        amountPaise: Long
    ) {
        val ledger = LedgerManager(db)
        if (payer.actorType == ActorType.FRIEND && payee.actorType == ActorType.ME && payer.friendId != null) {
            ledger.applyRepayment(transactionId, payer.friendId, amountPaise)
            return
        }
        if (payer.actorType == ActorType.ME && payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            ledger.applyOutgoingSettlement(transactionId, payee.friendId, amountPaise)
            return
        }
        if (payer.actorType == ActorType.ME) {
            shares.filter { it.side == "PAYER" && it.participantType == ActorType.FRIEND && it.friendId != null }
                .forEach { ledger.recordBalanceChange(transactionId, it.friendId!!, it.amountPaise) }
            return
        }
        val meShare = meShareOnSide(shares, "PAYEE")
        if (payer.actorType == ActorType.FRIEND && payer.friendId != null && meShare > 0L) {
            ledger.recordBalanceChange(transactionId, payer.friendId, -meShare)
        }
    }
}
