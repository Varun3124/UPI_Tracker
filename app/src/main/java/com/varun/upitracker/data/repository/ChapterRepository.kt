package com.varun.upitracker.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.data.declaration.CheckpointStore
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
import com.varun.upitracker.domain.declaration.Absorption
import com.varun.upitracker.domain.transactionentry.persistence.ChapterSync
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ledger.LedgerReplayer
import com.varun.upitracker.ledger.RoomReplaySource

private const val TAG = "ChapterRepository"

/** A chapter refused an operation, with a message fit to put in front of the user. */
class ChapterException(message: String) : Exception(message)

/** What [ChapterRepository.tagAll] did with a batch of transactions. */
data class BulkTagResult(
    /** How many of the batch now sit in the chapter, refunds carried along with their purchase included. */
    val taggedCount: Int,
    /** Friends the chapter had to take in to hold them (R5). */
    val addedMemberIds: Set<Long>,
    /** One reason per transaction that was refused, in the order they were tried. */
    val failures: List<String>
)

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
     * rows the base ledger should hold -- and that is decided by `chapterId`. So does the delete: a
     * checkpoint that counted this chapter's share loses that part with it, and the replay is what
     * works the opening out again without it (docs/declarations-design.md section 8).
     */
    suspend fun delete(chapterId: Long) = db.withTransaction {
        val tagged = db.chapterDao().taggedTransactions(chapterId)
        val friends = affectedFriends(tagged) + db.declarationDao().friendIdsCountingChapter(chapterId)
        db.transactionDao().untagChapter(chapterId)
        db.chapterDao().deleteById(chapterId)
        replayer.replay(friends)
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

    /**
     * [setChapterFor] over a batch, each in its own database transaction, collecting what was refused
     * rather than stopping at the first -- a selection off the transactions list mixes rows a chapter
     * can take with ones it cannot, and the user needs to hear about all of them.
     *
     * A refund whose purchase is also in the batch is not tried on its own. Tagging the purchase
     * carries it along, and trying it first would report "follows its original purchase" for a row
     * that ends up exactly where it was asked to go.
     */
    suspend fun tagAll(chapterId: Long, transactionIds: Set<Long>): BulkTagResult {
        val membersBefore = db.chapterDao().memberIds(chapterId).toSet()
        val carried = transactionIds.filter { id ->
            val original = db.transactionDao().getTransactionById(id)?.refundsTransactionId
            original != null && original in transactionIds
        }.toSet()

        val failures = mutableListOf<String>()
        (transactionIds - carried).forEach { id ->
            try {
                setChapterFor(id, chapterId)
            } catch (e: ChapterException) {
                failures += e.message ?: "That did not work"
            }
        }

        // Counted from the rows rather than from the attempts, so a carried refund is counted when
        // its purchase went in and not when it did not.
        val tagged = transactionIds.count { db.transactionDao().getTransactionById(it)?.chapterId == chapterId }
        return BulkTagResult(
            taggedCount = tagged,
            addedMemberIds = db.chapterDao().memberIds(chapterId).toSet() - membersBefore,
            failures = failures
        )
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
     * The whole of what moving or saving a transaction involves for the books, in the order it has to
     * happen.
     *
     * Refunds go first because the replay reads their `chapterId` too (R6). The chapters it leaves
     * and joins are read before anything moves, so a change caused only by rows a checkpoint already
     * covers can be absorbed into it (D9) -- and those friends' base ledgers replayed after, so the
     * opening follows. The rest of the replay runs when [replay] says the base ledger changed: a row
     * crossed between it and a chapter, or an edit rewrote one it holds. Moving between two
     * chapters, or creating a row straight into one, never touched `iou_entries`.
     *
     * [previousDateEpoch] is the row's date before a save changed it, if it did: both versions have to
     * be covered by a checkpoint for the change to be absorbed.
     */
    suspend fun applyChapterChangeInTransaction(
        transactionId: Long,
        previous: Long?,
        current: Long?,
        alsoReplay: Set<Long> = emptySet(),
        replay: Boolean = (previous == null) != (current == null),
        previousDateEpoch: Long? = null
    ) {
        val refundIds = db.transactionDao().getRefundIdsForOriginal(transactionId)
        val rows = rowsFor(listOf(transactionId) + refundIds)
        // [alsoReplay] is whoever was on the row before a save changed it. They can no longer be
        // read off the transaction, and their entries for it are already gone, so the caller has to
        // have captured them first.
        val friends = affectedFriends(rows.map { it.transaction }) + alsoReplay

        val touched = setOfNotNull(previous, current)
        val before = contributionsOf(touched)

        db.transactionDao().setChapter(transactionId, current)
        db.transactionDao().setChapterForRefundsOf(transactionId, current)

        if (current != null) {
            val members = db.chapterDao().memberIds(current).toSet()
            val missing = rows.flatMap { ChapterEligibility.missingMembers(it.transaction, it.shares, members) }
            addMembersInTransaction(current, missing.toSet(), System.currentTimeMillis())
        }

        val versionDates = rows.map { it.transaction.dateEpoch } + listOfNotNull(previousDateEpoch)
        val absorbed = recomputeAbsorbingInTransaction(touched, before, versionDates)

        replayer.replay((if (replay) friends else emptySet()) + absorbed)
    }

    /**
     * What each of [chapterIds] contributes to each friend right now, straight from
     * `chapter_balances`. Taken before a change, so [recomputeAbsorbingInTransaction] can tell what
     * the change did.
     */
    suspend fun contributionsOf(chapterIds: Set<Long>): Map<Long, Map<Long, Long>> =
        chapterIds.associateWith { chapterId ->
            db.chapterDao().balancesForChapter(chapterId).associate { it.friendId to it.amountPaise }
        }

    /**
     * Recomputes [chapterIds], then applies D9: for every friend whose checkpoint covers all of
     * [versionDates], what the change did to their contribution is counted into that chapter's part
     * of the checkpoint instead of moving their balance.
     *
     * Returns the friends whose parts moved. Their base ledger has to be replayed so the opening
     * follows -- the caller does that, once, along with whoever else it is rebuilding.
     */
    suspend fun recomputeAbsorbingInTransaction(
        chapterIds: Set<Long>,
        before: Map<Long, Map<Long, Long>>,
        versionDates: Collection<Long>
    ): Set<Long> {
        chapterIds.forEach { recomputeInTransaction(it) }
        if (chapterIds.isEmpty() || versionDates.isEmpty()) return emptySet()

        val after = contributionsOf(chapterIds)
        val friends = (before.values.flatMap { it.keys } + after.values.flatMap { it.keys }).toSet()
        if (friends.isEmpty()) return emptySet()

        val checkpoints = CheckpointStore(db)
        val absorbing = Absorption.absorbingFriends(versionDates, checkpoints.asOfByFriend(friends))
        if (absorbing.isEmpty()) return emptySet()

        val changed = linkedSetOf<Long>()
        chapterIds.forEach { chapterId ->
            Absorption.deltas(before[chapterId].orEmpty(), after[chapterId].orEmpty(), absorbing)
                .forEach { (friendId, delta) ->
                    if (checkpoints.absorb(friendId, chapterId, shareIdOf(chapterId), delta)) changed += friendId
                }
        }
        return changed
    }

    /** Rebuilds these friends' base ledgers, inside the caller's transaction. */
    suspend fun replayInTransaction(friendIds: Set<Long>) = replayer.replay(friendIds)

    /** The id a chapter is known by on other phones once it is shared; none yet, as nothing is shared. */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun shareIdOf(chapterId: Long): String? = null

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
        friendsBefore: Set<Long>,
        wasExisting: Boolean,
        previousDateEpoch: Long?
    ) {
        // A brand-new row outside every chapter was posted as it was saved: nothing to rebuild.
        if (!wasExisting && previous == null && current == null) return
        ChapterRepository(db).applyChapterChangeInTransaction(
            transactionId = transactionId,
            previous = previous,
            current = current,
            alsoReplay = friendsBefore,
            // An edit of a row the base ledger holds, before or after, rebuilds it: deleting one
            // row's entries and posting it again cannot undo what it settled against older ones. A
            // row created straight into a chapter, or moved between two, was never in it.
            replay = wasExisting && (previous == null || current == null),
            previousDateEpoch = previousDateEpoch
        )
    }
}
