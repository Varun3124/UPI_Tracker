package com.varun.upitracker.domain.transactionentry.validation

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType

/**
 * Whether a pending transaction is complete enough to confirm without asking the user.
 *
 * Lifted out of [com.varun.upitracker.sms.receiver.PendingTransactionReviewer], which needs a
 * Context and so cannot be reached from a plain JUnit test. These two rules decide whether money
 * moves, and the parcel importer has to predict their answer to tell a friend which rows it will
 * still have to ask about -- both are reasons to have them under test.
 */
object PendingReviewRules {

    /**
     * Total: the payer and payee sides each have to account for the whole amount.
     *
     * A merchant side is exempt because the app does not split what a shop paid or was paid; the
     * shares on that side describe the people, not the counterparty.
     */
    fun sharesAreValid(tx: Transaction, shares: List<TransactionShare>): Boolean {
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

    fun canAutoReview(tx: Transaction): Boolean {
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
