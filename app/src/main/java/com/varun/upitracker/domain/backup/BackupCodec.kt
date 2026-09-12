package com.varun.upitracker.domain.backup

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What actually gets written to Drive: `DHMB` + version + CRC32 + DEFLATE(text).
 *
 * Raw bytes rather than the base64 envelope
 * [com.varun.upitracker.domain.parcel.ParcelCodec] uses. A parcel has to survive being pasted into a
 * chat message; a backup is a file, and base64 would cost a third of the payload for nothing --
 * which matters against Drive's 5 MB single-request upload ceiling.
 *
 * The checksum is not ceremony. A backup is read back months later, possibly after a partial
 * download, and [Inflater] is not guaranteed to notice corruption. A silently mangled `amountPaise`
 * is the worst thing this feature could do, so the CRC is verified over the compressed bytes before
 * anything is decompressed or parsed.
 *
 * Uses `java.util.zip` and nothing from `android.*`, which is what keeps this object covered by the
 * project's plain-JUnit tests.
 */
object BackupCodec {

    /** `D`, `H`, `M`, `B`. Enough to reject a file that is not ours before doing any work. */
    private val MAGIC = byteArrayOf(0x44, 0x48, 0x4D, 0x42)

    private const val FORMAT_VERSION = 1

    /** MAGIC + version + CRC32. */
    private const val HEADER_BYTES = 4 + 1 + 4

    /**
     * A ceiling on what a corrupt or hostile payload can be inflated into. Generous next to a real
     * dump -- this database is thousands of short rows, not media -- and small enough that nothing
     * can be expanded into an allocation that kills the app.
     */
    private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024

    fun encode(plaintext: String): ByteArray {
        val compressed = deflate(plaintext.toByteArray(Charsets.UTF_8))
        val checksum = CRC32().apply { update(compressed) }.value

        val out = ByteArrayOutputStream(HEADER_BYTES + compressed.size)
        out.write(MAGIC)
        out.write(FORMAT_VERSION)
        // Big-endian, written by hand rather than through ByteBuffer so the layout is visible here.
        out.write((checksum ushr 24 and 0xFF).toInt())
        out.write((checksum ushr 16 and 0xFF).toInt())
        out.write((checksum ushr 8 and 0xFF).toInt())
        out.write((checksum and 0xFF).toInt())
        out.write(compressed)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BackupParseResult {
        if (bytes.size < HEADER_BYTES) {
            return BackupParseResult.Failed("This file is too small to be a backup.")
        }
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) {
            return BackupParseResult.Failed("This file is not a DhanMoney backup.")
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            return BackupParseResult.Failed(
                "This backup was written by a newer version of the app. Update, then try again."
            )
        }

        val expected = ((bytes[5].toLong() and 0xFF) shl 24) or
            ((bytes[6].toLong() and 0xFF) shl 16) or
            ((bytes[7].toLong() and 0xFF) shl 8) or
            (bytes[8].toLong() and 0xFF)

        val compressed = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        if (CRC32().apply { update(compressed) }.value != expected) {
            return BackupParseResult.Failed(
                "This backup is damaged, most likely an incomplete download. Try again."
            )
        }

        val plaintext = inflate(compressed)
            ?: return BackupParseResult.Failed("This backup is damaged and could not be read.")
        return BackupFormat.parse(plaintext)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 4 + 64)
            val buffer = ByteArray(16 * 1024)
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
            val out = ByteArrayOutputStream(input.size * 4)
            val buffer = ByteArray(16 * 1024)
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
