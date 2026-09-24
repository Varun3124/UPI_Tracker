package com.varun.upitracker.domain.mailbox

import java.security.SecureRandom
import java.util.Base64

/**
 * The shapes of the identifiers the mailbox passes around, checked wherever one arrives from outside.
 *
 * A uid or an id is about to become part of a Firestore path, a database key or a signed field, so
 * anything that does not look exactly like one is refused rather than escaped.
 */
object MailboxIds {

    /** Firebase Auth uids: alphanumeric, at most 128 characters. */
    private val UID = Regex("[A-Za-z0-9_-]{1,128}")

    /** Ids this app mints: 128 random bits in unpadded base64url, which is always 22 characters. */
    private val RANDOM_ID = Regex("[A-Za-z0-9_-]{22}")

    private const val RANDOM_BYTES = 16

    private val random = SecureRandom()

    fun isUid(value: String): Boolean = UID.matches(value)

    fun isRandomId(value: String): Boolean = RANDOM_ID.matches(value)

    /**
     * Message ids, invite ids, invite secrets and share references alike. 128 bits is past any
     * chance of two colliding, and unguessable where the id doubles as a capability.
     */
    fun newRandomId(): String {
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
