package com.varun.upitracker.statement

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Converts a statement's rupee amount to paise, rounding rather than truncating.
 * [com.varun.upitracker.sms.parser.SmsParser] truncates, which turns a floating-point 19.99 into
 * 1998 paise instead of 1999.
 */
fun rupeesToPaise(rupees: Double): Long {
    if (abs(rupees) < 0.005) return 0L
    return (rupees * 100.0).roundToLong()
}

/**
 * One transaction line of a bank statement, already split into the pieces the importer needs.
 *
 * Pure data with no Android or POI types, so the parsing rules stay unit-testable.
 */
data class StatementRow(
    /** Start of the statement's `Date` day, in the device's local zone. */
    val dateEpoch: Long,
    /** The `Narration` cell verbatim. Always populated, even for rows we could not decompose. */
    val narration: String,
    /** The `Chq./Ref.No.` cell, or null when blank or all-zero filler. */
    val statementRefNo: String?,
    val amountPaise: Long,
    /** "DEBIT" or "CREDIT" — same vocabulary [com.varun.upitracker.sms.parser.SmsParser] uses. */
    val direction: String,
    /** The 12-digit UPI RRN, or null for non-UPI rows. */
    val upiRefId: String?,
    /** The counterparty's bank-registered name. Truncated to ~24 chars by HDFC. */
    val rawName: String?,
    /** The counterparty's VPA, e.g. `crazzyproduct@ybl`. */
    val upiId: String?,
    /** Free-text tail of a UPI narration, e.g. `PAYMENT FOR 901441`. */
    val notes: String?
) {
    val isUpi: Boolean get() = upiRefId != null

    /**
     * What [com.varun.upitracker.resolver.AliasResolver] should be asked about: it keys credits on
     * the VPA and debits on the display name, which is exactly how a narration is shaped.
     */
    val resolverKey: String?
        get() = if (direction == "CREDIT") upiId ?: rawName else rawName ?: upiId

    /** Best human label for this row, for pre-filling a pending transaction. */
    val displayLabel: String get() = rawName ?: narration
}
