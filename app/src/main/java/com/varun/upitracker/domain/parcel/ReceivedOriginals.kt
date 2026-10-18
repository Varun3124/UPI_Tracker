package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.ReceivedOriginal
import com.varun.upitracker.database.entity.ReceivedOriginalShare
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/**
 * [current] with what a friend sent laid back over it: the row as it was the moment it arrived.
 *
 * Pending, and without anything only the receiver ever decided -- their account, their chapter, a
 * purchase it refunds. The references that keep it from being imported twice stay as they are.
 */
fun ReceivedOriginal.overlayOn(current: Transaction): Transaction = current.copy(
    amountPaise = amountPaise,
    dateEpoch = dateEpoch,
    payerActorType = payerActorType,
    payerFriendId = payerFriendId,
    payerMerchantId = payerMerchantId,
    payerRawLabel = payerRawLabel,
    payeeActorType = payeeActorType,
    payeeFriendId = payeeFriendId,
    payeeMerchantId = payeeMerchantId,
    payeeRawLabel = payeeRawLabel,
    reason = reason,
    ledgerEffect = ledgerEffect,
    iouRecovery = iouRecovery,
    isPending = true,
    myAccountId = null,
    chapterId = null,
    refundsTransactionId = null
)

fun ReceivedOriginalShare.toShare(): TransactionShare = TransactionShare(
    transactionId = transactionId,
    side = side,
    participantType = participantType,
    friendId = friendId,
    amountPaise = amountPaise,
    rawLabel = rawLabel,
    keepPayeeLeg = keepPayeeLeg,
    keepPayerLeg = keepPayerLeg,
    payeeLegVia = payeeLegVia,
    payeeLegViaFriendId = payeeLegViaFriendId,
    payerLegVia = payerLegVia,
    payerLegViaFriendId = payerLegViaFriendId
)

/**
 * Whether a received row now says something different from what was sent.
 *
 * Only what the two people share is compared -- the amount, the date, who paid whom, the split, the
 * note and who owes whom on it. The receiver's own bookkeeping (account, categories, chapter, having
 * confirmed it) is theirs alone and never counts as a change.
 *
 * Compared by meaning rather than by column, because confirming a row on the entry screen rewrites
 * some columns without changing anything: it labels ME "Me", finds the shop a name refers to, and
 * clears every IOU line on a row that moves nobody's balance. None of that is an edit.
 */
object ReceivedOriginalDiff {

    fun differs(
        original: ReceivedOriginal,
        originalShares: List<ReceivedOriginalShare>,
        tx: Transaction,
        shares: List<TransactionShare>,
        merchantName: (Long) -> String?
    ): Boolean {
        if (original.amountPaise != tx.amountPaise) return true
        if (original.dateEpoch != tx.dateEpoch) return true
        if (note(original.reason) != note(tx.reason)) return true
        if (!sameActor(original.payerActor(), tx.payerActor(), merchantName)) return true
        if (!sameActor(original.payeeActor(), tx.payeeActor(), merchantName)) return true

        val sent = originalShares.map { it.toShare() }
        if (shareKeys(sent, original.ledgerEffect) != shareKeys(shares, tx.ledgerEffect)) return true

        // Which side pays back only means anything where the two sides would post different lines.
        val asSent = original.overlayOn(tx)
        val matters = IouLegs.choiceMatters(tx.payerActorRef(), tx.payeeActorRef(), shares, tx.ledgerEffect) ||
            IouLegs.choiceMatters(asSent.payerActorRef(), asSent.payeeActorRef(), sent, asSent.ledgerEffect)
        return matters && IouLegs.resolve(asSent, sent) != IouLegs.resolve(tx, shares)
    }

    private data class Actor(val type: String, val friendId: Long?, val merchantId: Long?, val label: String?)

    private fun ReceivedOriginal.payerActor() = Actor(payerActorType, payerFriendId, payerMerchantId, payerRawLabel)
    private fun ReceivedOriginal.payeeActor() = Actor(payeeActorType, payeeFriendId, payeeMerchantId, payeeRawLabel)
    private fun Transaction.payerActor() = Actor(payerActorType, payerFriendId, payerMerchantId, payerRawLabel)
    private fun Transaction.payeeActor() = Actor(payeeActorType, payeeFriendId, payeeMerchantId, payeeRawLabel)

    private fun sameActor(a: Actor, b: Actor, merchantName: (Long) -> String?): Boolean {
        if (a.type != b.type) return false
        return when (a.type) {
            ActorType.ME -> true
            // A name only, on either side, is a different statement from a friend: mapping someone
            // the parcel named to one of your friends is an edit, and going back undoes it.
            ActorType.FRIEND -> when {
                a.friendId != null || b.friendId != null -> a.friendId == b.friendId
                else -> name(a.label) == name(b.label)
            }
            // Shops carry no debt, and confirming finds the shop a label means. Same shop is same.
            ActorType.MERCHANT -> when {
                a.merchantId != null && b.merchantId != null -> a.merchantId == b.merchantId
                else -> name(a.merchantId?.let(merchantName) ?: a.label) ==
                    name(b.merchantId?.let(merchantName) ?: b.label)
            }
            else -> name(a.label) == name(b.label)
        }
    }

    /** Order-free: the split is a set of people and amounts, however the rows were laid out. */
    private fun shareKeys(shares: List<TransactionShare>, ledgerEffect: LedgerEffect): List<String> =
        shares.map { share ->
            val who = when {
                share.participantType == ActorType.ME -> "me"
                share.friendId != null -> "friend:${share.friendId}"
                else -> "name:${name(share.rawLabel)}"
            }
            // A row that moves nobody's balance keeps no IOU line, whatever its legs were left at.
            val kept = ledgerEffect != LedgerEffect.NONE
            val legs = "${kept && share.keepPayeeLeg}/${kept && share.keepPayerLeg}"
            // Rerouting a debt is an edit: the same people end up owing different ones.
            val via = "${IouLegs.viaOf(share, owesPayer = false)}/${IouLegs.viaOf(share, owesPayer = true)}"
            "${share.side}|$who|${share.amountPaise}|$legs|$via"
        }.sorted()

    private fun name(label: String?): String = label?.trim()?.lowercase().orEmpty()

    private fun note(reason: String?): String? = reason?.trim()?.ifEmpty { null }
}
