package com.varun.upitracker.domain.mailbox

import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelCodec
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.PasteFraming
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An invite travels like a parcel, so it has to survive a chat app like one, and refuse the same damage. */
class InviteCodeTest {

    private val invite = LinkInviteCode(
        inviteId = MailboxIds.newRandomId(),
        secret = MailboxIds.newRandomId(),
        ownerUid = "aliceUid00000000000000000000",
        ownerFingerprint = "0123456789abcdef0123456789abcdef",
        nameHint = "Alice Sharma",
        expiresEpoch = 1_700_000_000_000L
    )

    private val bobFingerprint = "fedcba98765432100123456789abcdef"

    private fun failureOf(text: String): String {
        val result = InviteCode.decode(text)
        assertTrue("expected Failed, got $result", result is InviteDecodeResult.Failed)
        return (result as InviteDecodeResult.Failed).reason
    }

    /** A correctly framed invite whose inside is whatever [fill] writes. */
    private fun framed(fill: DataOutputStream.() -> Unit): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { it.fill() }
        return PasteFraming.frame(InviteCode.PREFIX, out.toByteArray())
    }

    @Test
    fun `an invite survives being wrapped by a chat app`() {
        val wrapped = "  " + InviteCode.encode(invite).chunked(17).joinToString("\n") + "\n"
        assertEquals(InviteDecodeResult.Ok(invite), InviteCode.decode(wrapped))
    }

    @Test
    fun `an overlong name hint is cut rather than refused`() {
        val decoded = InviteCode.decode(InviteCode.encode(invite.copy(nameHint = "x".repeat(200))))
        assertEquals(InviteCode.MAX_NAME_HINT, (decoded as InviteDecodeResult.Ok).invite.nameHint.length)
    }

    @Test
    fun `a damaged invite is refused`() {
        val encoded = InviteCode.encode(invite)
        val body = encoded.substringAfterLast('.')
        val flipped = if (body[6] == 'A') 'B' else 'A'
        val corrupted = encoded.substringBeforeLast('.') + "." + body.substring(0, 6) + flipped + body.substring(7)
        assertTrue(failureOf(corrupted).contains("damaged"))
        assertTrue(failureOf(encoded.dropLast(5)).isNotEmpty())
    }

    @Test
    fun `a parcel is not an invite, and an invite from a newer app says so`() {
        val parcel = ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "tok3n", emptyList()))
        assertTrue(failureOf(parcel).contains("does not look like"))
        val future = "DHANLINK2" + InviteCode.encode(invite).removePrefix(InviteCode.PREFIX)
        assertTrue(failureOf(future).contains("newer version"))
    }

    @Test
    fun `fields that are not what they claim to be are refused`() {
        fun withFields(
            inviteId: String = invite.inviteId,
            uid: String = invite.ownerUid,
            fingerprint: String = invite.ownerFingerprint,
            name: String = invite.nameHint,
            expires: Long = invite.expiresEpoch
        ) = framed {
            writeUTF(inviteId)
            writeUTF(invite.secret)
            writeUTF(uid)
            writeUTF(fingerprint)
            writeUTF(name)
            writeLong(expires)
        }

        assertTrue(InviteCode.decode(withFields()) is InviteDecodeResult.Ok)
        listOf(
            withFields(inviteId = "short"),
            withFields(uid = "not a uid"),
            withFields(fingerprint = "NOT-HEX"),
            withFields(name = " "),
            withFields(expires = 0L)
        ).forEach { text -> assertTrue(text, InviteCode.decode(text) is InviteDecodeResult.Failed) }
    }

    @Test
    fun `trailing bytes are refused`() {
        val text = framed {
            writeUTF(invite.inviteId)
            writeUTF(invite.secret)
            writeUTF(invite.ownerUid)
            writeUTF(invite.ownerFingerprint)
            writeUTF(invite.nameHint)
            writeLong(invite.expiresEpoch)
            writeByte(1)
        }
        assertTrue(InviteCode.decode(text) is InviteDecodeResult.Failed)
    }

    @Test
    fun `a shared link carries the code where no server sees it`() {
        val code = InviteCode.encode(invite)
        val link = InviteCode.link("dhanmoney.web.app", code)

        // Everything the server is asked for is the path; the code is past the '#', which browsers
        // and link previews keep to themselves.
        assertEquals("https://dhanmoney.web.app/link", link.substringBefore('#'))
        assertEquals(code, InviteCode.codeFrom(link))
        assertEquals(InviteDecodeResult.Ok(invite), InviteCode.decode(InviteCode.codeFrom(link)!!))
    }

    @Test
    fun `a code is found whether it was tapped, pasted as a link, or pasted bare`() {
        val code = InviteCode.encode(invite)

        assertEquals(code, InviteCode.codeFrom(code))
        assertEquals(code, InviteCode.codeFrom("  " + code + "\n"))
        assertEquals(code, InviteCode.codeFrom("dhanmoney://link#" + code))
        assertEquals(code, InviteCode.codeFrom("https://dhanmoney.web.app/link#" + code))
    }

    @Test
    fun `a link with nothing to link with is refused`() {
        assertNull(InviteCode.codeFrom("https://dhanmoney.web.app/link"))
        assertNull(InviteCode.codeFrom("https://dhanmoney.web.app/link#notacode"))
        assertNull(InviteCode.codeFrom(""))
        // A parcel is not an invite, however it arrives.
        val parcel = ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "token1234", emptyList()))
        assertNull(InviteCode.codeFrom("https://dhanmoney.web.app/link#" + parcel))
    }

    @Test
    fun `the proof covers the secret and everything the reply asks to be trusted`() {
        val proof = InviteCode.proof(invite.secret, invite.inviteId, "bobUid", bobFingerprint)
        assertTrue(
            InviteCode.proofMatches(proof, InviteCode.proof(invite.secret, invite.inviteId, "bobUid", bobFingerprint))
        )
        listOf(
            InviteCode.proof(MailboxIds.newRandomId(), invite.inviteId, "bobUid", bobFingerprint),
            InviteCode.proof(invite.secret, MailboxIds.newRandomId(), "bobUid", bobFingerprint),
            InviteCode.proof(invite.secret, invite.inviteId, "malloryUid", bobFingerprint),
            InviteCode.proof(invite.secret, invite.inviteId, "bobUid", "00000000000000000000000000000000")
        ).forEach { other -> assertFalse(InviteCode.proofMatches(proof, other)) }
    }

    @Test
    fun `an invite expires at its expiry time, not after it`() {
        assertFalse(InviteCode.isExpired(invite, invite.expiresEpoch - 1))
        assertTrue(InviteCode.isExpired(invite, invite.expiresEpoch))
    }
}
