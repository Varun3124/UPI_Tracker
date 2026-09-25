package com.varun.upitracker.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterBalance
import com.varun.upitracker.database.entity.ChapterMember
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.chapter.ChapterEligibility
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterResult
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.transactionentry.persistence.ChapterSync
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ledger.LedgerReplayer
import com.varun.upitracker.ledger.RoomReplaySource

private const val TAG = "ChapterRepository"

/** A chapter refused an operation, with a message fit to put in front of the user. */
class ChapterException(message: String) : Exception(message)

/**
 * Chapters, their members, and keeping both books straight when a transaction moves between them.
 *
 * Two shapes of method live here, and the difference matters. The public ones open a database
 * transaction and are what the UI calls. The `…InTransaction` ones are plain suspend functions that
 * assume one is already open -- they are what
 * [com.varun.upitracker.domain.transactionentry.persistence.TransactionPersistenceService] calls
 * from inside its own. Never call a public one from in there: Room's `withTransaction` would go
 * looking for a transaction on another dispatcher and deadlock against the one already held.
 *
 * For the same reason nothing in this file may switch dispatcher. `db.inTransaction()` is
 * thread-local, and a `withContext` would have Room dispatch the DAO call outside the transaction.
 */
class ChapterRepository(private val db: AppDatabase) {

    private val replayer = LedgerReplayer(RoomReplaySource(db), LedgerManager(db))

    // --- chapter lifecycle ------------------------------------------------------------------------

    suspend fun create(name: String, memberIds: Set<Long>, notes: String? = null): Long =
        db.withTransaction {
            val now = System.currentTimeMillis()
            val chapterId = db.chapterDao().insert(
                Chapter(name = name.trim(), createdEpoch = now, notes = notes?.takeIf { it.isNotBlank() })
            )
            addMembersInTransaction(chapterId, memberIds, now)
            chapterId
        }

    suspend fun rename(chapterId: Long, name: String, notes: String? = null) = db.withTransaction {
        val chapter = requireChapter(chapterId)
        db.chapterDao().update(
            chapter.copy(name = name.trim(), notes = notes?.takeIf { it.isNotBlank() })
        )
    }

    /**
     * Freezes a chapter. It keeps feeding the base ledger.
     *
     * Closing one that is not settled is allowed -- friends do not always pay up, and refusing would
     * trap the chapter open forever. The UI warns and lists who still owes; that is its job, not
     * this one's.
     */
    suspend fun close(chapterId: Long) = db.withTransaction {
        val chapter = requireChapter(chapterId)
        db.chapterDao().update(
            chapter.copy(
                state = ChapterState.CLOSED,
                closedEpoch = System.currentTimeMillis(),
                isActive = false
            )
        )
    }

    suspend fun reopen(chapterId: Long) = db.withTransaction {
        val chapter = requireChapter(chapterId)
        db.chapterDao().update(chapter.copy(state = ChapterState.OPEN, closedEpoch = null))
    }

    /** R10. Passing null, or a closed chapter, simply leaves nothing active. */
    suspend fun setActive(chapterId: Long?) {
        db.chapterDao().setActive(chapterId ?: 0L)
    }

    /**
     * R11. Every transaction in the chapter returns to the base ledger; none of them is deleted.
     *
     * The untag has to land before the replay, because what the replay selects is precisely the
     * rows the base ledger should hold -- and that is decided by `chapterId`.
     */
    suspend fun delete(chapterId: Long) = db.withTransaction {
        val tagged = db.chapterDao().taggedTransactions(chapterId)
        val friends = affectedFriends(tagged)
        db.transactionDao().untagChapter(chapterId)
        replayer.replay(friends)
        db.chapterDao().deleteById(chapterId)
    }

    // --- members --------------------------------------------------------------------------------

    suspend fun addMembers(chapterId: Long, friendIds: Set<Long>) = db.withTransaction {
        addMembersInTransaction(chapterId, friendIds, System.currentTimeMillis())
    }

    /** R7: someone who appears in a tagged transaction cannot be taken out from under it. */
    suspend fun removeMember(chapterId: Long, friendId: Long) = db.withTransaction {
        val involved = db.chapterDao().countTaggedInvolving(chapterId, friendId)
        if (involved > 0) {
            val plural = if (involved == 1) "transaction" else "transactions"
            throw ChapterException("They are in $involved $plural here")
        }
        db.chapterDao().removeMember(chapterId, friendId)
        recomputeInTransaction(chapterId)
    }

    // --- tagging ---------------------------------------------------------------------------------

    /**
     * Moves one transaction into [chapterId], or out of every chapter when it is null.
     *
     * What the entry screen does on save is the same sequence, run inside its own transaction -- see
     * `ChapterSync`. This is the path for tagging from a chapter screen, where nothing else about
     * the transaction is changing.
     */
    suspend fun setChapterFor(transactionId: Long, chapterId: Long?) = db.withTransaction {
        val tx = db.transactionDao().getTransactionById(transactionId)
            ?: throw ChapterException("That transaction is gone")
        if (tx.refundsTransactionId != null) {
            throw ChapterException("A refund follows its original purchase")
        }
        assertNotLockedByClosedChapter(tx.chapterId)
        if (chapterId != null) {
            val shares = db.transactionShareDao().getSharesForTransaction(tx.id)
            assertTaggable(tx, shares, chapterId)
        }
        applyChapterChangeInTransaction(tx.id, previous = tx.chapterId, current = chapterId)
    }

    // --- recompute -------------------------------------------------------------------------------

    suspend fun recompute(chapterId: Long) = db.withTransaction { recomputeInTransaction(chapterId) }

    /** What a chapter currently works out, without writing anything. For the chapter screen. */
    suspend fun resultFor(chapterId: Long): ChapterResult = ChapterMath.compute(taggedRows(chapterId))

    // --- the in-transaction half -------------------------------------------------------------------

    /**
     * Rebuilds a chapter's `chapter_balances` from its transactions. Cheap: a chapter is small, so
     * there is nothing to be gained by working out which rows actually changed.
     */
    suspend fun recomputeInTransaction(chapterId: Long) {
        val result = ChapterMath.compute(taggedRows(chapterId))
        if (result.unidentifiedLegCount > 0) {
            // Eligibility should have made this impossible. If it happens the chapter is quietly
            // under-counting, and the reason is worth finding.
            Log.w(TAG, "Chapter $chapterId dropped ${result.unidentifiedLegCount} legs naming someone unidentified")
        }
        db.chapterDao().deleteBalancesForChapter(chapterId)
        val rows = result.contributions.map { (friendId, amountPaise) ->
            ChapterBalance(chapterId = chapterId, friendId = friendId, amountPaise = amountPaise)
        }
        if (rows.isNotEmpty()) db.chapterDao().insertBalances(rows)
    }

    /**
     * The whole of what moving a transaction between books involves, in the order it has to happen.
     *
     * Refunds go first because the replay reads their `chapterId` too (R6); members before the
     * replay for the same reason (R5). The replay itself only runs when the transaction actually
     * crossed between the base ledger and a chapter -- moving from one chapter to another never
     * touched `iou_entries`, and neither did a row being created for the first time.
     */
    suspend fun applyChapterChangeInTransaction(
        transactionId: Long,
        previous: Long?,
        current: Long?,
        alsoReplay: Set<Long> = emptySet()
    ) {
        val refundIds = db.transactionDao().getRefundIdsForOriginal(transactionId)
        val rows = rowsFor(listOf(transactionId) + refundIds)
        // [alsoReplay] is whoever was on the row before a save changed it. They can no longer be
        // read off the transaction, and their entries for it are already gone, so the caller has to
        // have captured them first.
        val friends = affectedFriends(rows.map { it.transaction }) + alsoReplay

        db.transactionDao().setChapter(transactionId, current)
        db.transactionDao().setChapterForRefundsOf(transactionId, current)

        if (current != null) {
            val members = db.chapterDao().memberIds(current).toSet()
            val missing = rows.flatMap { ChapterEligibility.missingMembers(it.transaction, it.shares, members) }
            addMembersInTransaction(current, missing.toSet(), System.currentTimeMillis())
        }

        if ((previous == null) != (current == null)) replayer.replay(friends)

        previous?.let { recomputeInTransaction(it) }
        current?.let { recomputeInTransaction(it) }
    }

    /** R4 and R8, enforced here and not only in the UI. Throws with the reason to show. */
    suspend fun assertTaggable(
        tx: Transaction,
        shares: List<com.varun.upitracker.database.entity.TransactionShare>,
        chapterId: Long
    ) {
        val chapter = db.chapterDao().getById(chapterId) ?: throw ChapterException("That chapter is gone")
        val original = tx.refundsTransactionId?.let { db.transactionDao().getTransactionById(it) }
        ChapterEligibility.blockedReason(tx, shares, chapter, original)?.let { throw ChapterException(it) }
    }

    /** R8: a transaction already inside a closed chapter cannot be edited or moved out of it. */
    suspend fun assertNotLockedByClosedChapter(chapterId: Long?) {
        val chapter = chapterId?.let { db.chapterDao().getById(it) } ?: return
        if (chapter.state == ChapterState.CLOSED) {
            throw ChapterException("In ${chapter.name} (closed). Reopen to edit.")
        }
    }

    suspend fun addMembersInTransaction(chapterId: Long, friendIds: Set<Long>, atEpoch: Long) {
        if (friendIds.isEmpty()) return
        db.chapterDao().addMembers(
            friendIds.map { ChapterMember(chapterId = chapterId, friendId = it, addedEpoch = atEpoch) }
        )
    }

    /**
     * Everyone whose base ledger a move has to rebuild: the friends the transactions name, plus any
     * friend still holding an entry against one of them.
     */
    suspend fun affectedFriends(transactions: List<Transaction>): Set<Long> {
        if (transactions.isEmpty()) return emptySet()
        val ids = transactions.map { it.id }
        val shares = db.transactionShareDao().getSharesForTransactions(ids).groupBy { it.transactionId }
        val friends = linkedSetOf<Long>()
        transactions.forEach { friends += ChapterMath.friendsIn(it, shares[it.id].orEmpty()) }
        friends += db.iouDao().friendIdsWithEntriesFor(ids)
        return friends
    }

    private suspend fun taggedRows(chapterId: Long): List<TaggedTx> {
        val transactions = db.chapterDao().taggedTransactions(chapterId)
        return attachShares(transactions)
    }

    private suspend fun rowsFor(transactionIds: List<Long>): List<TaggedTx> {
        if (transactionIds.isEmpty()) return emptyList()
        val transactions = transactionIds.mapNotNull { db.transactionDao().getTransactionById(it) }
        return attachShares(transactions)
    }

    private suspend fun attachShares(transactions: List<Transaction>): List<TaggedTx> {
        if (transactions.isEmpty()) return emptyList()
        val byTransaction = db.transactionShareDao()
            .getSharesForTransactions(transactions.map { it.id })
            .groupBy { it.transactionId }
        return transactions.map { TaggedTx(it, byTransaction[it.id].orEmpty()) }
    }

    private suspend fun requireChapter(chapterId: Long): Chapter =
        db.chapterDao().getById(chapterId) ?: throw ChapterException("That chapter is gone")
}

/**
 * [ChapterSync] backed by [ChapterRepository]. The default for every save.
 *
 * Constructs the repository from the database handed to it, the way the save path already
 * constructs [com.varun.upitracker.ledger.LedgerManager] inline -- `persist` takes its database as
 * an argument rather than holding one.
 */
object RepositoryChapterSync : ChapterSync {

    override suspend fun prepare(
        db: AppDatabase,
        existing: Transaction?,
        shares: List<com.varun.upitracker.database.entity.TransactionShare>,
        chapterId: Long?
    ): Set<Long> {
        val repository = ChapterRepository(db)
        // R8: a row already sitting in a closed chapter is frozen, whatever the save wants.
        repository.assertNotLockedByClosedChapter(existing?.chapterId)
        if (chapterId != null) {
            val subject = existing ?: return emptySet()
            repository.assertTaggable(subject, shares, chapterId)
        }
        return existing?.let { repository.affectedFriends(listOf(it)) } ?: emptySet()
    }

    override suspend fun afterPersist(
        db: AppDatabase,
        transactionId: Long,
        previous: Long?,
        current: Long?,
        friendsBefore: Set<Long>
    ) {
        if (previous == null && current == null) return
        ChapterRepository(db).applyChapterChangeInTransaction(
            transactionId = transactionId,
            previous = previous,
            current = current,
            alsoReplay = friendsBefore
        )
    }
}
