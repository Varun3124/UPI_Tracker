package com.varun.upitracker.domain.chapter

import com.varun.upitracker.ui.AmountPerspective
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPlanLabelsTest {

    private val names = mapOf(1L to "Dan", 2L to "Ann")

    private fun payment(debtor: ChapterParty, creditor: ChapterParty, paise: Long = 70_000L) =
        ChapterPayment(debtor, creditor, paise)

    @Test
    fun aFriendPayingTheUserReadsAsOwingThem() {
        val row = ChapterPlanLabels.rowFor(
            payment(ChapterParty.Friend(1L), ChapterParty.Me), names
        )

        assertEquals("Dan", row.subjectName)
        assertEquals("owes you", row.label)
        assertEquals(70_000L, row.amountPaise)
        assertEquals(AmountPerspective.INCOMING, row.direction)
    }

    @Test
    fun theUserPayingAFriendReadsAsOwingThem() {
        val row = ChapterPlanLabels.rowFor(
            payment(ChapterParty.Me, ChapterParty.Friend(2L)), names
        )

        assertEquals("Ann", row.subjectName)
        assertEquals("you owe", row.label)
        assertEquals(AmountPerspective.OUTGOING, row.direction)
    }

    /**
     * Simplification can reach a payment between two friends who never transacted. It is still
     * shown -- the chapter is not settled until it happens -- but none of the user's own money
     * moves, so it carries the neutral colour rather than a red or a green.
     */
    @Test
    fun aPaymentBetweenTwoFriendsNamesBothAndIsNeutral() {
        val row = ChapterPlanLabels.rowFor(
            payment(ChapterParty.Friend(1L), ChapterParty.Friend(2L)), names
        )

        assertEquals("Dan", row.subjectName)
        assertEquals("pays Ann", row.label)
        assertEquals(AmountPerspective.NEUTRAL, row.direction)
    }

    /** A friend deleted from under a chapter still has to draw rather than crash. */
    @Test
    fun anUnknownFriendFallsBackToTheirId() {
        val row = ChapterPlanLabels.rowFor(
            payment(ChapterParty.Friend(99L), ChapterParty.Me), names
        )

        assertEquals("Friend 99", row.subjectName)
    }
}
