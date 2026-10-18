package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareManagerTemplateTest {

    private val manager = ShareManager()
    private val bob = Friend(id = 1, name = "Bob", avatarInitials = "B", addedEpoch = 0)
    private val cara = Friend(id = 2, name = "Cara", avatarInitials = "C", addedEpoch = 0)
    private val friends = listOf(bob, cara)

    private val mePrimary = ShareRowModel("ME", ActorType.ME, null, "Asha(Me)", "ME", 500L)
    private val blankFriendPrimary = ShareRowModel("F:0", ActorType.FRIEND, null, "", "?", 0L)

    @Test
    fun meSide_putsMembersAfterMe() {
        val rows = manager.withChapterMembers(mePrimary, ActorType.ME, listOf(1L, 2L), friends, "Asha(Me)")

        assertEquals(listOf("ME", "F:1", "F:2"), rows.map { it.key })
        // The primary keeps what it had; everyone added starts at nothing.
        assertEquals(listOf(500L, 0L, 0L), rows.map { it.amountPaise })
    }

    @Test
    fun friendSide_leavesThePrimaryBlankAndAddsMeWithTheMembers() {
        val rows = manager.withChapterMembers(
            blankFriendPrimary, ActorType.FRIEND, listOf(1L, 2L), friends, "Asha(Me)"
        )

        assertEquals(listOf("F:0", "ME", "F:1", "F:2"), rows.map { it.key })
        assertEquals("", rows[0].label)
        assertEquals("Asha(Me)", rows[1].label)
        assertTrue(rows.drop(1).all { it.amountPaise == 0L })
    }

    @Test
    fun merchantSide_isUntouched() {
        val merchant = ShareRowModel("M:9", ActorType.MERCHANT, null, "Cafe", "M", 500L)

        val rows = manager.withChapterMembers(merchant, ActorType.MERCHANT, listOf(1L, 2L), friends, "Me")

        assertEquals(listOf(merchant), rows)
    }

    @Test
    fun aMemberWithNoFriendBehindThemIsSkipped() {
        val rows = manager.withChapterMembers(mePrimary, ActorType.ME, listOf(1L, 99L), friends, "Me")

        assertEquals(listOf("ME", "F:1"), rows.map { it.key })
    }
}
