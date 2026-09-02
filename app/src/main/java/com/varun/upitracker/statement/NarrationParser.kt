package com.varun.upitracker.statement

/**
 * Splits an HDFC statement narration into its parts.
 *
 * UPI rows follow `UPI-<raw_name>-<upi_id>-<ifsc>-<ref_id>-<notes>`, but splitting positionally on
 * `-` does not work: VPAs routinely contain hyphens (`PAYTM-69905863@PTYBL`, `9023631695-2@YBL`,
 * `GPAY-12189961159@OKBIZAXIS`) and so do notes. So we anchor on the ref id — which the
 * `Chq./Ref.No.` column hands us independently — and read outwards from there.
 *
 * Everything that does not fit (`MONTHLY INTEREST CREDIT ...`, `ME DC SI ... ANTHROPIC* CLAUDE SUB`,
 * `UPIRET-...`, `...-TPT-...`) comes back as a non-UPI result with the narration untouched.
 */
object NarrationParser {

    private const val UPI_PREFIX = "UPI-"
    private val DIGITS_ONLY = Regex("^[0-9]+$")
    private val REF_ID_LENGTHS = 10..16

    data class ParsedNarration(
        val rawName: String?,
        val upiId: String?,
        val ifsc: String?,
        val upiRefId: String?,
        val notes: String?
    ) {
        val isUpi: Boolean get() = upiRefId != null

        companion object {
            val NON_UPI = ParsedNarration(null, null, null, null, null)
        }
    }

    /**
     * The `Chq./Ref.No.` cell as it should be stored: null when blank, or when it is the all-zero
     * filler HDFC writes for rows that have no reference of their own.
     */
    fun normaliseRefNo(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it == '0' }) return null
        return trimmed
    }

    /** The UPI RRN hiding inside a zero-padded ref cell (`0000126864903466` -> `126864903466`). */
    fun upiRefIdFromRefNo(refNo: String?): String? {
        val trimmed = refNo?.trim().orEmpty()
        if (!DIGITS_ONLY.matches(trimmed)) return null
        return trimmed.trimStart('0').takeIf { it.length in REF_ID_LENGTHS }
    }

    fun parse(narration: String, refNoCell: String?): ParsedNarration {
        val text = narration.trim()
        if (!text.startsWith(UPI_PREFIX)) return ParsedNarration.NON_UPI

        val segments = text.split('-')
        // Shortest legal UPI narration is UPI + name + vpa + ifsc + ref.
        if (segments.size < 5) return ParsedNarration.NON_UPI

        val refIndex = locateRefIndex(segments, upiRefIdFromRefNo(refNoCell))
        if (refIndex < 3) return ParsedNarration.NON_UPI

        val notes = segments.subList(refIndex + 1, segments.size)
            .joinToString("-")
            .trim()
            .takeIf { it.isNotEmpty() }
        val ifsc = segments[refIndex - 1].trim().takeIf { it.isNotEmpty() }
        val upiId = segments.subList(2, refIndex - 1).joinToString("-").trim()

        // A VPA always has an `@`. Without one we mis-split, so fold everything before the IFSC
        // back into the name rather than inventing a bogus UPI id.
        return if (upiId.contains('@')) {
            ParsedNarration(
                rawName = segments[1].trim().takeIf { it.isNotEmpty() },
                upiId = upiId,
                ifsc = ifsc,
                upiRefId = segments[refIndex],
                notes = notes
            )
        } else {
            ParsedNarration(
                rawName = segments.subList(1, refIndex - 1).joinToString("-").trim()
                    .takeIf { it.isNotEmpty() },
                upiId = null,
                ifsc = ifsc,
                upiRefId = segments[refIndex],
                notes = notes
            )
        }
    }

    /**
     * Prefer the ref id the statement gave us in its own column; fall back to the last
     * ref-id-shaped segment when that column is unusable.
     */
    private fun locateRefIndex(segments: List<String>, refFromCell: String?): Int {
        if (refFromCell != null) {
            for (index in 3 until segments.size) {
                if (segments[index] == refFromCell) return index
            }
        }
        for (index in segments.indices.reversed()) {
            if (index < 3) break
            val segment = segments[index]
            if (DIGITS_ONLY.matches(segment) && segment.length in REF_ID_LENGTHS) return index
        }
        return -1
    }
}
