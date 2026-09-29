package com.varun.upitracker.data.repository

import com.varun.upitracker.database.AppDatabase

/**
 * Deleting a transaction, and putting right everything the books built on it. The one way it is
 * done, for the list screens and for turning a transaction into an account transfer.
 *
 * Deleting the row takes its own entries and splits with it (CASCADE), but not what it did to other
 * entries: a repayment that settled an older debt leaves that debt flagged settled, and the balance
 * would carry on as if the repayment had happened. So the friends it named are replayed. A row in a
 * chapter owns no entries, but its chapter has to work its plan out again -- and if the row was
 * dated before someone's checkpoint, that change is absorbed rather than moving their balance
 * (docs/declarations-design.md D9).
 */
class TransactionRemoval(private val db: AppDatabase) {

    /**
     * Deletes [transactionId] inside the caller's database transaction. Nothing here opens one or
     * switches dispatcher, for the reason given on [ChapterRepository].
     *
     * Refunds pointing at it have to be dealt with first -- `refundsTransactionId` is RESTRICT -- so
     * callers check for them and say so before calling this.
     */
    suspend fun removeInTransaction(transactionId: Long) {
        val tx = db.transactionDao().getTransactionById(transactionId) ?: return
        val chapters = ChapterRepository(db)

        // Read while the row and its entries are still here: they are how we know who to rebuild.
        val friends = if (tx.chapterId == null) chapters.affectedFriends(listOf(tx)) else emptySet()
        val chapterIds = setOfNotNull(tx.chapterId)
        val before = chapters.contributionsOf(chapterIds)

        db.transactionShareDao().deleteForTransaction(transactionId)
        db.transactionDao().deleteById(transactionId)

        val absorbed = chapters.recomputeAbsorbingInTransaction(chapterIds, before, listOf(tx.dateEpoch))
        chapters.replayInTransaction(friends + absorbed)
    }
}
