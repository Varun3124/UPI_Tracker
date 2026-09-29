package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType

/**
 * Whether a transaction can go into a chapter, and if not, what to say about it.
 *
 * Kept out of the repository so the rule is under test and so the entry screen, the bulk picker and
 * the save path all give the same answer -- the same reason [com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules]
 * is shared. See docs/chapters-design.md R4.
 *
 * Note what is deliberately *not* refused. A transaction with no friend in it at all -- ME and a
 * shop -- is taggable: it contributes no legs, so it moves no net and no balance, and it is there so
 * a chapter reads as a complete record of the trip. A pending transaction and a gift are taggable
 * for the same reason: both count for nothing until they do, exactly as in the base ledger.
 */
object ChapterEligibility {

    private const val PAYER = "PAYER"
    private const val PAYEE = "PAYEE"

    /**
     * Null when [tx] can be tagged into [chapter]; otherwise why not, short enough to sit on a row.
     *
     * [originalOfRefund] is the purchase [tx] reverses, or null when it reverses nothing. A refund
     * has to sit wherever its original sits -- splitting the two would leave a chapter counting a
     * purchase it never got the money back for.
     */
    fun blockedReason(
        tx: Transaction,
        shares: List<TransactionShare>,
        chapter: Chapter,
        originalOfRefund: Transaction?
    ): String? = when {
        // S6: only the owner's phone puts rows in a shared chapter. A member sends theirs to the owner.
        !chapter.isOwn -> "Someone else's chapter"

        chapter.state == ChapterState.CLOSED -> "Chapter is closed"

        // Both ends being ME is an account transfer's shape: it moves no debt and belongs to nobody.
        tx.payerActorType == ActorType.ME && tx.payeeActorType == ActorType.ME ->
            "Between your own accounts"

        // Checked before anything about who is in it, so someone whose name was never mapped is
        // told to map it rather than being given a reason that points at the wrong fix.
        unidentifiedName(tx, shares) != null -> "Pick who ${unidentifiedName(tx, shares)} is first"

        tx.refundsTransactionId != null && originalOfRefund?.chapterId != chapter.id ->
            "Follows its original purchase"

        else -> null
    }

    /**
     * Who is in [tx] but not yet in the chapter, so tagging can add them and the UI can say so.
     *
     * R5: tagging a transaction pulls everyone in it into the chapter, in the same database
     * transaction. A chapter cannot hold a debt for someone who is not a member.
     */
    fun missingMembers(
        tx: Transaction,
        shares: List<TransactionShare>,
        memberIds: Set<Long>
    ): Set<Long> = ChapterMath.friendsIn(tx, shares) - memberIds

    /**
     * The name of someone this database cannot place, quoted, or null when everyone is known.
     *
     * Only the two ends and *sided* share rows. A legacy sideless share produces no legs at all, so
     * it can neither corrupt a chapter nor be the reason someone is in one -- refusing a transaction
     * over one would block a perfectly safe row.
     */
    private fun unidentifiedName(tx: Transaction, shares: List<TransactionShare>): String? {
        if (tx.payerActorType == ActorType.FRIEND && tx.payerFriendId == null) {
            return quoted(tx.payerRawLabel)
        }
        if (tx.payeeActorType == ActorType.FRIEND && tx.payeeFriendId == null) {
            return quoted(tx.payeeRawLabel)
        }
        val share = shares.firstOrNull {
            (it.side == PAYER || it.side == PAYEE) &&
                it.participantType == ActorType.FRIEND &&
                it.friendId == null
        }
        return share?.let { quoted(it.rawLabel) }
    }

    private fun quoted(label: String?): String =
        if (label.isNullOrBlank()) "this person" else "‘${label.trim()}’"
}
