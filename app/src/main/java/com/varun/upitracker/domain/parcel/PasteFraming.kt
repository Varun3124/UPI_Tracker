package com.varun.upitracker.domain.parcel

import java.util.Base64
import java.util.zip.CRC32

/**
 * The outside of everything this app asks people to paste: `<PREFIX>.<crc32 base36>.<payload>`.
 *
 * Parcels and link invites both travel through chat apps, which wrap long tokens, add stray spaces
 * and cut quoted copies short. Both need the same three defences -- whitespace ignored, a body that
 * survives the trip, and a checksum that catches a damaged copy before anything reads it -- so they
 * are written once, here, where the two cannot drift apart.
 *
 * The three parts are separated by `.`, which base64url's alphabet excludes, so the split is
 * unambiguous. base64url rather than plain base64 for the same reason it exists: `+` and `/` get
 * mangled by chat apps and URL shorteners, `-` and `_` do not.
 *
 * The checksum is not ceremony. A silently corrupted amount, or a silently corrupted invite that
 * links the wrong account, is the worst thing either could do. CRC32 over the payload catches the
 * damage before anything is parsed.
 *
 * Uses `java.util.Base64` and `java.util.zip`, never `android.util.Base64`: those are real classes
 * on the JVM, which is what keeps this covered by plain JUnit like the rest of `domain/`. The
 * android one is a stub in unit tests and throws "not mocked".
 */
object PasteFraming {

    private const val SEPARATOR = '.'

    sealed interface Result {
        class Ok(val payload: ByteArray) : Result

        /** Nothing but whitespace. */
        data object Empty : Result

        /** Not three parts, or a prefix from another family entirely. */
        data object Foreign : Result

        /** The right family under another number -- almost always made by a newer app. */
        data object OtherVersion : Result

        /** The checksum field itself cannot be read. */
        data object BadChecksum : Result

        /** Not valid base64url, or a checksum that disagrees: cut short or altered on the way. */
        data object Corrupted : Result
    }

    fun frame(prefix: String, payload: ByteArray): String {
        val checksum = CRC32().apply { update(payload) }.value
        return prefix + SEPARATOR + checksum.toString(36) + SEPARATOR +
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    /** The prefix [text] opens with, whitespace ignored, or null when there is none. */
    fun prefixOf(text: String): String? {
        val cleaned = text.filterNot { it.isWhitespace() }
        val end = cleaned.indexOf(SEPARATOR)
        return if (end > 0) cleaned.substring(0, end) else null
    }

    /**
     * [family] is [prefix] without its version number -- `UPIX` for `UPIX1` -- so a token from the
     * same family under another number reads as [Result.OtherVersion] rather than as garbage.
     */
    fun unframe(text: String, prefix: String, family: String): Result {
        // Chat apps and terminals insert line breaks into long tokens, and a pasted one often
        // arrives with a stray leading space. None of it is meaningful here.
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return Result.Empty

        val parts = cleaned.split(SEPARATOR)
        if (parts.size != 3) return Result.Foreign
        if (parts[0] != prefix) {
            return if (parts[0].startsWith(family)) Result.OtherVersion else Result.Foreign
        }
        val expectedChecksum = parts[1].toLongOrNull(36) ?: return Result.BadChecksum
        val payload = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (error: IllegalArgumentException) {
            return Result.Corrupted
        }
        if (CRC32().apply { update(payload) }.value != expectedChecksum) return Result.Corrupted
        return Result.Ok(payload)
    }
}
