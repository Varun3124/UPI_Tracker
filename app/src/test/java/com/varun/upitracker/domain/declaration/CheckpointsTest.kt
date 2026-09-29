package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md D7 and D8: the opening a checkpoint becomes, and what it seals. */
class CheckpointsTest {

    private val bob = 7L
    private val carol = 9L

    private val agreed = IouDeclaration(
        id = "a", friendId = bob, kind = DeclarationKind.DECLARE, asOfEpoch = 1_000L, amountPaise = 120_000L,
        proposedByMe = true, proposedEpoch = 1_000L, state = DeclarationState.ACCEPTED
    )

    @Test
    fun `with no parts the opening is the whole agreed amount`() {
        assertEquals(120_000L, Checkpoints.openingAmount(agreed, emptyList()))
    }

    @Test
    fun `the chapter shares the agreement counted come off the opening`() {
        val parts = listOf(
            DeclarationPart(declarationId = "a", chapterId = 3L, shareId = null, amountPaise = 30_000L),
            DeclarationPart(declarationId = "a", chapterId = 4L, shareId = "s", amountPaise = -5_000L)
        )
        assertEquals(95_000L, Checkpoints.openingAmount(agreed, parts))
    }

    @Test
    fun `a part whose chapter has not arrived yet does not count`() {
        val parts = listOf(DeclarationPart(declarationId = "a", chapterId = null, shareId = "s", amountPaise = 30_000L))
        assertEquals(120_000L, Checkpoints.openingAmount(agreed, parts))
    }

    @Test
    fun `the opening carries the checkpoint's instant`() {
        val opening = Checkpoints.openingOf(agreed, emptyList())
        assertEquals(Opening("a", bob, 1_000L, 120_000L), opening)
    }

    @Test
    fun `a row at or before the checkpoint is sealed`() {
        assertTrue(Checkpoints.isSealed(999L, 1_000L))
        assertTrue(Checkpoints.isSealed(1_000L, 1_000L))
        assertFalse(Checkpoints.isSealed(1_001L, 1_000L))
        assertFalse("no checkpoint seals nothing", Checkpoints.isSealed(1L, null))
    }

    @Test
    fun `sealing is per friend`() {
        val asOf = mapOf(bob to 1_000L)
        assertEquals(setOf(carol), Checkpoints.unsealed(listOf(bob, carol), 500L, asOf))
        assertEquals(setOf(bob), Checkpoints.sealed(listOf(bob, carol), 500L, asOf))
        assertEquals(setOf(bob, carol), Checkpoints.unsealed(listOf(bob, carol), 2_000L, asOf))
    }

    @Test
    fun `the replay floor is the earliest checkpoint, and only when everyone has one`() {
        assertEquals(400L, Checkpoints.replayFloor(listOf(bob, carol), mapOf(bob to 1_000L, carol to 400L)))
        assertNull(Checkpoints.replayFloor(listOf(bob, carol), mapOf(bob to 1_000L)))
        assertNull(Checkpoints.replayFloor(emptyList(), emptyMap()))
    }
}
