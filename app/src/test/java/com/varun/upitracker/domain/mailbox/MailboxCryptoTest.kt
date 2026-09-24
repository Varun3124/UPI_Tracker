package com.varun.upitracker.domain.mailbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end claims, run through real Tink. Alice sends to Bob; Mallory is anyone else -- a
 * stranger, or the server itself.
 */
class MailboxCryptoTest {

    private companion object {
        // Key generation is the slow part, and three people are enough for every case below.
        val alice: MailboxKeys = MailboxKeys.generate()
        val bob: MailboxKeys = MailboxKeys.generate()
        val mallory: MailboxKeys = MailboxKeys.generate()
    }

    private val aliceUid = "aliceUid00000000000000000000"
    private val bobUid = "bobUid0000000000000000000000"
    private val malloryUid = "malloryUid000000000000000000"
    private val messageId = MailboxIds.newRandomId()

    private fun envelope(sender: String = aliceUid, recipient: String = bobUid, id: String = messageId) =
        MailboxEnvelope(MailboxKind.PARCEL, id, sender, recipient, 1_700_000_000_000L, "V|2|0\nChai 🍵")

    private fun opened(result: UnsealResult): Unsealed {
        assertTrue("expected Ok, got $result", result is UnsealResult.Ok)
        return (result as UnsealResult.Ok).unsealed
    }

    @Test
    fun `a sealed message opens for its reader and verifies against its sender`() {
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        val unsealed = opened(MailboxCrypto.unseal(sealed, bob, aliceUid, bobUid, messageId))
        assertEquals(MailboxKind.PARCEL, unsealed.kind)
        assertEquals(envelope(), unsealed.verifiedBy(alice.publicKeys))
    }

    @Test
    fun `it does not verify against anyone else's keys`() {
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        val unsealed = opened(MailboxCrypto.unseal(sealed, bob, aliceUid, bobUid, messageId))
        assertNull(unsealed.verifiedBy(mallory.publicKeys))
        assertNull(unsealed.verifiedBy(bob.publicKeys))
    }

    @Test
    fun `it only opens under the sender, reader and id it was sealed for`() {
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        assertSame(UnsealResult.WontOpen, MailboxCrypto.unseal(sealed, bob, malloryUid, bobUid, messageId))
        assertSame(UnsealResult.WontOpen, MailboxCrypto.unseal(sealed, bob, aliceUid, malloryUid, messageId))
        assertSame(UnsealResult.WontOpen, MailboxCrypto.unseal(sealed, bob, aliceUid, bobUid, MailboxIds.newRandomId()))
    }

    @Test
    fun `it does not open with anyone else's private key`() {
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        assertSame(UnsealResult.WontOpen, MailboxCrypto.unseal(sealed, mallory, aliceUid, bobUid, messageId))
    }

    @Test
    fun `a single altered byte stops it opening`() {
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        listOf(0, sealed.size / 2, sealed.size - 1).forEach { index ->
            val altered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertSame("byte $index", UnsealResult.WontOpen, MailboxCrypto.unseal(altered, bob, aliceUid, bobUid, messageId))
        }
    }

    @Test
    fun `someone writing under a friend's name is caught by the key pinned for that friend`() {
        // Mallory knows Bob's public key -- it is public -- and signs a message claiming to be Alice.
        // Even if the server lied that it came from Alice, it opens but never verifies as Alice.
        val forged = MailboxCrypto.seal(envelope(sender = aliceUid), mallory, bob.publicKeys)
        val unsealed = opened(MailboxCrypto.unseal(forged, bob, aliceUid, bobUid, messageId))
        assertNull(unsealed.verifiedBy(alice.publicKeys))
        assertNotNull(unsealed.verifiedBy(mallory.publicKeys))
    }

    @Test
    fun `an envelope that disagrees with the document it arrived in is refused`() {
        // Sealed under Alice's binding, but the fields inside name someone else as the reader.
        val fields = envelope(recipient = malloryUid)
        val sealed = run {
            val packed = MailboxEnvelopeFormat.pack(
                MailboxEnvelopeFormat.fields(fields),
                ByteArray(64) { 1 }
            )
            // Reach the cipher the way seal does, binding to Bob, so only the inside disagrees.
            bob.publicKeys.hybridEncrypt().encrypt(packed, MailboxEnvelopeFormat.contextInfo(aliceUid, bobUid, messageId))
        }
        assertSame(UnsealResult.Malformed, MailboxCrypto.unseal(sealed, bob, aliceUid, bobUid, messageId))
    }

    @Test
    fun `private keys survive being stored and read back`() {
        val restored = MailboxKeys.parsePrivate(bob.serializePrivate())
        assertNotNull(restored)
        assertEquals(bob.fingerprint, restored!!.fingerprint)
        val sealed = MailboxCrypto.seal(envelope(), alice, bob.publicKeys)
        assertEquals(envelope(), opened(MailboxCrypto.unseal(sealed, restored, aliceUid, bobUid, messageId)).verifiedBy(alice.publicKeys))
    }

    @Test
    fun `published keys survive being written as text and read back`() {
        val published = alice.publicKeys
        val readBack = PublicMailboxKeys.fromText(published.encryptionKeyText, published.signingKeyText)
        assertNotNull(readBack)
        assertEquals(published.fingerprint, readBack!!.fingerprint)
        val sealed = MailboxCrypto.seal(envelope(sender = bobUid, recipient = aliceUid), bob, readBack)
        assertNotNull(opened(MailboxCrypto.unseal(sealed, alice, bobUid, aliceUid, messageId)).verifiedBy(bob.publicKeys))
    }

    @Test
    fun `garbage is not a key`() {
        assertNull(MailboxKeys.parsePrivate(ByteArray(0)))
        assertNull(MailboxKeys.parsePrivate("DHMK1 but not really".toByteArray()))
        assertNull(PublicMailboxKeys.fromText("not-base64!", alice.publicKeys.signingKeyText))
        assertNull(PublicMailboxKeys.fromText("AAAA", "AAAA"))
        // The two keys the wrong way round: an encryption key cannot verify, nor a signing key seal.
        assertNull(PublicMailboxKeys.fromText(alice.publicKeys.signingKeyText, alice.publicKeys.encryptionKeyText))
    }
}
