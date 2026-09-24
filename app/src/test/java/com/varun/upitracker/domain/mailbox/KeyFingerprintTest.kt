package com.varun.upitracker.domain.mailbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFingerprintTest {

    private val encryption = "encryption-keyset".toByteArray()
    private val signing = "signing-keyset".toByteArray()

    @Test
    fun `the same keys always give the same fingerprint`() {
        assertEquals(
            KeyFingerprint.of(encryption, signing),
            KeyFingerprint.of(encryption.copyOf(), signing.copyOf())
        )
    }

    @Test
    fun `swapping the two keys changes it`() {
        assertNotEquals(KeyFingerprint.of(encryption, signing), KeyFingerprint.of(signing, encryption))
    }

    @Test
    fun `the boundary between the keys cannot be slid along`() {
        assertNotEquals(
            KeyFingerprint.of("ab".toByteArray(), "c".toByteArray()),
            KeyFingerprint.of("a".toByteArray(), "bc".toByteArray())
        )
    }

    @Test
    fun `it is 32 lowercase hex characters, shown in eight groups of four`() {
        val fingerprint = KeyFingerprint.of(encryption, signing)
        assertTrue(fingerprint, Regex("[0-9a-f]{32}").matches(fingerprint))
        val shown = KeyFingerprint.display(fingerprint)
        assertEquals(8, shown.split(" ").size)
        assertEquals(fingerprint, shown.replace(" ", ""))
    }
}
