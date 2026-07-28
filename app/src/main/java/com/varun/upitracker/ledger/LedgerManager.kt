package com.varun.upitracker.ledger

import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.database.AppDatabase

typealias FriendLedgerSummary = com.varun.upitracker.data.repository.FriendLedgerSummary

class LedgerManager(private val db: AppDatabase) {
    private val repository = LedgerRepository(db)

    suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) =
        repository.recordBalanceChange(transactionId, friendId, deltaPaise)

    suspend fun recordDebts(transactionId: Long, friendShares: Map<Long, Long>) =
        repository.recordDebts(transactionId, friendShares)

    suspend fun recordReverseDebt(transactionId: Long, friendId: Long, amountPaise: Long) =
        repository.recordReverseDebt(transactionId, friendId, amountPaise)

    suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) =
        repository.applyRepayment(transactionId, friendId, creditAmountPaise)

    suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) =
        repository.applyOutgoingSettlement(transactionId, friendId, debitAmountPaise)

    suspend fun getSummaryForFriend(friendId: Long): FriendLedgerSummary? =
        repository.getSummaryForFriend(friendId)

    suspend fun getAllSummaries(): List<FriendLedgerSummary> =
        repository.getAllSummaries()
}
