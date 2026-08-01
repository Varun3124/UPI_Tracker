package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorRef
import kotlinx.coroutines.runBlocking

data class PersistTransactionRequest(
    val existingTransaction: Transaction?,
    val amountPaise: Long,
    val selectedAccountId: String?,
    val dateEpoch: Long
)

class TransactionPersistenceService(
    private val ledgerPostingService: LedgerPostingService = LedgerPostingService()
) {

    suspend fun persist(
        db: AppDatabase,
        request: PersistTransactionRequest,
        resolveActors: suspend () -> Pair<ActorRef, ActorRef>,
        resolveUnresolvedShareRows: suspend () -> Unit,
        buildSharesForPersistence: (txId: Long) -> List<TransactionShare>,
        mySharePaiseFromShares: (shares: List<TransactionShare>) -> Long,
        persistCategories: suspend (transactionId: Long, meSharePaise: Long) -> Unit
    ): Long {
        var persistedTransactionId = 0L
        val tx = request.existingTransaction

        db.runInTransaction {
            runBlocking {
                val (payer, payee) = resolveActors()
                resolveUnresolvedShareRows()
                val shares = buildSharesForPersistence(tx?.id ?: 0L)
                val meSharePaise = mySharePaiseFromShares(shares)

                val base = buildTransactionEntity(tx, request, payer, payee)
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

                persistCategories(transactionId, meSharePaise)
                ledgerPostingService.postLedger(db, transactionId, payer, payee, persistedShares, request.amountPaise)
                persistedTransactionId = transactionId
            }
        }

        return persistedTransactionId
    }

    private fun buildTransactionEntity(
        tx: Transaction?,
        request: PersistTransactionRequest,
        payer: ActorRef,
        payee: ActorRef
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
            isPending = false
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
            myAccountId = request.selectedAccountId,
            dateEpoch = request.dateEpoch,
            isPending = false
        )
    }
}
