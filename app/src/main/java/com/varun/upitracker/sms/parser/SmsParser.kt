package com.varun.upitracker.sms.parser

import com.varun.upitracker.parser.ParsedTransaction

object SmsParser {

    // HDFC credit pattern — "Credit Alert!\nRs.40.00 credited to HDFC Bank A/c XX7478 on 07-04-26 from VPA guy9@pingpay (UPI 695909760976)"
    private val HDFC_CREDIT_AMOUNT = Regex("""Rs\.(\d+(?:\.\d+)?) credited""")
    private val HDFC_CREDIT_VPA    = Regex("""from VPA ([^\s]+)\s*\(UPI""")
    private val HDFC_CREDIT_REF    = Regex("""\(UPI (\d+)\)""")

    // HDFC debit pattern — "Sent Rs.110.00\nFrom HDFC Bank A/C *7478\nTo GUY UPI USERNAME\nOn 29/03/26\nRef 645440000144\n..."
    private val HDFC_DEBIT_AMOUNT  = Regex("""Sent Rs\.(\d+(?:\.\d+)?)""")
    private val HDFC_DEBIT_PAYEE   = Regex("""To (.+)""")
    private val HDFC_DEBIT_REF     = Regex("""Ref (\d+)""")

    // AXIS debit pattern — "INR 100.00 debited\nA/c no. XX1591\n06-09-26, 17:59:11\nUPI/P2A/624922603677/HETVEE SAKARIA\n..."
    private val AXIS_DEBIT_AMOUNT      = Regex("""INR\s+(\d+(?:\.\d+)?)\s+debited""", RegexOption.IGNORE_CASE)
    private val AXIS_DEBIT_REF_PAYEE   = Regex("""UPI/[^/]+/(\d+)/(.+)""")

    /**
     * Returns a ParsedTransaction if the message is a recognized HDFC or AXIS UPI SMS, null otherwise.
     */
    fun parse(sender: String, body: String, timestamp: Long): ParsedTransaction? {
        return when {
            sender.contains("HDFC", ignoreCase = true)   -> parseHdfc(body, timestamp)
            sender.contains("AXISBK", ignoreCase = true) -> parseAxis(body, timestamp)
            else -> null
        }
    }

    private fun parseHdfc(body: String, timestamp: Long): ParsedTransaction? = when {
        body.contains("Credit Alert!", ignoreCase = true) -> parseHdfcCredit(body, timestamp)
        body.contains("Sent Rs.", ignoreCase = true)      -> parseHdfcDebit(body, timestamp)
        else -> null
    }

    private fun parseHdfcCredit(body: String, timestamp: Long): ParsedTransaction? {
        val amount  = HDFC_CREDIT_AMOUNT.find(body)?.groupValues?.get(1) ?: return null
        val vpa     = HDFC_CREDIT_VPA.find(body)?.groupValues?.get(1)    ?: return null
        val ref     = HDFC_CREDIT_REF.find(body)?.groupValues?.get(1)    ?: return null

        return ParsedTransaction(
            amountPaise = toP(amount),
            direction   = "CREDIT",
            payeeRaw    = vpa.trim(),
            upiRefId    = ref.trim(),
            dateEpoch   = timestamp
        )
    }

    private fun parseHdfcDebit(body: String, timestamp: Long): ParsedTransaction? {
        val amount  = HDFC_DEBIT_AMOUNT.find(body)?.groupValues?.get(1) ?: return null
        val payee   = HDFC_DEBIT_PAYEE.find(body)?.groupValues?.get(1)  ?: return null
        val ref     = HDFC_DEBIT_REF.find(body)?.groupValues?.get(1)    ?: return null

        return ParsedTransaction(
            amountPaise = toP(amount),
            direction   = "DEBIT",
            payeeRaw    = payee.trim(),
            upiRefId    = ref.trim(),
            dateEpoch   = timestamp
        )
    }

    // AXIS credit-alert parsing isn't implemented yet — no confirmed sample SMS to match against.
    private fun parseAxis(body: String, timestamp: Long): ParsedTransaction? = when {
        body.contains("debited", ignoreCase = true) -> parseAxisDebit(body, timestamp)
        else -> null
    }

    private fun parseAxisDebit(body: String, timestamp: Long): ParsedTransaction? {
        val amount      = AXIS_DEBIT_AMOUNT.find(body)?.groupValues?.get(1) ?: return null
        val refPayee    = AXIS_DEBIT_REF_PAYEE.find(body) ?: return null
        val ref         = refPayee.groupValues[1]
        val payee       = refPayee.groupValues[2]

        return ParsedTransaction(
            amountPaise = toP(amount),
            direction   = "DEBIT",
            payeeRaw    = payee.trim(),
            upiRefId    = ref.trim(),
            dateEpoch   = timestamp
        )
    }

    /** Converts "110.00" → 11000L paise, "40" → 4000L paise */
    private fun toP(amount: String): Long =
        (amount.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
}