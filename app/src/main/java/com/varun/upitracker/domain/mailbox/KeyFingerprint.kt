package com.varun.upitracker.domain.mailbox

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A short, stable name for someone's pair of public keys: what an invite code carries, what a link
 * pins, and what two people can read out to each other.
 *
 * Taken over the published bytes exactly as stored in `/users/{uid}`, never over keys re-serialised
 * here, so the same keys cannot fingerprint differently on two phones.
 */
object KeyFingerprint {

    /** 128 bits: far past forging by chance, and short enough to show. */
    private const val LENGTH_HEX = 32

    fun of(encryptionPublicKeyset: ByteArray, signingPublicKeyset: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-prefixed, so the boundary between the two keys cannot be slid along.
        digest.update(ByteBuffer.allocate(4).putInt(encryptionPublicKeyset.size).array())
        digest.update(encryptionPublicKeyset)
        digest.update(ByteBuffer.allocate(4).putInt(signingPublicKeyset.size).array())
        digest.update(signingPublicKeyset)
        return digest.digest()
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .take(LENGTH_HEX)
    }

    /** `1a2b 3c4d …` -- eight groups of four a person can compare out loud. */
    fun display(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
}
