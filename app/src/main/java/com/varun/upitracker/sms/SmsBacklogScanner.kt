package com.varun.upitracker.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.parser.TransactionSaver
import com.varun.upitracker.sms.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver

private const val TAG = "SmsBacklogScanner"

class SmsBacklogScanner(private val context: Context) {

    companion object {
        const val PREF_NAME = "upi_tracker_prefs"
        const val PREF_LAST_SCAN_EPOCH = "last_scan_epoch"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun scan() {
        val db = AppDatabase.getInstance(context)
        val accountRepository = AccountRepository(db)
        val defaultSavingsAccount = accountRepository.getDefaultAccountByType(AccountType.SAVINGS) ?: run {
            Log.w(TAG, "No default savings account found, aborting backlog scan")
            return
        }
        val windowStart = db.balanceSnapshotDao()
            .getEarliestForAccount(defaultSavingsAccount.id)
            ?.snapshotEpoch ?: defaultSavingsAccount.addedEpoch
        val resolver = AliasResolver(db)

        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            "date >= ?",
            arrayOf(windowStart.toString()),
            "date DESC"
        ) ?: run {
            Log.w(TAG, "Could not query SMS inbox")
            return
        }

        var scanned = 0
        var inserted = 0
        var skipped = 0

        cursor.use {
            val colAddress = it.getColumnIndexOrThrow("address")
            val colBody = it.getColumnIndexOrThrow("body")
            val colDate = it.getColumnIndexOrThrow("date")

            while (it.moveToNext()) {
                //Parsing
                val sender = it.getString(colAddress) ?: continue
                val body = it.getString(colBody) ?: continue
                val parsed = SmsParser.parse(sender, body, it.getLong(colDate)) ?: continue
                scanned++

                val wasInserted = TransactionSaver.saveIfNew(
                    context = context,
                    db = db,
                    resolver = resolver,
                    defaultAccountId = defaultSavingsAccount.id,
                    parsed = parsed,
                    source = "SMS",
                    notify = false
                )
                if (wasInserted) inserted++ else skipped++
            }
        }

        prefs.edit().putLong(PREF_LAST_SCAN_EPOCH, System.currentTimeMillis()).apply()
        Log.d(TAG, "Backlog scan complete scanned=$scanned inserted=$inserted skipped=$skipped")
    }
}
