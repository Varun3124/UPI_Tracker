package com.varun.upitracker.ledger

/**
 * The three ledger writes [com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService]
 * actually performs.
 *
 * Exists so posting policy can be tested without a database. [LedgerManager] is the real
 * implementation; a recording fake stands in for it in unit tests. Mirrors the
 * `AccountBalanceDataSource` seam that already exists for balance maths.
 */
interface LedgerPort {
    suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long)
    suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long)
    suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long)
}
