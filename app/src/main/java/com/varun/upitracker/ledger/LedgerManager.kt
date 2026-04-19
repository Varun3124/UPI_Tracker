package com.varun.upitracker.ledger

import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.IouEntry

private const val TAG = "LedgerManager"

data class FriendLedgerSummary(
    val friendId: Long,
    val friendName: String,
    val netBalancePaise: Long,
    val totalTheyOwedYou: Long,
    val totalYouOwedThem: Long,
    val lastActivityEpoch: Long?
)

class LedgerManager(private val db: AppDatabase) {

    suspend fun recordBalanceChange(
        transactionId: Long,
        friendId: Long,
        deltaPaise: Long
    ) {
        if (deltaPaise == 0L) return

        db.iouDao().insert(
            IouEntry(
                transactionId = transactionId,
                friendId = friendId,
                amountPaise = deltaPaise,
                isSettled = false
            )
        )
        Log.d(TAG, "Recorded balance change friend=$friendId delta=$deltaPaise on tx=$transactionId")
    }

    suspend fun recordDebts(
        transactionId: Long,
        friendShares: Map<Long, Long>
    ) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                friendShares.forEach { (friendId, amountPaise) ->
                    recordBalanceChange(transactionId, friendId, amountPaise)
                }
            }
        }
    }

    suspend fun recordReverseDebt(
        transactionId: Long,
        friendId: Long,
        amountPaise: Long
    ) {
        recordBalanceChange(transactionId, friendId, -amountPaise)
    }

    suspend fun applyRepayment(
        transactionId: Long,
        friendId: Long,
        creditAmountPaise: Long
    ) {
        applyAgainstPositiveBalance(transactionId, friendId, creditAmountPaise)
    }

    suspend fun applyOutgoingSettlement(
        transactionId: Long,
        friendId: Long,
        debitAmountPaise: Long
    ) {
        applyAgainstNegativeBalance(transactionId, friendId, debitAmountPaise)
    }

    suspend fun getSummaryForFriend(friendId: Long): FriendLedgerSummary? {
        val friend = db.friendDao().getFriendById(friendId) ?: return null
        val netBalance = db.iouDao().getNetBalanceForFriend(friendId) ?: 0L
        val lastActivity = db.iouDao().getLastActivityEpoch(friendId)
        val allFriendEntries = db.iouDao().getAllEntriesForFriend(friendId)

        val totalTheyOwedYou = allFriendEntries
            .filter { it.amountPaise > 0 }
            .sumOf { it.amountPaise }
        val totalYouOwedThem = allFriendEntries
            .filter { it.amountPaise < 0 }
            .sumOf { -it.amountPaise }

        return FriendLedgerSummary(
            friendId = friendId,
            friendName = friend.name,
            netBalancePaise = netBalance,
            totalTheyOwedYou = totalTheyOwedYou,
            totalYouOwedThem = totalYouOwedThem,
            lastActivityEpoch = lastActivity
        )
    }

    suspend fun getAllSummaries(): List<FriendLedgerSummary> {
        val balances = db.iouDao().getAllNetBalances()
        return balances.mapNotNull { getSummaryForFriend(it.friendId) }
            .sortedByDescending { kotlin.math.abs(it.netBalancePaise) }
    }

    private suspend fun applyAgainstPositiveBalance(
        transactionId: Long,
        friendId: Long,
        amountPaise: Long
    ) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val unsettled = db.iouDao().getPositiveUnsettledOldestFirst(friendId)
                var remaining = amountPaise

                unsettled.forEach { entry ->
                    if (remaining <= 0) return@forEach
                    remaining = settleEntry(entry, remaining)
                }

                if (remaining > 0) {
                    recordBalanceChange(transactionId, friendId, -remaining)
                }
            }
        }
    }

    private suspend fun applyAgainstNegativeBalance(
        transactionId: Long,
        friendId: Long,
        amountPaise: Long
    ) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val unsettled = db.iouDao().getNegativeUnsettledOldestFirst(friendId)
                var remaining = amountPaise

                unsettled.forEach { entry ->
                    if (remaining <= 0) return@forEach
                    remaining = settleEntry(entry, remaining)
                }

                if (remaining > 0) {
                    recordBalanceChange(transactionId, friendId, remaining)
                }
            }
        }
    }

    private suspend fun settleEntry(entry: IouEntry, settlementAmount: Long): Long {
        val magnitude = kotlin.math.abs(entry.amountPaise)
        val now = System.currentTimeMillis()

        return if (settlementAmount >= magnitude) {
            db.iouDao().update(
                entry.copy(
                    isSettled = true,
                    settledEpoch = now
                )
            )
            settlementAmount - magnitude
        } else {
            val residualMagnitude = magnitude - settlementAmount
            val residualSigned = if (entry.amountPaise >= 0) residualMagnitude else -residualMagnitude

            db.iouDao().update(
                entry.copy(
                    isSettled = true,
                    settledEpoch = now
                )
            )
            db.iouDao().insert(
                IouEntry(
                    transactionId = entry.transactionId,
                    friendId = entry.friendId,
                    amountPaise = residualSigned,
                    isSettled = false
                )
            )
            0L
        }
    }
}
