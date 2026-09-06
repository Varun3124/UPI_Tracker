package com.varun.upitracker.domain

import com.varun.upitracker.ui.ActorType.ME

data class TransferDeltaInput(
    val fromAccountId: String?,
    val toAccountId: String?,
    val amountFromPaise: Long,
    val amountToPaise: Long
)

/** The fields of a transaction that decide how it moves an account balance. */
data class TransactionDeltaInput(
    val myAccountId: String?,
    val payerActorType: String,
    val payeeActorType: String,
    val amountPaise: Long
)

object BalanceDeltaCalculator {

    /**
     * How much a transaction moved [accountId]. Positive = balance increases.
     *
     * The whole amount counts, whoever the counterparty was and whether or not the transaction has
     * been reviewed: this answers "what left my bank", not "what did I consume". A split debits the
     * full amount here and the IOU ledger separately tracks what comes back, so a net-worth
     * figure is this plus the unsettled IOU balance, not this alone.
     *
     * Mirrored in SQL by `TransactionDao.getAccountBalanceDeltaBetween`. The two must agree — the
     * balance row is summed in SQL while the per-card running balance accumulates here.
     */
    fun transactionDelta(accountId: String, transaction: TransactionDeltaInput): Long = when {
        transaction.myAccountId != accountId -> 0L
        // ME -> ME is a transfer that was never converted; transfers carry their own delta.
        transaction.payerActorType == ME && transaction.payeeActorType == ME -> 0L
        transaction.payerActorType == ME -> -transaction.amountPaise
        transaction.payeeActorType == ME -> transaction.amountPaise
        else -> 0L
    }

    fun transferDelta(accountId: String, transfer: TransferDeltaInput): Long {
        var delta = 0L
        if (transfer.fromAccountId == accountId) delta -= transfer.amountFromPaise
        if (transfer.toAccountId == accountId) delta += transfer.amountToPaise
        return delta
    }

    /**
     * How much a transfer contributes to spend, i.e. the negation of [transferDelta] summed over
     * every tracked account. Moving money between two of your own accounts costs nothing, so equal
     * legs contribute 0; an ATM fee contributes the fee; an investment gain contributes a negative
     * amount. An untracked endpoint (null account id) means the money genuinely left or entered
     * your world, so the whole leg counts.
     */
    fun expenseDelta(transfer: TransferDeltaInput): Long {
        val out = if (transfer.fromAccountId != null) transfer.amountFromPaise else 0L
        val into = if (transfer.toAccountId != null) transfer.amountToPaise else 0L
        return out - into
    }
}
