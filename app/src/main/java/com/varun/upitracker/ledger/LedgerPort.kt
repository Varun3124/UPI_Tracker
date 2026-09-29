package com.varun.upitracker.ledger

/**
 * The ledger writes [com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService]
 * and [LedgerReplayer] actually perform.
 *
 * Exists so posting policy can be tested without a database. [LedgerManager] is the real
 * implementation; a recording fake stands in for it in unit tests. Mirrors the
 * `AccountBalanceDataSource` seam that already exists for balance maths.
 */
interface LedgerPort {
    suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long)
    suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long)
    suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long)

    /**
     * A checkpoint's opening balance: everything the friend and ME agreed on, less the chapter shares
     * it counted. Always the oldest debt in their base ledger, so it is settled first. Only
     * [LedgerReplayer] posts one, and always before any transaction. See docs/declarations-design.md D7.
     */
    suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long)
}
