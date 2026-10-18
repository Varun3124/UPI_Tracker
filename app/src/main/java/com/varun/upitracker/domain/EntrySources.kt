package com.varun.upitracker.domain

import com.varun.upitracker.data.repository.StatementImportRepository
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction

/**
 * `Transaction.source` is a free string; `AccountTransfer.source` is the typed enum. A row turned from
 * one into the other keeps where it came from, which is what tells the entry screen an imported
 * amount and date are the bank's, not the user's.
 */
fun Transaction.entrySource(): EntrySource = when (source) {
    "SMS" -> EntrySource.SMS
    "NOTIFICATION" -> EntrySource.NOTIFICATION
    StatementImportRepository.SOURCE_BANK_STATEMENT -> EntrySource.BANK_STATEMENT
    else -> EntrySource.MANUAL
}

fun EntrySource.transactionSource(): String = when (this) {
    EntrySource.SMS -> "SMS"
    EntrySource.NOTIFICATION -> "NOTIFICATION"
    EntrySource.BANK_STATEMENT -> StatementImportRepository.SOURCE_BANK_STATEMENT
    EntrySource.MANUAL -> "MANUAL"
}
