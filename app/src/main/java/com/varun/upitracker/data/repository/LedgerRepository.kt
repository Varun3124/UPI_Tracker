package com.varun.upitracker.data.repository

import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.IouEntry

private const val LEDGER_TAG = "LedgerRepository"

/**
 * What one chapter's plan does to this friend's balance. Zero means they belong to it but come out
 * even -- `chapter_balances` stores no row in that case, so it is the membership that puts it here.
 */
data class FriendChapterBalance(
    val chapterId: Long,
    val name: String,
    val state: ChapterState,
    val amountPaise: Long
)

data class FriendLedgerSummary(
    val friendId: Long,
    val friendName: String,

    /**
     * What this friend owes ME altogether: their personal balance plus their share of every
     * chapter's plan (R16). This is the figure the dashboard and the friend page show.
     */
    val netBalancePaise: Long,

    /** The base ledger alone -- unsettled `iou_entries`, with no chapter in it. */
    val personalBalancePaise: Long,

    /** Every chapter this friend is in, newest first. */
    val chapterBalances: List<FriendChapterBalance>,

    val totalTheyOwedYou: Long,
    val totalYouOwedThem: Long,
    val lastActivityEpoch: Long?
)

class LedgerRepository(private val db: AppDatabase) {

    suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
        if (deltaPaise == 0L) return
        db.iouDao().insert(
            IouEntry(
                transactionId = transactionId,
                friendId = friendId,
                amountPaise = deltaPaise,
                isSettled = false
            )
        )
        Log.d(LEDGER_TAG, "Recorded balance change friend=$friendId delta=$deltaPaise on tx=$transactionId")
    }

    suspend fun recordDebts(transactionId: Long, friendShares: Map<Long, Long>) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                friendShares.forEach { (friendId, amountPaise) ->
                    recordBalanceChange(transactionId, friendId, amountPaise)
                }
            }
        }
    }

    suspend fun recordReverseDebt(transactionId: Long, friendId: Long, amountPaise: Long) {
        recordBalanceChange(transactionId, friendId, -amountPaise)
    }

    suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
        applyAgainstPositiveBalance(transactionId, friendId, creditAmountPaise)
    }

    suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
        applyAgainstNegativeBalance(transactionId, friendId, debitAmountPaise)
    }

    suspend fun getSummaryForFriend(friendId: Long): FriendLedgerSummary? {
        val friend = db.friendDao().getFriendById(friendId) ?: return null
        val personal = db.iouDao().getNetBalanceForFriend(friendId) ?: 0L
        val allFriendEntries = db.iouDao().getAllEntriesForFriend(friendId)

        // Memberships carry every chapter the friend is in, including the ones they come out even
        // in; balances are the authority on the money. Merging the two means a contribution can
        // never be missed because a membership row went astray.
        val chapters = linkedMapOf<Long, FriendChapterBalance>()
        db.chapterDao().membershipsForFriend(friendId).forEach {
            chapters[it.chapterId] = FriendChapterBalance(it.chapterId, it.name, it.state, it.amountPaise ?: 0L)
        }
        db.chapterDao().balancesForFriend(friendId).forEach {
            chapters[it.chapterId] = FriendChapterBalance(it.chapterId, it.name, it.state, it.amountPaise)
        }
        val chapterBalances = chapters.values.toList()
        val chapterTotal = chapterBalances.sumOf { it.amountPaise }

        // Once a friend's whole history is tagged they hold no entries at all, and the entry-based
        // date comes back null.
        val lastActivity = listOfNotNull(
            db.iouDao().getLastActivityEpoch(friendId),
            db.chapterDao().lastTaggedActivityEpoch(friendId)
        ).maxOrNull()

        return FriendLedgerSummary(
            friendId = friendId,
            friendName = friend.name,
            netBalancePaise = personal + chapterTotal,
            personalBalancePaise = personal,
            chapterBalances = chapterBalances,
            // Chapter contributions are folded in, so these cover both books. That does make them a
            // live figure rather than a historical one: a contribution moves whenever the chapter's
            // plan is worked out again.
            totalTheyOwedYou = allFriendEntries.filter { it.amountPaise > 0 }.sumOf { it.amountPaise } +
                chapterBalances.filter { it.amountPaise > 0 }.sumOf { it.amountPaise },
            totalYouOwedThem = allFriendEntries.filter { it.amountPaise < 0 }.sumOf { -it.amountPaise } +
                chapterBalances.filter { it.amountPaise < 0 }.sumOf { -it.amountPaise },
            lastActivityEpoch = lastActivity
        )
    }

    /**
     * Every friend either book has something to say about.
     *
     * Both sources are needed. `getAllNetBalances` only returns friends with *unsettled* entries, so
     * on its own it misses someone whose balance exists purely because a chapter's plan put it there
     * -- which is exactly the friend simplification invented a debt for.
     */
    suspend fun getAllSummaries(): List<FriendLedgerSummary> {
        val friendIds = linkedSetOf<Long>()
        db.iouDao().getAllNetBalances().forEach { friendIds += it.friendId }
        friendIds += db.chapterDao().friendIdsWithBalance()
        return friendIds
            .mapNotNull { getSummaryForFriend(it) }
            .sortedWith(
                compareByDescending<FriendLedgerSummary> { kotlin.math.abs(it.netBalancePaise) }
                    // Stable now that two sources are merged, so equal balances keep one order.
                    .thenBy { it.friendId }
            )
    }

    private suspend fun applyAgainstPositiveBalance(transactionId: Long, friendId: Long, amountPaise: Long) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val unsettled = db.iouDao().getPositiveUnsettledOldestFirst(friendId)
                var remaining = amountPaise
                unsettled.forEach { entry ->
                    if (remaining <= 0) return@forEach
                    remaining = settleEntry(entry, remaining)
                }
                if (remaining > 0) recordBalanceChange(transactionId, friendId, -remaining)
            }
        }
    }

    private suspend fun applyAgainstNegativeBalance(transactionId: Long, friendId: Long, amountPaise: Long) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val unsettled = db.iouDao().getNegativeUnsettledOldestFirst(friendId)
                var remaining = amountPaise
                unsettled.forEach { entry ->
                    if (remaining <= 0) return@forEach
                    remaining = settleEntry(entry, remaining)
                }
                if (remaining > 0) recordBalanceChange(transactionId, friendId, remaining)
            }
        }
    }

    private suspend fun settleEntry(entry: IouEntry, settlementAmount: Long): Long {
        val magnitude = kotlin.math.abs(entry.amountPaise)
        val now = System.currentTimeMillis()
        return if (settlementAmount >= magnitude) {
            db.iouDao().update(entry.copy(isSettled = true, settledEpoch = now))
            settlementAmount - magnitude
        } else {
            val residualMagnitude = magnitude - settlementAmount
            val residualSigned = if (entry.amountPaise >= 0) residualMagnitude else -residualMagnitude
            db.iouDao().update(entry.copy(isSettled = true, settledEpoch = now))
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
