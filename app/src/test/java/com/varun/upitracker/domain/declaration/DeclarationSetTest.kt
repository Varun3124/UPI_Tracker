package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md D4 and D5: which declarations are in force, and which is the checkpoint. */
class DeclarationSetTest {

    private val bob = 7L

    private fun declare(id: String, asOf: Long, amount: Long, state: String = DeclarationState.ACCEPTED, archived: Boolean = false) =
        IouDeclaration(
            id = id, friendId = bob, kind = DeclarationKind.DECLARE, asOfEpoch = asOf, amountPaise = amount,
            proposedByMe = true, proposedEpoch = asOf, state = state, archived = archived
        )

    private fun amend(id: String, target: String, asOf: Long, amount: Long, state: String = DeclarationState.ACCEPTED) =
        IouDeclaration(
            id = id, friendId = bob, kind = DeclarationKind.AMEND, targetId = target, asOfEpoch = asOf, amountPaise = amount,
            proposedByMe = false, proposedEpoch = asOf + 1, state = state
        )

    private fun revoke(id: String, target: String, state: String = DeclarationState.ACCEPTED) =
        IouDeclaration(
            id = id, friendId = bob, kind = DeclarationKind.REVOKE, targetId = target,
            proposedByMe = true, proposedEpoch = 999L, state = state
        )

    @Test
    fun `nothing is in force until it is accepted`() {
        assertNull(DeclarationSet.effective(listOf(declare("a", 100L, 500L, state = DeclarationState.OPEN))))
        assertNull(DeclarationSet.effective(listOf(declare("a", 100L, 500L, state = DeclarationState.DENIED))))
        assertNull(DeclarationSet.effective(listOf(declare("a", 100L, 500L, state = DeclarationState.WITHDRAWN))))
    }

    @Test
    fun `the latest checkpoint wins`() {
        val rows = listOf(declare("a", 100L, 500L), declare("b", 300L, 900L), declare("c", 200L, 700L))
        assertEquals("b", DeclarationSet.effective(rows)?.id)
    }

    @Test
    fun `the id breaks a tie on the instant, the same way on both phones`() {
        val rows = listOf(declare("x", 100L, 500L), declare("y", 100L, 600L))
        assertEquals("y", DeclarationSet.effective(rows)?.id)
        assertEquals("y", DeclarationSet.effective(rows.reversed())?.id)
    }

    @Test
    fun `an accepted amendment replaces its target`() {
        val rows = listOf(declare("a", 100L, 500L), amend("b", "a", 100L, 900L))
        assertEquals(listOf("b"), DeclarationSet.live(rows).map { it.id })
        assertEquals(900L, DeclarationSet.effective(rows)?.amountPaise)
    }

    @Test
    fun `an open amendment changes nothing`() {
        val rows = listOf(declare("a", 100L, 500L), amend("b", "a", 100L, 900L, state = DeclarationState.OPEN))
        assertEquals("a", DeclarationSet.effective(rows)?.id)
    }

    @Test
    fun `revoking the checkpoint brings the one before it back`() {
        val rows = listOf(declare("a", 100L, 500L), declare("b", 200L, 700L), revoke("r", "b"))
        assertEquals("a", DeclarationSet.effective(rows)?.id)
    }

    @Test
    fun `a removal seen before its target still wins`() {
        // The revoke of "b" is known here before "b" itself was accepted.
        val rows = listOf(revoke("r", "b"), declare("b", 200L, 700L))
        assertNull(DeclarationSet.effective(rows))
    }

    @Test
    fun `archived declarations still anchor the balance`() {
        val rows = listOf(declare("a", 100L, 500L, archived = true))
        assertEquals("a", DeclarationSet.effective(rows)?.id)
    }

    @Test
    fun `every order of the same accepted proposals gives the same checkpoint`() {
        val rows = listOf(
            declare("a", 100L, 500L),
            amend("b", "a", 100L, 800L),
            declare("c", 300L, 200L),
            revoke("r", "c"),
            amend("d", "b", 100L, 850L),
            declare("e", 50L, 10L)
        )
        val expected = DeclarationSet.effective(rows)?.id
        assertEquals("d", expected)
        permutations(rows).forEach { order ->
            assertEquals(expected, DeclarationSet.effective(order)?.id)
            assertEquals(DeclarationSet.live(rows).map { it.id }.toSet(), DeclarationSet.live(order).map { it.id }.toSet())
        }
    }

    @Test
    fun `checkpoints are worked out per friend`() {
        val carol = 9L
        val rows = listOf(declare("a", 100L, 500L), declare("b", 200L, 50L).copy(friendId = carol))
        val byFriend = DeclarationSet.effectiveByFriend(rows)
        assertEquals("a", byFriend[bob]?.id)
        assertEquals("b", byFriend[carol]?.id)
    }

    @Test
    fun `a withdrawal stub is never a declaration`() {
        val stub = IouDeclaration(
            id = "s", friendId = bob, kind = null, proposedByMe = false, proposedEpoch = 1L,
            state = DeclarationState.WITHDRAWN
        )
        assertTrue(DeclarationSet.live(listOf(stub)).isEmpty())
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.indices.flatMap { index ->
            val rest = items.toMutableList().also { it.removeAt(index) }
            permutations(rest).map { listOf(items[index]) + it }
        }
}
