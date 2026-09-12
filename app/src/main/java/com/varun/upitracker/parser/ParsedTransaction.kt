package com.varun.upitracker.parser

/** A transaction extracted from a bank-originated message, regardless of channel (SMS, Gmail notification, ...). */
data class ParsedTransaction(
    val amountPaise: Long,
    val direction: String,       // "DEBIT" or "CREDIT"
    val payeeRaw: String,        // UPI ID for credits, display name for debits
    val upiRefId: String,        // bank's canonical UPI reference — the cross-channel dedup key
    val dateEpoch: Long
)
