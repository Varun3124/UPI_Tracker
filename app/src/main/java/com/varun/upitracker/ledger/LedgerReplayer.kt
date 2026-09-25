package com.varun.upitracker.ledger

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/**
 * Forwards only what concerns [friendIds], and drops the rest on the floor.
 *
 * A transaction being replayed may also name a friend outside the set, whose entries were never
 * deleted and are not being rebuilt. Posting those again would double them.
 */
class ScopedLedgerPort(
    private val delegate: LedgerPort,
    private val friendIds: Set<Long>
) : LedgerPort {

    override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
        if (friendId in friendIds) delegate.recordBalanceChange(transactionId, friendId, deltaPaise)
    }

    override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
        if (friendId in friendIds) delegate.applyRepayment(transactionId, friendId, creditAmountPaise)
    }

    override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
        if (friendId in friendIds) delegate.applyOutgoingSettlement(transactionId, friendId, debitAmountPaise)
    }
}

/**
 * Rebuilds the base ledger for a set of friends from scratch.
 *
 * Needed because a tagged transaction posts nothing to `iou_entries` (R15), so when one moves in or
 * out of a chapter its entries cannot simply be deleted. Deleting them throws away the
 * settle-oldest-first bookkeeping built on top of them:
 *
 *   1. A dinner leaves Rahul owing 500. That is an entry of +500.
 *   2. Rahul later pays 200 back. The repayment marks the +500 settled and inserts a residual +300
 *      -- attributed to the *dinner*, not to the repayment.
 *   3. Tag the repayment into a chapter. Deleting the repayment's own entries deletes nothing, it
 *      owns none; the residual +300 stands, and the chapter adds its own -200 on top.
 *
 * Replaying instead gives the right +300. It works because a port call moves the unsettled net by a
 * fixed amount whatever it finds -- `applyRepayment(A)` settles what it can and records the rest,
 * moving the net by exactly -A either way -- so the net is a function of the multiset of calls and
 * not of their order. Reproducing the same calls reproduces the same balances. Which *entries* end
 * up flagged settled can differ, and that is the only thing that does.
 *
 * Two orderings are not optional. The rows must be read before the delete, because the selection
 * asks about entries the delete destroys; and `transactions.chapterId` must already be written,
 * because the selection is what decides which rows still belong to the base ledger.
 */
class LedgerReplayer(
    private val source: Source,
    private val ledger: LedgerPort,
    private val postingService: LedgerPostingService = LedgerPostingService()
) {

    /**
     * Where the rows come from. [RoomReplaySource] is the real one; a test hands over a fixed list,
     * which is the only way any of this is reachable from a plain JVM test.
     */
    interface Source {
        suspend fun rowsFor(friendIds: Set<Long>): List<TaggedTx>
        suspend fun clearEntriesFor(friendIds: Set<Long>)
    }

    suspend fun replay(friendIds: Set<Long>) {
        // Not just an optimisation: Room renders an empty list as `IN ()`, which SQLite rejects.
        if (friendIds.isEmpty()) return

        val rows = order(source.rowsFor(friendIds))
        source.clearEntriesFor(friendIds)

        val scoped = ScopedLedgerPort(ledger, friendIds)
        rows.forEach { (tx, shares) ->
            postingService.postLedger(
                scoped,
                tx.id,
                tx.payerActorRef(),
                tx.payeeActorRef(),
                shares,
                tx.amountPaise,
                tx.ledgerEffect,
                IouLegs.resolve(tx, shares)
            )
        }
    }

    companion object {
        /**
         * Oldest first, id breaking a date tie.
         *
         * Date order is what settle-oldest-first already assumes -- `getPositiveUnsettledOldestFirst`
         * sorts by the transaction's date -- so replaying in it means a repayment always finds the
         * debts that predate it. Id order within a date is save order, which is the order the live
         * entries were posted in.
         */
        fun order(rows: List<TaggedTx>): List<TaggedTx> =
            rows.sortedWith(compareBy({ it.transaction.dateEpoch }, { it.transaction.id }))
    }
}

/** The real [LedgerReplayer.Source]. Plain suspend calls: it runs inside the caller's transaction. */
class RoomReplaySource(private val db: AppDatabase) : LedgerReplayer.Source {

    override suspend fun rowsFor(friendIds: Set<Long>): List<TaggedTx> {
        val ids = friendIds.toList()
        val transactions = db.transactionDao().getUntaggedPostedForFriends(ids)
        if (transactions.isEmpty()) return emptyList()
        val sharesByTransaction = db.transactionShareDao()
            .getSharesForTransactions(transactions.map { it.id })
            .groupBy { it.transactionId }
        return transactions.map { TaggedTx(it, sharesByTransaction[it.id].orEmpty()) }
    }

    override suspend fun clearEntriesFor(friendIds: Set<Long>) {
        db.iouDao().deleteForFriends(friendIds.toList())
    }
}
