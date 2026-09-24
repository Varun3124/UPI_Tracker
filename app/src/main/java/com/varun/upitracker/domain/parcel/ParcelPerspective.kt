package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/** A parcel row turned back into rows this database can hold. */
data class LocalParcelRow(
    val transaction: Transaction,
    val shares: List<TransactionShare>
)

/**
 * Turns a transaction around so the other party can read it, and back again.
 *
 * The whole feature rests on one swap: the writer's ME becomes SENDER, and the recipient becomes
 * ME. Everything else -- amounts, dates, sides, which side the shares sit on, which side of the split
 * pays back -- is left exactly as it was, because a bill split does not change shape depending on who
 * is reading it.
 *
 * Names are passed in as lambdas rather than looked up, the same seam
 * [com.varun.upitracker.ledger.LedgerPort] uses, so this stays a pure function and stays under test.
 */
object ParcelPerspective {

    const val SOURCE_SHARED_PARCEL = "SHARED_PARCEL"

    private const val MAILBOX_REF_PREFIX = "mbx:"

    /**
     * Rewrites one of the writer's transactions into the form [recipientFriendId] should read.
     *
     * Dropped on the way out, all of them meaningless to anyone else: the row id, `myAccountId`
     * (the writer's bank account), `statementRefNo`, `refundsTransactionId` (it points at a row
     * only the writer holds), `isPending` and `source`.
     *
     * [linkedUidOf] names a third party by account as well as by name. The mailbox passes it for the
     * other people the same transaction is going to, so their copies can recognise one another; a
     * pasted parcel never does, having nothing that verifies who wrote it.
     */
    fun flipForRecipient(
        transaction: Transaction,
        shares: List<TransactionShare>,
        recipientFriendId: Long,
        friendName: (Long) -> String?,
        merchantName: (Long) -> String?,
        linkedUidOf: (Long) -> String? = { null }
    ): ParcelTransaction {
        val payer = flipActor(
            actorType = transaction.payerActorType,
            friendId = transaction.payerFriendId,
            merchantId = transaction.payerMerchantId,
            rawLabel = transaction.payerRawLabel,
            recipientFriendId = recipientFriendId,
            friendName = friendName,
            merchantName = merchantName,
            linkedUidOf = linkedUidOf
        )
        val payee = flipActor(
            actorType = transaction.payeeActorType,
            friendId = transaction.payeeFriendId,
            merchantId = transaction.payeeMerchantId,
            rawLabel = transaction.payeeRawLabel,
            recipientFriendId = recipientFriendId,
            friendName = friendName,
            merchantName = merchantName,
            linkedUidOf = linkedUidOf
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
            shares = shares.mapNotNull { share ->
                flipShare(share, recipientFriendId, friendName, linkedUidOf)
            },
            iouRecovery = IouLegs.resolve(transaction, shares)
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
        merchantName: (Long) -> String?,
        linkedUidOf: (Long) -> String?
    ): ParcelActor = when (actorType) {
        ActorType.ME -> ParcelActor.Sender
        ActorType.FRIEND ->
            if (friendId == recipientFriendId) {
                ParcelActor.Me
            } else {
                // A friend of the writer's the reader may well not know. Named, never identified --
                // unless they were sent this too, when the account they linked with says who.
                thirdParty(friendId, friendId?.let(friendName) ?: rawLabel ?: "Someone", linkedUidOf)
            }
        ActorType.MERCHANT -> ParcelActor.Shop(merchantId?.let(merchantName) ?: rawLabel ?: "A shop")
        else -> ParcelActor.Unnamed(rawLabel ?: "Unknown")
    }

    private fun flipShare(
        share: TransactionShare,
        recipientFriendId: Long,
        friendName: (Long) -> String?,
        linkedUidOf: (Long) -> String?
    ): ParcelShare? {
        val side = share.side ?: return null // Legacy sideless shares say nothing about who owes what.
        val participant = when {
            share.participantType == ActorType.ME -> ParcelActor.Sender
            share.friendId == recipientFriendId -> ParcelActor.Me
            else -> thirdParty(
                share.friendId,
                share.friendId?.let(friendName) ?: share.rawLabel ?: "Someone",
                linkedUidOf
            )
        }
        return ParcelShare(side, participant, share.amountPaise, share.keepPayeeLeg, share.keepPayerLeg)
    }

    private fun thirdParty(friendId: Long?, name: String, linkedUidOf: (Long) -> String?): ParcelActor =
        friendId?.let(linkedUidOf)?.let { uid -> ParcelActor.Linked(uid, name) } ?: ParcelActor.Person(name)

    /**
     * Turns a pasted parcel's row into rows for the reader's own database.
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
    ): LocalParcelRow = land(
        row = row,
        senderFriendId = senderFriendId,
        sharedRefId = sharedRefIdFor(originToken, row.sourceId),
        mapPerson = mapPerson,
        resolveLinked = { null },
        resolveShop = resolveShop,
        carryUpiRefId = carryUpiRefId
    )

    /**
     * [toLocal] for a row that came by mailbox from [senderUid], already decrypted and verified.
     *
     * [resolveLinked] maps an account to the reader's own friend linked with it, and is the only way
     * a [ParcelActor.Linked] gets an id without the reader saying so: linking was an explicit,
     * two-sided act, which is exactly what a matching name is not. An account the reader has not
     * linked falls back to a name, which [mapPerson] can still map by hand.
     */
    fun toLocalFromMailbox(
        row: ParcelTransaction,
        senderFriendId: Long,
        senderUid: String,
        mapPerson: (String) -> Long?,
        resolveLinked: (String) -> Long?,
        resolveShop: (String) -> Long?,
        carryUpiRefId: Boolean
    ): LocalParcelRow = land(
        row = row,
        senderFriendId = senderFriendId,
        sharedRefId = mailboxRefIdFor(
            senderUid,
            requireNotNull(row.shareRef) { "A mailbox row always carries a share reference." }
        ),
        mapPerson = mapPerson,
        resolveLinked = resolveLinked,
        resolveShop = resolveShop,
        carryUpiRefId = carryUpiRefId
    )

    fun sharedRefIdFor(originToken: String, sourceId: Long): String = "$originToken.$sourceId"

    /**
     * Where a mailbox row lands: namespaced by the **verified** sender, never by anything the parcel
     * says about itself. A sender picking the reference cannot then claim a row another friend sent
     * first, and every friend the row went to holds it under the same reference.
     */
    fun mailboxRefIdFor(senderUid: String, shareRef: String): String =
        "$MAILBOX_REF_PREFIX$senderUid:$shareRef"

    /** The start every [mailboxRefIdFor] from [senderUid] shares. */
    fun mailboxRefPrefixFor(senderUid: String): String = "$MAILBOX_REF_PREFIX$senderUid:"

    /**
     * False when a mailbox parcel names its own sender or its reader as a [ParcelActor.Linked]. Both
     * already have a role -- [ParcelActor.Sender] and [ParcelActor.Me] -- and a second copy of either
     * would count their share twice, so such a parcel is refused whole rather than repaired.
     */
    fun linkedUidsAreThirdParties(parcel: Parcel, senderUid: String, readerUid: String): Boolean =
        parcel.transactions.asSequence()
            .flatMap { tx -> sequenceOf(tx.payer, tx.payee) + tx.shares.asSequence().map { it.participant } }
            .filterIsInstance<ParcelActor.Linked>()
            .none { it.uid == senderUid || it.uid == readerUid }

    private fun land(
        row: ParcelTransaction,
        senderFriendId: Long,
        sharedRefId: String,
        mapPerson: (String) -> Long?,
        resolveLinked: (String) -> Long?,
        resolveShop: (String) -> Long?,
        carryUpiRefId: Boolean
    ): LocalParcelRow {
        val payer = row.payer.toLocalActor(senderFriendId, mapPerson, resolveLinked, resolveShop)
        val payee = row.payee.toLocalActor(senderFriendId, mapPerson, resolveLinked, resolveShop)

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
            sharedRefId = sharedRefId,
            dateEpoch = row.dateEpoch,
            source = SOURCE_SHARED_PARCEL,
            // Shaped like every other import: pending, no IOU rows, no ledger posting. The
            // existing review flow completes it, and the user gets the last word on money.
            isPending = true,
            ledgerEffect = row.ledgerEffect
        )

        val shares = row.shares.map { share ->
            val landed = when (val participant = share.participant) {
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
                is ParcelActor.Linked -> TransactionShare(
                    transactionId = 0,
                    side = share.side,
                    participantType = ActorType.FRIEND,
                    friendId = resolveLinked(participant.uid) ?: mapPerson(participant.name),
                    amountPaise = share.amountPaise,
                    rawLabel = participant.name
                )
                else -> {
                    val name = (participant as? ParcelActor.Person)?.name
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
            // Which IOUs the writer kept travels with the share, like the side it recovers from.
            landed.copy(keepPayeeLeg = share.keepPayeeLeg, keepPayerLeg = share.keepPayerLeg)
        }
        // Applied as the writer chose, never re-derived here: a copy recovering from the other side
        // would disagree with theirs about who owes what. A version 1 parcel never said, and reads as
        // what the writer's own app settled on for it -- the side they were on.
        val landedPayer = transaction.payerActorRef()
        val landedPayee = transaction.payeeActorRef()
        val iouRecovery = IouLegs.effectiveRecovery(
            landedPayer,
            landedPayee,
            row.iouRecovery ?: IouLegs.fromRole(landedPayer, landedPayee, shares, IouParty.Friend(senderFriendId))
        )
        return LocalParcelRow(transaction.copy(iouRecovery = iouRecovery), shares)
    }

    private data class LocalActor(
        val actorType: String,
        val friendId: Long? = null,
        val merchantId: Long? = null,
        val rawLabel: String? = null
    )

    private fun ParcelActor.toLocalActor(
        senderFriendId: Long,
        mapPerson: (String) -> Long?,
        resolveLinked: (String) -> Long?,
        resolveShop: (String) -> Long?
    ): LocalActor = when (this) {
        ParcelActor.Me -> LocalActor(ActorType.ME)
        ParcelActor.Sender -> LocalActor(ActorType.FRIEND, friendId = senderFriendId)
        is ParcelActor.Person -> LocalActor(
            ActorType.FRIEND,
            friendId = mapPerson(name),
            rawLabel = name
        )
        is ParcelActor.Linked -> LocalActor(
            ActorType.FRIEND,
            friendId = resolveLinked(uid) ?: mapPerson(name),
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
