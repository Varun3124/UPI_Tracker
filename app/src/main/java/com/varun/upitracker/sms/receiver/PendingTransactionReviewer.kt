package com.varun.upitracker.sms.receiver

import android.content.Context
import com.varun.upitracker.data.declaration.CheckpointStore
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.dao.clearDraft
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.MerchantCategory
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterOption
import com.varun.upitracker.domain.chapter.ChapterPrompt
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.category.MerchantCategoryHistory
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ledger.SealingLedgerPort
import com.varun.upitracker.ui.ActorType
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

    /**
     * Whether confirming [tx] from its notification decides nothing that is the user's to decide.
     * The notification only offers its button when this holds, so the button never quietly does
     * nothing.
     */
    suspend fun canReview(db: AppDatabase, tx: Transaction): Boolean {
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
        return !ChapterPrompt.needsEntryScreen(tx, shares, chapterOptions, originalOfRefund)
    }

    /**
     * The category [tx]'s merchant has always been filed under in this direction, when there has
     * only ever been one, and [tx] is shaped so that one category can take all of it.
     */
    suspend fun suggestedCategory(db: AppDatabase, tx: Transaction): Category? {
        val (merchantId, kind) = MerchantCategoryHistory.merchantSide(
            tx.payerActorType, tx.payeeActorType, tx.payerMerchantId, tx.payeeMerchantId
        ) ?: return null
        val categoryId = withContext(Dispatchers.IO) {
            MerchantCategoryHistory.singleCategories(db.categorySplitDao().getMerchantCategoryUses(merchantId))
        }[merchantId to kind] ?: return null
        val category = withContext(Dispatchers.IO) { db.categoryDao().getCategoryById(categoryId) } ?: return null
        return category.takeIf { takesWholeAmount(tx, emptyList(), it) }
    }

    /**
     * True once [transactionId] is confirmed. With [categoryId], it is filed under that category
     * too -- the notification's "Save as ..." -- or not confirmed at all if it no longer fits.
     */
    suspend fun review(context: Context, transactionId: Long, categoryId: Long? = null): Boolean {
        val db = AppDatabase.getInstance(context)
        val tx = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(transactionId) } ?: return false
        if (!tx.isPending) return true
        if (!canReview(db, tx)) return false

        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        val category = categoryId?.let { id ->
            withContext(Dispatchers.IO) { db.categoryDao().getCategoryById(id) }
                ?.takeIf { takesWholeAmount(tx, shares, it) }
                ?: return false
        }

        return withContext(Dispatchers.IO) {
            db.runInTransaction<Boolean> {
                runBlocking {
                    // Written down, not just used: a row still missing one would otherwise look to
                    // IouRecoveryBackfill like one whose entries the old inference posted, and get
                    // corrected a second time.
                    val iouRecovery = IouLegs.resolve(tx, shares)
                    val updated = tx.copy(isPending = false, iouRecovery = iouRecovery)
                    db.transactionDao().update(updated)
                    // Whatever was pended for it is moot once it is confirmed as it stands.
                    db.entryDraftDao().clearDraft(tx.id)
                    if (category != null) fileUnder(db, updated, category)
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

    /**
     * One category takes the whole amount only when ME is one end, a merchant the other, nothing is
     * split and it is not a refund -- then ME's share is the amount, which is what the entry screen
     * would ask to be categorised. And only in the direction this transaction categorises in.
     */
    private fun takesWholeAmount(tx: Transaction, shares: List<TransactionShare>, category: Category): Boolean {
        if (shares.isNotEmpty() || tx.refundsTransactionId != null || tx.amountPaise <= 0L) return false
        if (tx.payerActorType != ActorType.ME && tx.payeeActorType != ActorType.ME) return false
        val (_, kind) = MerchantCategoryHistory.merchantSide(
            tx.payerActorType, tx.payeeActorType, tx.payerMerchantId, tx.payeeMerchantId
        ) ?: return false
        return kind == category.kind
    }

    /** What the entry screen's save writes for a single checked category, as `persistCategories` does. */
    private suspend fun fileUnder(db: AppDatabase, tx: Transaction, category: Category) {
        db.categorySplitDao().deleteForTransaction(tx.id)
        db.categorySplitDao().insert(
            TransactionCategorySplit(
                transactionId = tx.id,
                categoryId = category.id,
                myAmountPaise = tx.amountPaise,
                partyAmountPaise = 0L
            )
        )
        val merchantId = tx.payeeMerchantId.takeIf { tx.payeeActorType == ActorType.MERCHANT }
            ?: tx.payerMerchantId.takeIf { tx.payerActorType == ActorType.MERCHANT }
        // Only a suggestion cache for next time; nothing that affects money reads it.
        merchantId?.let { db.categoryDao().linkMerchantCategory(MerchantCategory(merchantId = it, categoryId = category.id)) }
    }

    private suspend fun chapterOptions(db: AppDatabase): List<ChapterOption> {
        val repository = ChapterRepository(db)
        return db.chapterDao().getOpen().map { chapter ->
            ChapterOption(
                chapter = chapter,
                memberIds = db.chapterDao().memberIds(chapter.id).toSet(),
                // A friend's chapter is never offered (S10), so whether it is settled is never asked.
                settled = !chapter.isOwn || repository.resultFor(chapter.id).settled
            )
        }
    }
}
