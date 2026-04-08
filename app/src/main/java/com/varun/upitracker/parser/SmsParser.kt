package com.varun.upitracker.parser

import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedSms(
    val amountPaise: Long,
    val direction: String,       // "DEBIT" or "CREDIT"
    val payeeRaw: String,        // UPI ID for credits, display name for debits
    val upiRefId: String,
    val dateEpoch: Long
)

object SmsParser {

    // Credit pattern — "Credit Alert!\nRs.40.00 credited to HDFC Bank A/c XX7478 on 07-04-26 from VPA guy9@pingpay (UPI 695909760976)"
    private val CREDIT_AMOUNT  = Regex("""Rs\.(\d+(?:\.\d+)?) credited""")
    private val CREDIT_DATE    = Regex("""on (\d{2}-\d{2}-\d{2}) from VPA""")
    private val CREDIT_VPA     = Regex("""from VPA ([^\s]+)\s*\(UPI""")
    private val CREDIT_REF     = Regex("""\(UPI (\d+)\)""")

    // Debit pattern — "Sent Rs.110.00\nFrom HDFC Bank A/C *7478\nTo GUY UPI USERNAME\nOn 29/03/26\nRef 645440000144\n..."
    private val DEBIT_AMOUNT   = Regex("""Sent Rs\.(\d+(?:\.\d+)?)""")
    private val DEBIT_PAYEE    = Regex("""To (.+)""")
    private val DEBIT_DATE     = Regex("""On (\d{2}/\d{2}/\d{2})""")
    private val DEBIT_REF      = Regex("""Ref (\d+)""")

    private val creditDateFmt  = SimpleDateFormat("dd-MM-yy", Locale.getDefault())
    private val debitDateFmt   = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    /**
     * Returns a ParsedSms if the message is a recognised HDFC UPI SMS, null otherwise.
     */
    fun parse(sender: String, body: String): ParsedSms? {
        // Only process HDFC messages
        if (!sender.contains("HDFC", ignoreCase = true)) return null

        return when {
            body.contains("Credit Alert!", ignoreCase = true) -> parseCredit(body)
            body.contains("Sent Rs.", ignoreCase = true)      -> parseDebit(body)
            else -> null
        }
    }

    private fun parseCredit(body: String): ParsedSms? {
        val amount  = CREDIT_AMOUNT.find(body)?.groupValues?.get(1) ?: return null
        val dateStr = CREDIT_DATE.find(body)?.groupValues?.get(1)   ?: return null
        val vpa     = CREDIT_VPA.find(body)?.groupValues?.get(1)    ?: return null
        val ref     = CREDIT_REF.find(body)?.groupValues?.get(1)    ?: return null

        val epoch = try {
            creditDateFmt.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        return ParsedSms(
            amountPaise = toP(amount),
            direction   = "CREDIT",
            payeeRaw    = vpa.trim(),
            upiRefId    = ref.trim(),
            dateEpoch   = epoch
        )
    }

    private fun parseDebit(body: String): ParsedSms? {
        val amount  = DEBIT_AMOUNT.find(body)?.groupValues?.get(1) ?: return null
        val payee   = DEBIT_PAYEE.find(body)?.groupValues?.get(1)  ?: return null
        val dateStr = DEBIT_DATE.find(body)?.groupValues?.get(1)   ?: return null
        val ref     = DEBIT_REF.find(body)?.groupValues?.get(1)    ?: return null

        val epoch = try {
            debitDateFmt.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        return ParsedSms(
            amountPaise = toP(amount),
            direction   = "DEBIT",
            payeeRaw    = payee.trim(),
            upiRefId    = ref.trim(),
            dateEpoch   = epoch
        )
    }

    /** Converts "110.00" → 11000L paise, "40" → 4000L paise */
    private fun toP(amount: String): Long =
        (amount.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
}