package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType

/** A parcel row turned back into rows this database can hold. */
data class LocalParcelRow(
    val transaction: Transaction,
    val shares: List<TransactionShare>
)

/**
 * Turns a transaction around so the other party can read it, and back again.
 *
 * The whole feature rests on one swap: the writer's ME becomes SENDER, and the recipient becomes
 * ME. Everything else -- amounts, dates, sides, which side the shares sit on -- is left exactly as
 * it was, because a bill split does not change shape depending on who is reading it.
 *
 * Names are passed in as lambdas rather than looked up, the same seam
 * [com.varun.upitracker.ledger.LedgerPort] uses, so this stays a pure function and stays under test.
 */
object ParcelPerspective {

    const val SOURCE_SHARED_PARCEL = "SHARED_PARCEL"

    /**
     * Rewrites one of the writer's transactions into the form [recipientFriendId] should read.
     *
     * Dropped on the way out, all of them meaningless to anyone else: the row id, `myAccountId`
     * (the writer's bank account), `statementRefNo`, `refundsTransactionId` (it points at a row
     * only the writer holds), `isPending` and `source`.
     */
    fun flipForRecipient(
        transaction: Transaction,
        shares: List<TransactionShare>,
        recipientFriendId: Long,
        friendName: (Long) -> String?,
        merchantName: (Long) -> String?
    ): ParcelTransaction {
        val payer = flipActor(
            actorType = transaction.payerActorType,
            friendId = transaction.payerFriendId,
            merchantId = transaction.payerMerchantId,
            rawLabel = transaction.payerRawLabel,
            recipientFriendId = recipientFriendId,
            friendName = friendName,
            merchantName = merchantName
        )
        val payee = flipActor(
            actorType = transaction.payeeActorType,
            friendId = transaction.payeeFriendId,
            merchantId = transaction.payeeMerchantId,
            rawLabel = transaction.payeeRawLabel,
            recipientFriendId = recipientFriendId,
            friendName = friendName,
            merchantName = merchantName
        )

        return ParcelTransaction(
            sourceId = transaction.id,
            dateEpoch = transaction.dateEpoch,
            amountPaise = transaction.amountPaise,
            payer = payer,
            payee = payee,
            ledgerEffect = transaction.ledgerEffect,
            upiRefId = transaction.upiRefId?.takeIf { isDirectTransfer(transaction, recipientFriendId) },
            reason = transaction.reason,
            shares = shares.mapNotNull { share -> flipShare(share, recipientFriendId, friendName) }
        )
    }

    /**
     * Both banks issue the same reference for a payment between two people, which makes it the one
     * genuinely shared identifier in the row -- and so the strongest way for the reader to spot
     * that their own SMS already recorded this. On a merchant payment it is the writer's private
     * reference, matches nothing the reader holds, and is dropped.
     */
    private fun isDirectTransfer(transaction: Transaction, recipientFriendId: Long): Boolean {
        val meToThem = transaction.payerActorType == ActorType.ME &&
            transaction.payeeActorType == ActorType.FRIEND &&
            transaction.payeeFriendId == recipientFriendId
        val themToMe = transaction.payeeActorType == ActorType.ME &&
            transaction.payerActorType == ActorType.FRIEND &&
            transaction.payerFriendId == recipientFriendId
        return meToThem || themToMe
    }

    private fun flipActor(
        actorType: String,
        friendId: Long?,
        merchantId: Long?,
        rawLabel: String?,
        recipientFriendId: Long,
        friendName: (Long) -> String?,
        merchantName: (Long) -> String?
    ): ParcelActor = when (actorType) {
        ActorType.ME -> ParcelActor.Sender
        ActorType.FRIEND ->
            if (friendId == recipientFriendId) {
                ParcelActor.Me
            } else {
                // A friend of the writer's the reader may well not know. Named, never identified.
                ParcelActor.Person(friendId?.let(friendName) ?: rawLabel ?: "Someone")
            }
        ActorType.MERCHANT -> ParcelActor.Shop(merchantId?.let(merchantName) ?: rawLabel ?: "A shop")
        else -> ParcelActor.Unnamed(rawLabel ?: "Unknown")
    }

    private fun flipShare(
        share: TransactionShare,
        recipientFriendId: Long,
        friendName: (Long) -> String?
    ): ParcelShare? {
        val side = share.side ?: return null // Legacy sideless shares say nothing about who owes what.
        val participant = when {
            share.participantType == ActorType.ME -> ParcelActor.Sender
            share.friendId == recipientFriendId -> ParcelActor.Me
            else -> ParcelActor.Person(
                share.friendId?.let(friendName) ?: share.rawLabel ?: "Someone"
            )
        }
        return ParcelShare(side, participant, share.amountPaise)
    }

    /**
     * Turns a parcel row into rows for the reader's own database.
     *
     * [mapPerson] is the reader's own decision about who a name refers to, taken on the review
     * screen -- never a name lookup. Matching "Rahul" against a friend called Rahul and giving that
     * share their id would post a debt to someone the reader has never dealt with, on the strength
     * of a string; unmapped is the safe default and the only one this reaches on its own. See the
     * comment on [TransactionShare.rawLabel].
     *
     * [resolveShop] may look a shop up freely -- a merchant carries no debt either way.
     *
     * [carryUpiRefId] is false when the caller already found a transaction holding this reference;
     * writing it again would only trip the unique index.
     */
    fun toLocal(
        row: ParcelTransaction,
        senderFriendId: Long,
        originToken: String,
        mapPerson: (String) -> Long?,
        resolveShop: (String) -> Long?,
        carryUpiRefId: Boolean
    ): LocalParcelRow {
        val payer = row.payer.toLocalActor(senderFriendId, mapPerson, resolveShop)
        val payee = row.payee.toLocalActor(senderFriendId, mapPerson, resolveShop)

        val transaction = Transaction(
            amountPaise = row.amountPaise,
            payerActorType = payer.actorType,
            payerFriendId = payer.friendId,
            payerMerchantId = payer.merchantId,
            payerRawLabel = payer.rawLabel,
            payeeActorType = payee.actorType,
            payeeFriendId = payee.friendId,
            payeeMerchantId = payee.merchantId,
            payeeRawLabel = payee.rawLabel,
            reason = row.reason,
            upiRefId = row.upiRefId?.takeIf { carryUpiRefId },
            sharedRefId = sharedRefIdFor(originToken, row.sourceId),
            dateEpoch = row.dateEpoch,
            source = SOURCE_SHARED_PARCEL,
            // Shaped like every other import: pending, no IOU rows, no ledger posting. The
            // existing review flow completes it, and the user gets the last word on money.
            isPending = true,
            ledgerEffect = row.ledgerEffect
        )

        val shares = row.shares.map { share ->
            when (share.participant) {
                ParcelActor.Me -> TransactionShare(
                    transactionId = 0,
                    side = share.side,
                    participantType = ActorType.ME,
                    amountPaise = share.amountPaise
                )
                ParcelActor.Sender -> TransactionShare(
                    transactionId = 0,
                    side = share.side,
                    participantType = ActorType.FRIEND,
                    friendId = senderFriendId,
                    amountPaise = share.amountPaise
                )
                else -> {
                    val name = (share.participant as? ParcelActor.Person)?.name
                    TransactionShare(
                        transactionId = 0,
                        side = share.side,
                        participantType = ActorType.FRIEND,
                        friendId = name?.let(mapPerson),
                        amountPaise = share.amountPaise,
                        rawLabel = name
                    )
                }
            }
        }
        return LocalParcelRow(transaction, shares)
    }

    fun sharedRefIdFor(originToken: String, sourceId: Long): String = "$originToken.$sourceId"

    private data class LocalActor(
        val actorType: String,
        val friendId: Long? = null,
        val merchantId: Long? = null,
        val rawLabel: String? = null
    )

    private fun ParcelActor.toLocalActor(
        senderFriendId: Long,
        mapPerson: (String) -> Long?,
        resolveShop: (String) -> Long?
    ): LocalActor = when (this) {
        ParcelActor.Me -> LocalActor(ActorType.ME)
        ParcelActor.Sender -> LocalActor(ActorType.FRIEND, friendId = senderFriendId)
        is ParcelActor.Person -> LocalActor(
            ActorType.FRIEND,
            friendId = mapPerson(name),
            rawLabel = name
        )
        // Kept as a MERCHANT even when this database has never heard of the shop. The label alone
        // is enough for PendingReviewRules.canAutoReview, and the merchant side stays exempt from
        // the share sums, which an UNKNOWN would not be.
        is ParcelActor.Shop -> LocalActor(
            ActorType.MERCHANT,
            merchantId = resolveShop(name),
            rawLabel = name
        )
        is ParcelActor.Unnamed -> LocalActor(ActorType.UNKNOWN, rawLabel = label)
    }
}
