package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.mailbox.MailboxIds
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The two bodies a declaration travels in, and everything they refuse. */
class DeclarationMessagesTest {

    private val id = MailboxIds.newRandomId()
    private val target = MailboxIds.newRandomId()
    private val share = MailboxIds.newRandomId()

    private val declare = DeclarationProposal(
        id = id, kind = DeclarationKind.DECLARE, targetId = null, asOfEpoch = 1_000L, amountPaise = -120_000L,
        deltaPaise = null, proposedEpoch = 1_000L, auto = true, note = "Before the trip",
        parts = listOf(ProposalPart(share, -30_000L))
    )

    @Test
    fun `a proposal survives the round trip`() {
        assertEquals(declare, DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(declare)))
    }

    @Test
    fun `an amendment and a revocation survive the round trip`() {
        val amend = declare.copy(kind = DeclarationKind.AMEND, targetId = target, deltaPaise = 40_000L, parts = emptyList(), note = null)
        val revoke = declare.copy(kind = DeclarationKind.REVOKE, targetId = target, asOfEpoch = null, amountPaise = null, parts = emptyList())
        assertEquals(amend, DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(amend)))
        assertEquals(revoke, DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(revoke)))
    }

    @Test
    fun `an answer survives the round trip`() {
        DeclarationVerdict.entries.forEach { verdict ->
            val answer = DeclarationAnswer(id, verdict, "My book says 1,150")
            assertEquals(answer, DeclarationMessages.decodeAnswer(DeclarationMessages.encodeAnswer(answer)))
        }
        val bare = DeclarationAnswer(id, DeclarationVerdict.ACCEPT)
        assertEquals(bare, DeclarationMessages.decodeAnswer(DeclarationMessages.encodeAnswer(bare)))
    }

    @Test
    fun `each kind must have its own shape`() {
        val refused = listOf(
            declare.copy(targetId = target),                        // a DECLARE replaces nothing
            declare.copy(amountPaise = null),                       // and names an amount
            declare.copy(asOfEpoch = null),                         // and an instant
            declare.copy(deltaPaise = 5L),                          // and adds nothing
            declare.copy(kind = DeclarationKind.AMEND),             // an AMEND names its target
            declare.copy(kind = DeclarationKind.AMEND, targetId = target, deltaPaise = 1L), // and carries no parts
            declare.copy(kind = DeclarationKind.REVOKE, targetId = target, parts = emptyList()), // a REVOKE names no amount
            declare.copy(kind = DeclarationKind.REVOKE, targetId = id, asOfEpoch = null, amountPaise = null, parts = emptyList()), // nor itself
            declare.copy(kind = "SETTLE"),
            declare.copy(id = "not-an-id"),
            declare.copy(parts = listOf(ProposalPart(share, 1L), ProposalPart(share, 2L))), // one part per chapter
            declare.copy(parts = listOf(ProposalPart("x", 1L))),
            declare.copy(note = "x".repeat(DeclarationMessages.MAX_NOTE_CHARS + 1)),
            declare.copy(note = "   ")
        )
        refused.forEachIndexed { index, proposal ->
            assertNull("case $index", DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(proposal)))
        }
    }

    @Test
    fun `anything that is not exactly a proposal is refused`() {
        val body = DeclarationMessages.encodeProposal(declare)
        val bytes = Base64.getUrlDecoder().decode(body)
        val trailing = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes + byteArrayOf(0))
        assertNull(DeclarationMessages.decodeProposal(trailing))
        assertNull(DeclarationMessages.decodeProposal(body.dropLast(4)))
        assertNull(DeclarationMessages.decodeProposal("!!not base64!!"))
        assertNull("an answer is not a proposal", DeclarationMessages.decodeProposal(DeclarationMessages.encodeAnswer(DeclarationAnswer(id, DeclarationVerdict.ACCEPT))))
        assertNull("a proposal is not an answer", DeclarationMessages.decodeAnswer(body))
    }

    @Test
    fun `an answer with an unknown verdict is refused`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF("DHA1")
            it.writeUTF(id)
            it.writeUTF("MAYBE")
            it.writeUTF("")
        }
        assertNull(DeclarationMessages.decodeAnswer(Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())))
    }

    @Test
    fun `an outgoing proposal is written from the reader's seat`() {
        val row = IouDeclaration(
            id = id, friendId = 7L, kind = DeclarationKind.DECLARE, asOfEpoch = 1_000L, amountPaise = 120_000L,
            proposedByMe = true, proposedEpoch = 1_000L, state = DeclarationState.OPEN
        )
        val outgoing = DeclarationMessages.outgoing(row, listOf(ProposalPart(share, 30_000L)))
        assertEquals(-120_000L, outgoing.amountPaise)
        assertEquals(listOf(ProposalPart(share, -30_000L)), outgoing.parts)
        assertNotNull(DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(outgoing)))
    }

    @Test
    fun `an amendment never carries parts, whatever it is handed`() {
        val row = IouDeclaration(
            id = id, friendId = 7L, kind = DeclarationKind.AMEND, targetId = target, asOfEpoch = 1_000L,
            amountPaise = 160_000L, deltaPaise = 40_000L, proposedByMe = true, proposedEpoch = 2_000L,
            state = DeclarationState.OPEN
        )
        val outgoing = DeclarationMessages.outgoing(row, listOf(ProposalPart(share, 30_000L)))
        assertEquals(emptyList<ProposalPart>(), outgoing.parts)
        assertEquals(-40_000L, outgoing.deltaPaise)
    }

    @Test
    fun `a note is trimmed, capped and dropped when empty`() {
        assertEquals("Hi", DeclarationMessages.cleanNote("  Hi  "))
        assertNull(DeclarationMessages.cleanNote("   "))
        assertNull(DeclarationMessages.cleanNote(null))
        assertEquals(DeclarationMessages.MAX_NOTE_CHARS, DeclarationMessages.cleanNote("x".repeat(1_000))!!.length)
    }
}
