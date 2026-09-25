package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare

/**
 * What saving a transaction has to do about the chapter it is in.
 *
 * Injected into [TransactionPersistenceService] rather than written into it, for the reason the
 * ledger port exists: the save path is already eight lambdas deep, and chapters should be one
 * collaborator rather than five more arguments.
 *
 * Every method runs inside the caller's already-open database transaction, so a throw from
 * [prepare] rolls back nothing (there is nothing yet to roll back) and a failure later rolls back
 * the save, the replay and the recompute together. Nothing here may switch dispatcher.
 */
interface ChapterSync {

    /**
     * Checks the save is allowed and returns the friends whose base ledger a move would rebuild.
     *
     * Called before anything is written, which is the point: the friend set has to be read while
     * the old row and its `iou_entries` are still there, because an edit that drops someone from a
     * split would otherwise leave nobody to replay them from.
     *
     * Throws with a message fit to show the user when the transaction cannot go where it is headed.
     */
    suspend fun prepare(
        db: AppDatabase,
        existing: Transaction?,
        shares: List<TransactionShare>,
        chapterId: Long?
    ): Set<Long>

    /**
     * Called once the row and its shares are written, with what [prepare] returned.
     *
     * [wasExisting] is false for a row being created. A brand-new transaction has never been in the
     * base ledger, so tagging it cannot have taken anything out of one -- there is nothing to
     * rebuild, and rebuilding anyway would re-settle unrelated entries for no reason.
     */
    suspend fun afterPersist(
        db: AppDatabase,
        transactionId: Long,
        previous: Long?,
        current: Long?,
        friendsBefore: Set<Long>,
        wasExisting: Boolean
    )

    /** For tests and for any caller that has no business with chapters. */
    object None : ChapterSync {
        override suspend fun prepare(
            db: AppDatabase,
            existing: Transaction?,
            shares: List<TransactionShare>,
            chapterId: Long?
        ): Set<Long> = emptySet()

        override suspend fun afterPersist(
            db: AppDatabase,
            transactionId: Long,
            previous: Long?,
            current: Long?,
            friendsBefore: Set<Long>,
            wasExisting: Boolean
        ) = Unit
    }
}
