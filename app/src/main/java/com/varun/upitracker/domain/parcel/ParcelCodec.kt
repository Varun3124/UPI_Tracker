package com.varun.upitracker.domain.parcel

import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What actually travels between two phones: `UPIX1.<crc32>.<payload>`.
 *
 * The three parts are separated by `.`, which base64url's alphabet excludes, so the split is
 * unambiguous. base64url rather than plain base64 for the same reason it exists: `+` and `/` get
 * mangled by chat apps and URL shorteners, `-` and `_` do not.
 *
 * The checksum is not ceremony. This travels through WhatsApp, which wraps long tokens, and gets
 * copied out of quoted messages, which truncates them. [Inflater] usually throws on corruption but
 * is not guaranteed to, and a silently corrupted amount is the worst thing this feature could do.
 * CRC32 over the compressed bytes catches it before anything is parsed.
 *
 * Uses `java.util.Base64` and `java.util.zip`, never `android.util.Base64`: those are real classes
 * on the JVM, which is what keeps this object covered by plain JUnit like the rest of `domain/`.
 * The android one is a stub in unit tests and throws "not mocked".
 */
object ParcelCodec {

    const val PREFIX = "UPIX1"

    /**
     * A parcel is a chat message; nothing legitimate comes close to this. The cap exists so a
     * hostile or corrupt payload cannot be inflated into an allocation big enough to kill the app.
     */
    private const val MAX_INFLATED_BYTES = 256 * 1024

    private const val SEPARATOR = '.'

    fun encode(parcel: Parcel): String {
        val plaintext = ParcelFormat.format(parcel).toByteArray(Charsets.UTF_8)
        val compressed = deflate(plaintext)
        val checksum = CRC32().apply { update(compressed) }.value
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return PREFIX + SEPARATOR + checksum.toString(36) + SEPARATOR + payload
    }

    fun decode(text: String): ParcelDecodeResult {
        // Chat apps and terminals insert line breaks into long tokens, and a pasted parcel often
        // arrives with a stray leading space. None of it is meaningful here.
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ParcelDecodeResult.Failed("There is nothing here to import.")

        val parts = cleaned.split(SEPARATOR)
        if (parts.size != 3) {
            return ParcelDecodeResult.Failed("That does not look like a shared parcel.")
        }
        if (parts[0] != PREFIX) {
            return ParcelDecodeResult.Failed(
                if (parts[0].startsWith("UPIX")) {
                    "This parcel was made by a newer version of the app. Update, then try again."
                } else {
                    "That does not look like a shared parcel."
                }
            )
        }
        val expectedChecksum = parts[1].toLongOrNull(36)
            ?: return ParcelDecodeResult.Failed("This parcel is damaged. Ask for it to be sent again.")

        val compressed = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (error: IllegalArgumentException) {
            return ParcelDecodeResult.Failed(
                "This parcel is damaged, most likely cut short when it was copied. " +
                    "Ask for it to be sent again."
            )
        }
        if (CRC32().apply { update(compressed) }.value != expectedChecksum) {
            return ParcelDecodeResult.Failed(
                "This parcel is damaged, most likely cut short when it was copied. " +
                    "Ask for it to be sent again."
            )
        }

        val plaintext = inflate(compressed)
            ?: return ParcelDecodeResult.Failed("This parcel is damaged. Ask for it to be sent again.")
        return ParcelFormat.parse(plaintext)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(input.size / 2 + 32)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): String? {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = java.io.ByteArrayOutputStream(input.size * 3)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val written = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    return null
                }
                // A stream that stops needing input without finishing is truncated, and looping on
                // it would spin forever writing nothing.
                if (written == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, written)
                if (out.size() > MAX_INFLATED_BYTES) return null
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }
}
