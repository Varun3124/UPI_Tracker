package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType

/**
 * Whether a transaction can be sent to a given friend, and if not, what to say about it.
 *
 * Lifted out of `ParcelExportRepository` so the rule is under test: it decides what leaves the
 * device. The two ways out ask different questions. The mailbox sends only to people in the
 * transaction ([blockedReason]); a pasted parcel can go to anyone the user picks
 * ([pasteBlockedReason]), since they are the one handing it over.
 */
object ParcelEligibility {

    /**
     * Null when [tx] can be pasted to any friend at all; otherwise why not, short enough to sit on
     * a row.
     *
     * The recipient need not be in it. [ParcelPerspective.flipForRecipient] then has nobody to turn
     * into ME, so the row lands on their side with the writer as SENDER and everyone else named --
     * a record of someone else's money, which posts nothing to their own balances.
     */
    fun pasteBlockedReason(tx: Transaction): String? = when {
        // Not reviewed here yet, so it carries no shares and its actors may still be unknown.
        // Sending one hands the other person a half-formed row to puzzle over.
        tx.isPending -> "Not reviewed yet"

        // Sending it back would earn a fresh reference under our own token and defeat the
        // dedup of whoever sent it in the first place.
        tx.sharedRefId != null -> "Came from a parcel"

        tx.payerActorType == ActorType.UNKNOWN || tx.payeeActorType == ActorType.UNKNOWN ->
            "Needs a payer and payee"

        // Both ends being ME is an account transfer's shape: private, and nonsense once flipped.
        tx.payerActorType == ActorType.ME && tx.payeeActorType == ActorType.ME ->
            "Between your own accounts"

        else -> null
    }

    /**
     * Null when [tx] can go to [recipientFriendId] through the mailbox; otherwise why not.
     *
     * [recipientHasShare] is whether they hold a share with a side. A sideless legacy share is
     * dropped by [ParcelPerspective.flipForRecipient], so it cannot be what puts them in the row.
     */
    fun blockedReason(tx: Transaction, recipientFriendId: Long, recipientHasShare: Boolean): String? =
        pasteBlockedReason(tx) ?: when {
            // They have to be in it somewhere for the flip to make them ME. Being in the split is
            // enough, even when neither end is you: a dinner someone else paid for and split with
            // this friend is theirs to know about too. It still lands pending on their side.
            tx.payerFriendId != recipientFriendId && tx.payeeFriendId != recipientFriendId &&
                !recipientHasShare -> "Nothing here to share"

            else -> null
        }

    fun blockedReason(tx: Transaction, shares: List<TransactionShare>, recipientFriendId: Long): String? =
        blockedReason(tx, recipientFriendId, hasSidedShare(shares, recipientFriendId))

    fun hasSidedShare(shares: List<TransactionShare>, friendId: Long): Boolean = shares.any {
        it.side != null && it.participantType == ActorType.FRIEND && it.friendId == friendId
    }
}
