package com.varun.upitracker.domain.declaration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md D9: which chapter changes a checkpoint absorbs. */
class AbsorptionTest {

    private val bob = 7L
    private val carol = 9L
    private val dan = 10L

    @Test
    fun `a change caused by rows before a checkpoint is absorbed for that friend`() {
        val asOf = mapOf(bob to 1_000L, carol to 400L)
        assertEquals(setOf(bob), Absorption.absorbingFriends(listOf(500L), asOf))
    }

    @Test
    fun `every version has to be covered`() {
        // A row edited from before Bob's checkpoint to after it: that is new, and counts.
        assertTrue(Absorption.absorbingFriends(listOf(500L, 1_500L), mapOf(bob to 1_000L)).isEmpty())
        // A purchase and a refund carried with it, both before.
        assertEquals(setOf(bob), Absorption.absorbingFriends(listOf(500L, 900L), mapOf(bob to 1_000L)))
    }

    @Test
    fun `a row at the checkpoint's own instant is covered`() {
        assertEquals(setOf(bob), Absorption.absorbingFriends(listOf(1_000L), mapOf(bob to 1_000L)))
    }

    @Test
    fun `no rows or no checkpoints absorb nothing`() {
        assertTrue(Absorption.absorbingFriends(emptyList(), mapOf(bob to 1_000L)).isEmpty())
        assertTrue(Absorption.absorbingFriends(listOf(1L), emptyMap()).isEmpty())
    }

    @Test
    fun `deltas are after minus before, for the absorbing friends only`() {
        val before = mapOf(bob to 30_000L, carol to -5_000L)
        val after = mapOf(bob to 45_000L, dan to 7_000L)
        assertEquals(
            mapOf(bob to 15_000L, carol to 5_000L),
            Absorption.deltas(before, after, setOf(bob, carol))
        )
        // A friend absent from both maps, or unchanged, is left out.
        assertTrue(Absorption.deltas(mapOf(bob to 1L), mapOf(bob to 1L), setOf(bob, carol)).isEmpty())
    }
}
