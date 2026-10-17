package com.varun.upitracker.data.repository

import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.ReceivedOriginal
import com.varun.upitracker.database.entity.ReceivedOriginalShare
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.parcel.ReceivedOriginalDiff
import com.varun.upitracker.domain.parcel.overlayOn
import com.varun.upitracker.domain.parcel.toShare
import com.varun.upitracker.ui.ActorType

/** A received row as it arrived, with whether the row has since been changed from it. */
data class ReceivedOriginalView(
    val original: ReceivedOriginal,
    val shares: List<ReceivedOriginalShare>,
    val differs: Boolean
)

class RevertException(message: String) : Exception(message)

/**
 * The untouchable copy of each received transaction, and putting a row back to it.
 *
 * The receiver can change anything about a row a friend sent; this is the failsafe that makes that
 * safe. It is written once, at import, and never again. Reverting copies it back over the row and
 * leaves the row pending, exactly as it first landed, for the receiver to confirm again.
 */
class ReceivedOriginalRepository(private val db: AppDatabase) {

    /**
     * Keeps [tx] and [shares], just inserted as [transactionId], as what arrived. Inside the
     * importer's database transaction, so a row is never saved without its original.
     *
     * Names are written beside ids, so a friend or shop deleted later still reads by name here.
     */
    suspend fun captureInTransaction(
        transactionId: Long,
        tx: Transaction,
        shares: List<TransactionShare>,
        senderName: String
    ) {
        val inserted = db.receivedOriginalDao().insert(
            ReceivedOriginal(
                transactionId = transactionId,
                senderName = senderName,
                receivedEpoch = System.currentTimeMillis(),
                amountPaise = tx.amountPaise,
                dateEpoch = tx.dateEpoch,
                payerActorType = tx.payerActorType,
                payerFriendId = tx.payerFriendId,
                payerMerchantId = tx.payerMerchantId,
                payerRawLabel = labelOf(tx.payerActorType, tx.payerFriendId, tx.payerMerchantId, tx.payerRawLabel),
                payeeActorType = tx.payeeActorType,
                payeeFriendId = tx.payeeFriendId,
                payeeMerchantId = tx.payeeMerchantId,
                payeeRawLabel = labelOf(tx.payeeActorType, tx.payeeFriendId, tx.payeeMerchantId, tx.payeeRawLabel),
                reason = tx.reason,
                ledgerEffect = tx.ledgerEffect,
                iouRecovery = tx.iouRecovery
            )
        )
        if (inserted == -1L) return // Already kept: an original is never replaced.
        db.receivedOriginalDao().insertShares(
            shares.map { share ->
                ReceivedOriginalShare(
                    transactionId = transactionId,
                    side = share.side,
                    participantType = share.participantType,
                    friendId = share.friendId,
                    rawLabel = share.rawLabel ?: share.friendId?.let { db.friendDao().getFriendById(it)?.name },
                    amountPaise = share.amountPaise,
                    keepPayeeLeg = share.keepPayeeLeg,
                    keepPayerLeg = share.keepPayerLeg
                )
            }
        )
    }

    /** Null for a row that never came from a friend, or came before originals were kept. */
    suspend fun load(transactionId: Long): ReceivedOriginalView? {
        val original = db.receivedOriginalDao().get(transactionId) ?: return null
        val tx = db.transactionDao().getTransactionById(transactionId) ?: return null
        val shares = db.receivedOriginalDao().getShares(transactionId)
        val merchants = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }
        val differs = ReceivedOriginalDiff.differs(
            original = original,
            originalShares = shares,
            tx = tx,
            shares = db.transactionShareDao().getSharesForTransaction(transactionId),
            merchantName = { merchants[it] }
        )
        return ReceivedOriginalView(original, shares, differs)
    }

    /**
     * Puts [transactionId] back as it arrived, pending. Returns the old chapter, which the caller
     * republishes when it is shared -- see [com.varun.upitracker.maintenance.ChapterPublishing].
     *
     * Built like [TransactionRemoval]: who to rebuild is read while the edited row is still there,
     * since the row going back to pending takes it out of the ledger and out of any chapter.
     */
    suspend fun revert(transactionId: Long): Long? = db.withTransaction {
        val tx = db.transactionDao().getTransactionById(transactionId)
            ?: throw RevertException("That transaction is no longer here.")
        val original = db.receivedOriginalDao().get(transactionId)
            ?: throw RevertException("There is no original to go back to.")

        val refunds = db.transactionDao().getRefundIdsForOriginal(transactionId).size
        if (refunds > 0) {
            throw RevertException(
                if (refunds == 1) "A refund is linked to this transaction. Delete or unlink the refund first."
                else "$refunds refunds are linked to this transaction. Delete or unlink them first."
            )
        }
        tx.chapterId?.let { db.chapterDao().getById(it) }
            ?.takeIf { it.isOwn && it.state == ChapterState.CLOSED }
            ?.let { throw RevertException("In ${it.name} (closed). Reopen it to change this transaction.") }

        val chapters = ChapterRepository(db)
        val friends = if (tx.chapterId == null) chapters.affectedFriends(listOf(tx)) else emptySet()
        val chapterIds = setOfNotNull(tx.chapterId)
        val before = chapters.contributionsOf(chapterIds)

        db.transactionDao().update(original.overlayOn(tx))
        db.categorySplitDao().deleteForTransaction(transactionId)
        db.iouDao().deleteForTransaction(transactionId)
        db.transactionShareDao().deleteForTransaction(transactionId)
        val shares = db.receivedOriginalDao().getShares(transactionId).map { it.toShare() }
        if (shares.isNotEmpty()) db.transactionShareDao().insertAll(shares)

        val absorbed = chapters.recomputeAbsorbingInTransaction(chapterIds, before, listOf(tx.dateEpoch, original.dateEpoch))
        chapters.replayInTransaction(friends + absorbed)
        tx.chapterId
    }

    private suspend fun labelOf(actorType: String, friendId: Long?, merchantId: Long?, rawLabel: String?): String? =
        rawLabel ?: when (actorType) {
            ActorType.FRIEND -> friendId?.let { db.friendDao().getFriendById(it)?.name }
            ActorType.MERCHANT -> merchantId?.let { db.merchantDao().getMerchantById(it)?.name }
            else -> null
        }
}
