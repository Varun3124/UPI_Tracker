package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/** A chapter as the entry screen needs to see it: the row, who is in it, and whether it is square. */
data class ChapterOption(
    val chapter: Chapter,
    val memberIds: Set<Long>,
    val settled: Boolean
)

/**
 * When saving a transaction should involve a chapter, and which one.
 *
 * Pure so the entry screen and the notification's one-tap confirm cannot disagree -- the same reason
 * [com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules] is shared. If the
 * screen would offer a choice, the notification must decline to answer it on the user's behalf.
 */
object ChapterPrompt {

    /**
     * The friends a direct payment is between, or null when it is not one.
     *
     * ME is never in the set: the user is a member of every chapter and is never stored as one. A
     * gift is excluded -- it moves no debt, so it cannot be a settle-up -- as is anything with a
     * shop or an unidentified person at either end.
     */
    fun directPaymentFriends(tx: Transaction, shares: List<TransactionShare>): Set<Long>? {
        if (tx.ledgerEffect == LedgerEffect.NONE) return null
        val (payer, payee) = IouLegs.directPaymentParties(
            tx.payerActorRef(), tx.payeeActorRef(), shares, tx.amountPaise
        ) ?: return null

        val friends = linkedSetOf<Long>()
        listOf(payer, payee).forEach { party ->
            when (party) {
                is IouParty.Me -> Unit
                is IouParty.Friend -> friends += party.friendId
                else -> return null
            }
        }
        return friends
    }

    /**
     * R18: the open, unsettled chapters a direct payment could be settling up.
     *
     * Every party has to be in the chapter already. Offering one that would have to grow to take the
     * payment would be putting words in the user's mouth -- and the payment is meant to *reduce* a
     * debt the chapter already knows about.
     */
    fun settleUpCandidates(
        tx: Transaction,
        shares: List<TransactionShare>,
        options: List<ChapterOption>
    ): List<ChapterOption> {
        if (tx.chapterId != null) return emptyList()
        val friends = directPaymentFriends(tx, shares) ?: return emptyList()
        return options.filter { option ->
            // S10: a friend's chapter takes nothing tagged here, so it is never the answer.
            option.chapter.isOwn &&
                option.chapter.state == ChapterState.OPEN &&
                !option.settled &&
                friends.all { it in option.memberIds }
        }
    }

    /**
     * R19: the active chapter, when it can take this transaction and would add nobody to do it.
     *
     * The membership check is the important half. Pre-selecting into auto-adding someone would grow
     * a chapter's membership every time the user saved without looking at the field.
     */
    fun preselect(
        tx: Transaction,
        shares: List<TransactionShare>,
        options: List<ChapterOption>,
        originalOfRefund: Transaction?
    ): ChapterOption? {
        val active = options.firstOrNull {
            it.chapter.isOwn && it.chapter.isActive && it.chapter.state == ChapterState.OPEN
        } ?: return null
        if (ChapterEligibility.blockedReason(tx, shares, active.chapter, originalOfRefund) != null) return null
        if (ChapterEligibility.missingMembers(tx, shares, active.memberIds).isNotEmpty()) return null
        return active
    }

    /**
     * Whether confirming this from a notification would be answering a question that is the user's.
     *
     * The one-tap confirm posts straight to the ledger and never shows a chapter. So anything already
     * in a chapter, anything a chapter would be pre-selected for, and anything that would have been
     * asked about has to go to the entry screen instead.
     */
    fun needsEntryScreen(
        tx: Transaction,
        shares: List<TransactionShare>,
        options: List<ChapterOption>,
        originalOfRefund: Transaction?
    ): Boolean =
        tx.chapterId != null ||
            preselect(tx, shares, options, originalOfRefund) != null ||
            settleUpCandidates(tx, shares, options).isNotEmpty()
}
