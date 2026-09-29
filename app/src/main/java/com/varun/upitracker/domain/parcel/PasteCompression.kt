package com.varun.upitracker.domain.parcel

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * DEFLATE for everything pasted between phones: parcels, and static copies of chapters.
 *
 * [Inflater] usually throws on corruption but is not guaranteed to, and a silently corrupted amount is
 * the worst thing either could do -- which is why every caller checks [PasteFraming]'s checksum before
 * anything is inflated, and why [inflate] stops at a cap, so a hostile or corrupt payload cannot be
 * inflated into an allocation big enough to kill the app.
 */
object PasteCompression {

    fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 2 + 32)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Null when [input] is not one whole DEFLATE stream, or would inflate past [maxBytes]. */
    fun inflate(input: ByteArray, maxBytes: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 3)
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
                if (out.size() > maxBytes) return null
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
