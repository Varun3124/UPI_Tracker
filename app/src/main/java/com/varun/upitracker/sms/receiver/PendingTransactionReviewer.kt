package com.varun.upitracker.sms.receiver

import android.content.Context
import com.varun.upitracker.data.declaration.CheckpointStore
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterOption
import com.varun.upitracker.domain.chapter.ChapterPrompt
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ledger.SealingLedgerPort
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.payeeActorRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object PendingTransactionReviewer {

    /**
     * Shared with the manual entry screen on purpose. This file used to carry its own copy, which
     * had drifted: it settled a FRIEND -> ME transaction even when it carried shares, and never
     * reached the payee-side legs at all.
     */
    private val ledgerPostingService = LedgerPostingService()

    suspend fun review(context: Context, transactionId: Long): Boolean {
        val db = AppDatabase.getInstance(context)
        val tx = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(transactionId) } ?: return false
        if (!tx.isPending) return true
        if (!PendingReviewRules.canAutoReview(tx)) return false

        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        if (!PendingReviewRules.sharesAreValid(tx, shares)) return false

        // Confirming from a notification posts straight to the ledger and never shows a chapter. So
        // anything a chapter would be chosen for has to go to the entry screen, where the user can
        // see the choice being made for them and say otherwise.
        val chapterOptions = withContext(Dispatchers.IO) { chapterOptions(db) }
        val originalOfRefund = withContext(Dispatchers.IO) {
            tx.refundsTransactionId?.let { db.transactionDao().getTransactionById(it) }
        }
        if (ChapterPrompt.needsEntryScreen(tx, shares, chapterOptions, originalOfRefund)) return false

        return withContext(Dispatchers.IO) {
            db.runInTransaction<Boolean> {
                runBlocking {
                    // Written down, not just used: a row still missing one would otherwise look to
                    // IouRecoveryBackfill like one whose entries the old inference posted, and get
                    // corrected a second time.
                    val iouRecovery = IouLegs.resolve(tx, shares)
                    val updated = tx.copy(isPending = false, iouRecovery = iouRecovery)
                    db.transactionDao().update(updated)
                    val chapters = ChapterRepository(db)
                    if (db.iouDao().friendIdsWithEntriesFor(listOf(tx.id)).isNotEmpty()) {
                        // A backfill flipped this row back to pending without clearing what it posted
                        // the first time. Posting over that would count it twice, and deleting only
                        // its own entries would leave whatever it settled flagged as settled.
                        chapters.replayInTransaction(chapters.affectedFriends(listOf(updated)))
                    } else {
                        // Friends whose checkpoint already covers its date get nothing (D8).
                        val sealed = CheckpointStore(db).sealedFriends(ChapterMath.friendsIn(updated, shares), tx.dateEpoch)
                        ledgerPostingService.postLedger(
                            SealingLedgerPort(LedgerManager(db), sealed), tx.id, tx.payerActorRef(), tx.payeeActorRef(),
                            shares, tx.amountPaise, tx.ledgerEffect, iouRecovery
                        )
                    }
                    true
                }
            }
        }
    }

    private suspend fun chapterOptions(db: AppDatabase): List<ChapterOption> {
        val repository = ChapterRepository(db)
        return db.chapterDao().getOpen().map { chapter ->
            ChapterOption(
                chapter = chapter,
                memberIds = db.chapterDao().memberIds(chapter.id).toSet(),
                settled = repository.resultFor(chapter.id).settled
            )
        }
    }
}
