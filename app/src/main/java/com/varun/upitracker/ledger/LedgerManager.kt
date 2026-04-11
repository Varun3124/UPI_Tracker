package com.varun.upitracker.ledger

import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.IouEntry

private const val TAG = "LedgerManager"

data class FriendLedgerSummary(
    val friendId: Long,
    val friendName: String,
    val netBalancePaise: Long,        // Positive = they owe you, Negative = you owe them
    val totalTheyOwedYou: Long,       // Lifetime sum of positive entries
    val totalYouOwedThem: Long,       // Lifetime sum of negative entries
    val lastActivityEpoch: Long?
)

class LedgerManager(private val db: AppDatabase) {

    // ------------------------------------------------------------------
    // Called when a DEBIT transaction has IOU friends selected in overlay
    // ------------------------------------------------------------------

    /**
     * Records debt entries for one or more friends on a single transaction.
     * Each entry is independent — friend owes you their share.
     * All inserts are wrapped in a DB transaction for atomicity.
     */
    suspend fun recordDebts(
        transactionId: Long,
        friendShares: Map<Long, Long>   // friendId → amountPaise they owe you
    ) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                friendShares.forEach { (friendId, amountPaise) ->
                    db.iouDao().insert(
                        IouEntry(
                            transactionId = transactionId,
                            friendId      = friendId,
                            amountPaise   = amountPaise,  // Positive = they owe you
                            isSettled     = false
                        )
                    )
                    Log.d(TAG, "Recorded debt: friend=$friendId owes ₹${amountPaise/100.0} on tx=$transactionId")
                }
            }
        }
    }

    /**
     * Records a debt where YOU owe a friend (e.g. friend paid for you).
     * Stored as a negative amountPaise.
     */
    suspend fun recordReverseDebt(
        transactionId: Long,
        friendId: Long,
        amountPaise: Long   // Will be stored as negative
    ) {
        db.iouDao().insert(
            IouEntry(
                transactionId = transactionId,
                friendId      = friendId,
                amountPaise   = -amountPaise,   // Negative = you owe them
                isSettled     = false
            )
        )
        Log.d(TAG, "Recorded reverse debt: you owe friend=$friendId ₹${amountPaise/100.0} on tx=$transactionId")
    }

    // ------------------------------------------------------------------
    // Called when a CREDIT is confirmed as a friend repayment in overlay
    // ------------------------------------------------------------------

    /**
     * Applies a repayment from a friend against their oldest unsettled debts.
     * Preserves full history — partially paid entries are NOT deleted,
     * instead a new residual entry is created for the unpaid remainder.
     *
     * Cases:
     * - Credit < total debt  → settle oldest entries, create residual for remainder
     * - Credit = total debt  → all entries settled cleanly
     * - Credit > total debt  → all entries settled, surplus recorded as you owing them
     */
    suspend fun applyRepayment(
        transactionId: Long,
        friendId: Long,
        creditAmountPaise: Long
    ) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val unsettled = db.iouDao().getUnsettledOldestFirst(friendId)
                var remaining = creditAmountPaise

                Log.d(TAG, "Applying repayment of ₹${creditAmountPaise/100.0} from friend=$friendId")
                Log.d(TAG, "Unsettled entries: ${unsettled.size}, total debt: ₹${unsettled.sumOf { it.amountPaise }/100.0}")

                for (entry in unsettled) {
                    if (remaining <= 0) break

                    when {
                        // Remaining credit fully covers this entry → settle it completely
                        remaining >= entry.amountPaise -> {
                            db.iouDao().update(
                                entry.copy(
                                    isSettled    = true,
                                    settledEpoch = System.currentTimeMillis()
                                )
                            )
                            remaining -= entry.amountPaise
                            Log.d(TAG, "Fully settled entry=${entry.id} (₹${entry.amountPaise/100.0}), remaining=₹${remaining/100.0}")
                        }

                        // Remaining credit only partially covers this entry →
                        // Mark original as settled, create a new residual entry
                        // for the unpaid portion. History is preserved.
                        else -> {
                            val residual = entry.amountPaise - remaining

                            // Settle the original entry
                            db.iouDao().update(
                                entry.copy(
                                    isSettled    = true,
                                    settledEpoch = System.currentTimeMillis()
                                )
                            )

                            // Create a new residual entry linked to the SAME original transaction
                            // so history shows which transaction the remaining debt came from
                            db.iouDao().insert(
                                IouEntry(
                                    transactionId = entry.transactionId,
                                    friendId      = friendId,
                                    amountPaise   = residual,
                                    isSettled     = false
                                )
                            )
                            Log.d(TAG, "Partially settled entry=${entry.id}, residual=₹${residual/100.0} preserved")
                            remaining = 0
                        }
                    }
                }

                // Credit exceeded total debt — surplus means you now owe them the difference
                if (remaining > 0) {
                    db.iouDao().insert(
                        IouEntry(
                            transactionId = transactionId,
                            friendId      = friendId,
                            amountPaise   = -remaining,   // Negative = you owe them
                            isSettled     = false
                        )
                    )
                    Log.d(TAG, "Credit exceeded debt by ₹${remaining/100.0}, recorded as you owing friend=$friendId")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Summary queries — used by dashboard in Step 12
    // ------------------------------------------------------------------

    /**
     * Returns a full ledger summary for a single friend.
     */
    suspend fun getSummaryForFriend(friendId: Long): FriendLedgerSummary? {
        val friend           = db.friendDao().getFriendById(friendId) ?: return null
        val netBalance       = db.iouDao().getNetBalanceForFriend(friendId) ?: 0L
        val lastActivity     = db.iouDao().getLastActivityEpoch(friendId)
        val allFriendEntries = db.iouDao().getAllEntriesForFriend(friendId)

        val totalTheyOwedYou = allFriendEntries
            .filter { it.amountPaise > 0 }.sumOf { it.amountPaise }
        val totalYouOwedThem = allFriendEntries
            .filter { it.amountPaise < 0 }.sumOf { -it.amountPaise }

        return FriendLedgerSummary(
            friendId          = friendId,
            friendName        = friend.name,
            netBalancePaise   = netBalance,
            totalTheyOwedYou  = totalTheyOwedYou,
            totalYouOwedThem  = totalYouOwedThem,
            lastActivityEpoch = lastActivity
        )
    }

    /**
     * Returns summaries for ALL friends who have any IOU history.
     * Used by the home screen IOU section.
     */
    suspend fun getAllSummaries(): List<FriendLedgerSummary> {
        val balances = db.iouDao().getAllNetBalances()
        return balances.mapNotNull { getSummaryForFriend(it.friendId) }
            .sortedByDescending { Math.abs(it.netBalancePaise) }  // Largest balances first
    }
}