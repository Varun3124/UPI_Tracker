package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md D2, D3 and D12: how a proposal moves. */
class DeclarationFlowTest {

    @Test
    fun `an open proposal can be accepted, denied, withdrawn or closed`() {
        assertEquals(DeclarationState.ACCEPTED, DeclarationFlow.next(DeclarationState.OPEN, DeclarationEvent.ACCEPTED))
        assertEquals(DeclarationState.DENIED, DeclarationFlow.next(DeclarationState.OPEN, DeclarationEvent.DENIED))
        assertEquals(DeclarationState.WITHDRAWN, DeclarationFlow.next(DeclarationState.OPEN, DeclarationEvent.WITHDRAWN))
        assertEquals(DeclarationState.CLOSED, DeclarationFlow.next(DeclarationState.OPEN, DeclarationEvent.UNLINKED))
    }

    @Test
    fun `accept beats withdraw`() {
        // The other side accepted before they saw the withdrawal, and their book already has it.
        assertEquals(DeclarationState.ACCEPTED, DeclarationFlow.next(DeclarationState.WITHDRAWN, DeclarationEvent.ACCEPTED))
        // And a withdrawal arriving after an acceptance changes nothing.
        assertNull(DeclarationFlow.next(DeclarationState.ACCEPTED, DeclarationEvent.WITHDRAWN))
    }

    @Test
    fun `an acceptance sent before an unlink still counts`() {
        assertEquals(DeclarationState.ACCEPTED, DeclarationFlow.next(DeclarationState.CLOSED, DeclarationEvent.ACCEPTED))
    }

    @Test
    fun `a decided proposal ignores later answers`() {
        assertNull(DeclarationFlow.next(DeclarationState.ACCEPTED, DeclarationEvent.ACCEPTED))
        assertNull(DeclarationFlow.next(DeclarationState.ACCEPTED, DeclarationEvent.DENIED))
        assertNull(DeclarationFlow.next(DeclarationState.DENIED, DeclarationEvent.ACCEPTED))
        assertNull(DeclarationFlow.next(DeclarationState.DENIED, DeclarationEvent.WITHDRAWN))
        assertNull(DeclarationFlow.next(DeclarationState.WITHDRAWN, DeclarationEvent.DENIED))
    }

    @Test
    fun `unlinking leaves an accepted declaration accepted`() {
        // It is archived instead, which is not a state change.
        assertNull(DeclarationFlow.next(DeclarationState.ACCEPTED, DeclarationEvent.UNLINKED))
    }

    @Test
    fun `only an open proposal can be answered from this phone`() {
        assertTrue(DeclarationFlow.canAnswer(DeclarationState.OPEN))
        assertFalse(DeclarationFlow.canAnswer(DeclarationState.WITHDRAWN))
        assertFalse(DeclarationFlow.canAnswer(DeclarationState.CLOSED))
        assertFalse(DeclarationFlow.canAnswer(DeclarationState.ACCEPTED))
    }

    @Test
    fun `both phones reach the same state whichever way the messages cross`() {
        // Proposer withdraws while the recipient accepts. Each side sees its own action first.
        val proposer = listOf(DeclarationEvent.WITHDRAWN, DeclarationEvent.ACCEPTED)
            .fold(DeclarationState.OPEN) { state, event -> DeclarationFlow.next(state, event) ?: state }
        val recipient = listOf(DeclarationEvent.ACCEPTED, DeclarationEvent.WITHDRAWN)
            .fold(DeclarationState.OPEN) { state, event -> DeclarationFlow.next(state, event) ?: state }
        assertEquals(DeclarationState.ACCEPTED, proposer)
        assertEquals(proposer, recipient)
    }
}
