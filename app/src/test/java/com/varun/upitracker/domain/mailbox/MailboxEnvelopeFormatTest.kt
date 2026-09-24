package com.varun.upitracker.domain.mailbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The layout decides what gets signed and what a ciphertext is bound to, so every field has to count. */
class MailboxEnvelopeFormatTest {

    private val envelope = MailboxEnvelope(
        kind = MailboxKind.PARCEL,
        messageId = MailboxIds.newRandomId(),
        senderUid = "aliceUid00000000000000000000",
        recipientUid = "bobUid0000000000000000000000",
        createdEpoch = 1_700_000_000_000L,
        body = "V|2|0\nChai 🍵"
    )

    @Test
    fun `fields read back exactly`() {
        assertEquals(envelope, MailboxEnvelopeFormat.parseFields(MailboxEnvelopeFormat.fields(envelope)))
    }

    @Test
    fun `every field is part of what gets signed`() {
        val original = MailboxEnvelopeFormat.fields(envelope)
        listOf(
            envelope.copy(kind = MailboxKind.UNLINK),
            envelope.copy(messageId = MailboxIds.newRandomId()),
            envelope.copy(senderUid = "malloryUid"),
            envelope.copy(recipientUid = "charlieUid"),
            envelope.copy(createdEpoch = envelope.createdEpoch + 1),
            envelope.copy(body = envelope.body + " ")
        ).forEach { changed ->
            assertFalse(changed.toString(), original.contentEquals(MailboxEnvelopeFormat.fields(changed)))
        }
    }

    @Test
    fun `truncated, extended or foreign bytes are refused`() {
        val bytes = MailboxEnvelopeFormat.fields(envelope)
        assertNull(MailboxEnvelopeFormat.parseFields(bytes.copyOf(bytes.size - 1)))
        assertNull(MailboxEnvelopeFormat.parseFields(bytes + byteArrayOf(0)))
        assertNull(MailboxEnvelopeFormat.parseFields(ByteArray(0)))
        assertNull(MailboxEnvelopeFormat.parseFields("not an envelope at all".toByteArray()))
    }

    @Test
    fun `an id that is not one is refused even when well framed`() {
        assertNull(MailboxEnvelopeFormat.parseFields(MailboxEnvelopeFormat.fields(envelope.copy(senderUid = "not a uid"))))
        assertNull(MailboxEnvelopeFormat.parseFields(MailboxEnvelopeFormat.fields(envelope.copy(messageId = "short"))))
    }

    @Test
    fun `context info binds sender, recipient and message id, in that order`() {
        val base = MailboxEnvelopeFormat.contextInfo("a", "b", "m")
        assertFalse(base.contentEquals(MailboxEnvelopeFormat.contextInfo("x", "b", "m")))
        assertFalse(base.contentEquals(MailboxEnvelopeFormat.contextInfo("a", "x", "m")))
        assertFalse(base.contentEquals(MailboxEnvelopeFormat.contextInfo("a", "b", "x")))
        assertFalse(base.contentEquals(MailboxEnvelopeFormat.contextInfo("b", "a", "m")))
    }

    @Test
    fun `packing keeps the fields and the signature apart`() {
        val fields = MailboxEnvelopeFormat.fields(envelope)
        val signature = ByteArray(64) { it.toByte() }
        val packed = MailboxEnvelopeFormat.pack(fields, signature)

        val unpacked = MailboxEnvelopeFormat.unpack(packed)
        assertNotNull(unpacked)
        assertArrayEquals(fields, unpacked!!.first)
        assertArrayEquals(signature, unpacked.second)

        assertNull(MailboxEnvelopeFormat.unpack(packed.copyOf(packed.size - 1)))
        assertNull(MailboxEnvelopeFormat.unpack(packed + byteArrayOf(0)))
        assertNull(MailboxEnvelopeFormat.unpack(MailboxEnvelopeFormat.pack(fields, ByteArray(0))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a body past the cap is refused before it is written`() {
        MailboxEnvelopeFormat.fields(envelope.copy(body = "x".repeat(MailboxEnvelopeFormat.MAX_BODY_BYTES + 1)))
    }
}
