package com.varun.upitracker.domain.chapter

import com.varun.upitracker.ui.AmountPerspective

/**
 * One payment of a chapter's plan, in the shape the dashboard's IOU cards draw: an avatar and a
 * name, a short sentence, and an amount whose colour carries the direction.
 */
data class ChapterPlanRow(
    /** Whose avatar and name the row carries. */
    val subjectName: String,
    val label: String,
    val amountPaise: Long,
    val direction: AmountPerspective
)

/**
 * Turning a [ChapterPayment] into a row that reads like a friend's IOU line.
 *
 * A plan payment is symmetrical -- "A pays B" -- but an IOU card is written from the user's point of
 * view, so which end becomes the row's subject depends on whether ME is in it. Pure, and separate
 * from the screen, because the three cases are worth asserting rather than eyeballing.
 *
 * [AmountPerspective] rather than a colour: `colorAttr()` already maps it to the same
 * positive/negative/neutral roles every other amount in the app is painted with, so an owed amount
 * here cannot end up a different green from an owed amount on a friend's page.
 */
object ChapterPlanLabels {

    fun rowFor(payment: ChapterPayment, names: Map<Long, String>): ChapterPlanRow {
        val debtor = payment.debtor
        val creditor = payment.creditor
        return when {
            debtor is ChapterParty.Me -> ChapterPlanRow(
                subjectName = nameOf(creditor, names),
                label = "you owe",
                amountPaise = payment.amountPaise,
                direction = AmountPerspective.OUTGOING
            )
            creditor is ChapterParty.Me -> ChapterPlanRow(
                subjectName = nameOf(debtor, names),
                label = "owes you",
                amountPaise = payment.amountPaise,
                direction = AmountPerspective.INCOMING
            )
            // Neither end is the user. Simplification can reach a payment between two friends who
            // never transacted, and it is still worth showing: the chapter is not settled until it
            // happens, even though none of the user's own money moves.
            else -> ChapterPlanRow(
                subjectName = nameOf(debtor, names),
                label = "pays ${nameOf(creditor, names)}",
                amountPaise = payment.amountPaise,
                direction = AmountPerspective.NEUTRAL
            )
        }
    }

    /** Matches ChapterDetailViewModel's fallback: a friend deleted from under a chapter still reads. */
    private fun nameOf(party: ChapterParty, names: Map<Long, String>): String = when (party) {
        is ChapterParty.Me -> "You"
        is ChapterParty.Friend -> names[party.friendId] ?: "Friend ${party.friendId}"
    }
}
