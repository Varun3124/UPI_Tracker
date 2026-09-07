package com.varun.upitracker.notification.parser

import com.varun.upitracker.parser.ParsedTransaction

object NotificationParser {

    const val GMAIL_PACKAGE = "com.google.android.gm"

    // Axis Bank alert forwarded through Gmail — "INR 1.00 was debited from your A/c no. XX1591.
    // ... Transaction Info:\nUPI/P2A/625013838137/VARUN IYER ... Regards,\nAxis Bank Ltd."
    private val AXIS_AMOUNT_DIRECTION = Regex(
        """INR\s+(\d+(?:\.\d+)?)\s+was\s+(debited|credited)""",
        RegexOption.IGNORE_CASE
    )
    private val AXIS_REF_PAYEE = Regex("""UPI/[^/]+/(\d+)/(.+)""")

    /**
     * Returns a ParsedTransaction if [text] (a notification's title + bigText, concatenated) is a
     * recognized Axis Bank UPI alert forwarded through Gmail, null otherwise. HDFC-via-Gmail
     * parsing isn't implemented yet — no confirmed sample to match against.
     */
    fun parse(packageName: String, text: String, timestamp: Long): ParsedTransaction? {
        if (packageName != GMAIL_PACKAGE) return null
        if (!text.contains("Axis Bank", ignoreCase = true)) return null

        val amountDirection = AXIS_AMOUNT_DIRECTION.find(text) ?: return null
        val refPayee = AXIS_REF_PAYEE.find(text) ?: return null

        val amount = amountDirection.groupValues[1]
        val direction = amountDirection.groupValues[2]

        return ParsedTransaction(
            amountPaise = toP(amount),
            direction   = if (direction.equals("debited", ignoreCase = true)) "DEBIT" else "CREDIT",
            payeeRaw    = refPayee.groupValues[2].trim(),
            upiRefId    = refPayee.groupValues[1].trim(),
            dateEpoch   = timestamp
        )
    }

    /** Converts "110.00" → 11000L paise, "40" → 4000L paise */
    private fun toP(amount: String): Long =
        (amount.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
}
