package com.varun.upitracker.ledger

import com.varun.upitracker.database.entity.IouEntry
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.declaration.Opening

/**
 * [com.varun.upitracker.data.repository.LedgerRepository]'s bookkeeping with no database behind it:
 * the same [SettleMath], over entries in the order `IouDao`'s oldest-first queries put them --
 * openings first, then by the date of each entry's transaction, then by id.
 *
 * [dateOf] maps a transaction id to its date, the part of the query's join a list cannot do itself.
 */
class InMemoryLedger(private val dateOf: (Long) -> Long) : LedgerPort {

    private val entries = mutableListOf<IouEntry>()
    private var nextId = 1L

    val all: List<IouEntry> get() = entries.toList()

    /** What `IouDao.getNetBalanceForFriend` returns: the unsettled entries, summed. */
    fun net(friendId: Long): Long =
        entries.filter { it.friendId == friendId && !it.isSettled }.sumOf { it.amountPaise }

    /** What `IouDao.deleteForFriends` does. */
    fun clear(friendIds: Set<Long>) {
        entries.removeAll { it.friendId in friendIds }
    }

    /** What deleting a transaction row does to the ledger by itself: its own entries CASCADE, nothing else. */
    fun cascadeDeleteOf(transactionId: Long) {
        entries.removeAll { it.transactionId == transactionId }
    }

    override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
        if (deltaPaise != 0L) insert(IouEntry(transactionId = transactionId, friendId = friendId, amountPaise = deltaPaise))
    }

    override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) =
        settle(transactionId, friendId, creditAmountPaise, againstPositive = true)

    override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) =
        settle(transactionId, friendId, debitAmountPaise, againstPositive = false)

    override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) {
        if (amountPaise != 0L) {
            insert(
                IouEntry(
                    transactionId = null,
                    friendId = friendId,
                    amountPaise = amountPaise,
                    declarationId = declarationId
                )
            )
        }
    }

    private suspend fun settle(transactionId: Long, friendId: Long, amountPaise: Long, againstPositive: Boolean) {
        val oldestFirst = entries
            .filter {
                it.friendId == friendId && !it.isSettled &&
                    if (againstPositive) it.amountPaise > 0L else it.amountPaise < 0L
            }
            .sortedWith(
                compareBy<IouEntry>(
                    { it.transactionId != null },
                    { it.transactionId?.let(dateOf) ?: Long.MIN_VALUE },
                    { it.id }
                )
            )
        val outcome = SettleMath.settle(oldestFirst, amountPaise, now = 0L)
        outcome.settled.forEach { settled -> entries[entries.indexOfFirst { it.id == settled.id }] = settled }
        outcome.residual?.let(::insert)
        if (outcome.remainderPaise > 0L) {
            recordBalanceChange(
                transactionId,
                friendId,
                if (againstPositive) -outcome.remainderPaise else outcome.remainderPaise
            )
        }
    }

    private fun insert(entry: IouEntry) {
        entries += entry.copy(id = nextId++)
    }
}

/**
 * A [LedgerReplayer.Source] over rows held in memory, clearing an [InMemoryLedger]. "Involves" is
 * [ChapterMath.friendsIn], as the real query's first three clauses are.
 */
class ListReplaySource(
    private val rows: () -> List<TaggedTx>,
    private val openings: () -> Map<Long, Opening>,
    private val ledger: InMemoryLedger
) : LedgerReplayer.Source {

    override suspend fun openingsFor(friendIds: Set<Long>): Map<Long, Opening> =
        openings().filterKeys { it in friendIds }

    override suspend fun rowsFor(friendIds: Set<Long>, afterEpoch: Long?): List<TaggedTx> =
        rows().filter { tagged ->
            (afterEpoch == null || tagged.transaction.dateEpoch > afterEpoch) &&
                !tagged.transaction.isPending &&
                tagged.transaction.chapterId == null &&
                ChapterMath.friendsIn(tagged.transaction, tagged.shares).any { it in friendIds }
        }

    override suspend fun clearEntriesFor(friendIds: Set<Long>) = ledger.clear(friendIds)
}
