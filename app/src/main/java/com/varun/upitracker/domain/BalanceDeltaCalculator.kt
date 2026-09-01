package com.varun.upitracker.domain

data class TransferDeltaInput(
    val fromAccountId: String?,
    val toAccountId: String?,
    val amountFromPaise: Long,
    val amountToPaise: Long
)

object BalanceDeltaCalculator {

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
