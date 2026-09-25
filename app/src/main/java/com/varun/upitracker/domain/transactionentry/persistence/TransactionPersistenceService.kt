package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.data.repository.RepositoryChapterSync
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.ActorRef
import kotlinx.coroutines.runBlocking

data class PersistTransactionRequest(
    val existingTransaction: Transaction?,
    val amountPaise: Long,
    val selectedAccountId: String?,
    val dateEpoch: Long,
    val description: String? = null,
    val refundsTransactionId: Long? = null,

    /** The chapter this transaction should end up in, or null for the base ledger. */
    val chapterId: Long? = null
)

class TransactionPersistenceService(
    private val ledgerPostingService: LedgerPostingService = LedgerPostingService(),
    // Defaults to the real one so every existing call site gets chapter handling without opting in:
    // a save path that quietly skipped it would leave a chapter's balances stale.
    private val chapterSync: ChapterSync = RepositoryChapterSync
) {

    /**
     * [chooseIouRecovery] is only asked once the actors and share rows are resolved: a name typed into
     * a split but not yet committed only becomes a share there, and whether anyone else is in the split
     * is what the choice turns on. A transaction a friend sent keeps the technique it arrived with --
     * changing it here would leave the two copies disagreeing about who owes whom.
     *
     * The ledger effect is read off the share rows rather than asked for: with every IOU line left
     * out, the transaction moves nobody's balance. See [IouLegs.ledgerEffect].
     */
    suspend fun persist(
        db: AppDatabase,
        request: PersistTransactionRequest,
        resolveActors: suspend () -> Pair<ActorRef, ActorRef>,
        resolveUnresolvedShareRows: suspend () -> Unit,
        buildSharesForPersistence: (txId: Long) -> List<TransactionShare>,
        categoryAmountPaise: (shares: List<TransactionShare>) -> Long,
        chooseIouRecovery: (payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>) -> IouRecovery,
        persistCategories: suspend (transactionId: Long, categoryAmountPaise: Long, payer: ActorRef, payee: ActorRef) -> Unit
    ): Long {
        var persistedTransactionId = 0L
        val tx = request.existingTransaction

        db.runInTransaction {
            runBlocking {
                val (payer, payee) = resolveActors()
                resolveUnresolvedShareRows()
                val shares = buildSharesForPersistence(tx?.id ?: 0L)
                val toCategorisePaise = categoryAmountPaise(shares)
                val iouRecovery = IouLegs.effectiveRecovery(
                    payer,
                    payee,
                    tx?.takeIf { it.sharedRefId != null }?.iouRecovery ?: chooseIouRecovery(payer, payee, shares)
                )
                val ledgerEffect = IouLegs.ledgerEffect(payer, payee, shares, request.amountPaise, iouRecovery)

                // Before any write, so the refusal costs nothing and so the friends who are about to
                // be replayed can still be read off the row as it stands.
                val previousChapterId = tx?.chapterId
                val chapterId = request.chapterId
                val friendsBefore = chapterSync.prepare(db, tx, shares, chapterId)

                val base = buildTransactionEntity(tx, request, payer, payee, ledgerEffect, iouRecovery)
                val transactionId = if (tx == null) {
                    db.transactionDao().insert(base)
                } else {
                    db.transactionDao().update(base)
                    tx.id
                }

                db.iouDao().deleteForTransaction(transactionId)
                db.categorySplitDao().deleteForTransaction(transactionId)
                db.transactionShareDao().deleteForTransaction(transactionId)

                val persistedShares = shares.map { it.copy(transactionId = transactionId) }
                if (persistedShares.isNotEmpty()) db.transactionShareDao().insertAll(persistedShares)

                persistCategories(transactionId, toCategorisePaise, payer, payee)

                // A tagged transaction posts nothing here: its effect reaches a friend's balance
                // through `chapter_balances` instead (R15). When it has just crossed between the two
                // books, the replay inside `afterPersist` reposts it along with everything else the
                // base ledger holds for those friends, so posting it again here would double it.
                if (chapterId == null && previousChapterId == null) {
                    ledgerPostingService.postLedger(
                        LedgerManager(db), transactionId, payer, payee, persistedShares,
                        request.amountPaise, ledgerEffect, iouRecovery
                    )
                }
                chapterSync.afterPersist(db, transactionId, previousChapterId, chapterId, friendsBefore)
                persistedTransactionId = transactionId
            }
        }

        return persistedTransactionId
    }

    private fun buildTransactionEntity(
        tx: Transaction?,
        request: PersistTransactionRequest,
        payer: ActorRef,
        payee: ActorRef,
        ledgerEffect: LedgerEffect,
        iouRecovery: IouRecovery
    ): Transaction {
        return (tx ?: Transaction(
            amountPaise = request.amountPaise,
            payerActorType = payer.actorType,
            payerFriendId = payer.friendId,
            payerMerchantId = payer.merchantId,
            payerRawLabel = payer.rawLabel,
            payeeActorType = payee.actorType,
            payeeFriendId = payee.friendId,
            payeeMerchantId = payee.merchantId,
            payeeRawLabel = payee.rawLabel,
            myAccountId = request.selectedAccountId,
            dateEpoch = request.dateEpoch,
            source = "MANUAL",
            isPending = false,
            refundsTransactionId = request.refundsTransactionId,
            ledgerEffect = ledgerEffect,
            iouRecovery = iouRecovery,
            chapterId = request.chapterId
        )).copy(
            amountPaise = request.amountPaise,
            payerActorType = payer.actorType,
            payerFriendId = payer.friendId,
            payerMerchantId = payer.merchantId,
            payerRawLabel = payer.rawLabel,
            payeeActorType = payee.actorType,
            payeeFriendId = payee.friendId,
            payeeMerchantId = payee.merchantId,
            payeeRawLabel = payee.rawLabel,
            reason = request.description,
            myAccountId = request.selectedAccountId,
            dateEpoch = request.dateEpoch,
            isPending = false,
            // Must be repeated here, not just in the constructor above: on an edit `tx` is
            // non-null and only the fields named in this copy survive.
            refundsTransactionId = request.refundsTransactionId,
            ledgerEffect = ledgerEffect,
            iouRecovery = iouRecovery,
            // Named here too, for the reason above it: on an edit `tx` is non-null and a chapter
            // change would otherwise be silently dropped.
            chapterId = request.chapterId
        )
    }
}
