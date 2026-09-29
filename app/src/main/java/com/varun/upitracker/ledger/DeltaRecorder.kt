package com.varun.upitracker.ledger

/**
 * Adds up what would be posted against each friend, without a database behind it: positive means
 * they end up owing ME, the sign `IouEntry.amountPaise` uses.
 *
 * Used wherever a figure has to agree exactly with what the ledger would do -- a parcel row's preview,
 * a late row's effect, a balance after a proposal is accepted -- by running the real posting service
 * into it rather than reasoning about payer and payee a second time. A second copy of those rules
 * would drift, and a preview that disagrees with what the ledger then does is worse than none.
 */
class DeltaRecorder : LedgerPort {

    val deltas = linkedMapOf<Long, Long>()

    fun of(friendId: Long): Long = deltas[friendId] ?: 0L

    override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
        add(friendId, deltaPaise)
    }

    /** They paid me, so what they owe me falls. */
    override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
        add(friendId, -creditAmountPaise)
    }

    /** I paid them, so what they owe me rises. */
    override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
        add(friendId, debitAmountPaise)
    }

    override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) {
        add(friendId, amountPaise)
    }

    private fun add(friendId: Long, deltaPaise: Long) {
        deltas[friendId] = (deltas[friendId] ?: 0L) + deltaPaise
    }
}
