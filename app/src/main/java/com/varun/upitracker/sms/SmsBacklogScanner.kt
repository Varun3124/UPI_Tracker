package com.varun.upitracker.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver

private const val TAG = "SmsBacklogScanner"

class SmsBacklogScanner(private val context: Context) {

    companion object {
        const val PREF_NAME            = "upi_tracker_prefs"
        const val PREF_BACKLOG_DAYS    = "backlog_window_days"
        const val PREF_LAST_SCAN_EPOCH = "last_scan_epoch"
        const val DEFAULT_WINDOW_DAYS  = 30
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Main entry point. Call from MainActivity on every launch.
     * Scans the SMS inbox for unprocessed HDFC messages within the
     * configured window and saves them as pending transactions.
     * Skips anything already in the DB via refId deduplication.
     */
    suspend fun scan() {
        val windowDays  = prefs.getInt(PREF_BACKLOG_DAYS, DEFAULT_WINDOW_DAYS)
        val windowStart = System.currentTimeMillis() - (windowDays * 24 * 60 * 60 * 1000L)

        Log.d(TAG, "Starting backlog scan — window: $windowDays days, from: $windowStart")

        val db       = AppDatabase.getInstance(context)
        val resolver = AliasResolver(db)

        val inbox = Uri.parse("content://sms/inbox")
        val cursor = context.contentResolver.query(
            inbox,
            arrayOf("address", "body", "date"),
            "date >= ?",
            arrayOf(windowStart.toString()),
            "date DESC"
        ) ?: run {
            Log.w(TAG, "Could not query SMS inbox")
            return
        }

        var scanned  = 0
        var inserted = 0
        var skipped  = 0

        cursor.use {
            val colAddress = it.getColumnIndexOrThrow("address")
            val colBody    = it.getColumnIndexOrThrow("body")
            val colDate    = it.getColumnIndexOrThrow("date")

            while (it.moveToNext()) {
                val sender = it.getString(colAddress) ?: continue
                val body   = it.getString(colBody)    ?: continue
                val date   = it.getLong(colDate)

                scanned++

                val parsed = SmsParser.parse(sender, body) ?: continue

                // Deduplication — skip if already in DB
                val existing = db.transactionDao().findByRefId(parsed.upiRefId)
                if (existing != null) {
                    skipped++
                    continue
                }

                // Run alias resolution
                val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)

                val payeeType = when (resolution) {
                    is com.varun.upitracker.resolver.ResolvedAs.AsFriend   -> "FRIEND"
                    is com.varun.upitracker.resolver.ResolvedAs.AsMerchant -> "MERCHANT"
                    is com.varun.upitracker.resolver.ResolvedAs.Unknown    -> "UNKNOWN"
                }
                val resolvedFriendId   = (resolution as? com.varun.upitracker.resolver.ResolvedAs.AsFriend)?.friendId
                val resolvedMerchantId = (resolution as? com.varun.upitracker.resolver.ResolvedAs.AsMerchant)?.merchantId

                val needsOverlay = when (resolution) {
                    is com.varun.upitracker.resolver.ResolvedAs.AsMerchant -> false
                    is com.varun.upitracker.resolver.ResolvedAs.AsFriend   -> !resolution.isConfident
                    is com.varun.upitracker.resolver.ResolvedAs.Unknown    -> true
                }

                val transaction = Transaction(
                    amountPaise        = parsed.amountPaise,
                    direction          = parsed.direction,
                    payeeRaw           = parsed.payeeRaw,
                    payeeType          = payeeType,
                    resolvedFriendId   = resolvedFriendId,
                    resolvedMerchantId = resolvedMerchantId,
                    upiRefId           = parsed.upiRefId,
                    dateEpoch          = parsed.dateEpoch,
                    source             = "SMS",
                    isPending          = needsOverlay
                )

                try {
                    db.transactionDao().insert(transaction)
                    inserted++
                    Log.d(TAG, "Inserted backlog tx: ${parsed.payeeRaw} ₹${parsed.amountPaise/100.0} ref=${parsed.upiRefId}")
                } catch (e: Exception) {
                    // Unique constraint on upiRefId — race condition with live SMS receiver
                    skipped++
                    Log.d(TAG, "Skipped duplicate ref=${parsed.upiRefId}")
                }
            }
        }

        // Save scan timestamp
        prefs.edit().putLong(PREF_LAST_SCAN_EPOCH, System.currentTimeMillis()).apply()

        Log.d(TAG, "Backlog scan complete — scanned=$scanned inserted=$inserted skipped=$skipped")
    }

    fun getWindowDays(): Int = prefs.getInt(PREF_BACKLOG_DAYS, DEFAULT_WINDOW_DAYS)

    fun setWindowDays(days: Int) {
        prefs.edit().putInt(PREF_BACKLOG_DAYS, days).apply()
        Log.d(TAG, "Backlog window updated to $days days")
    }

    fun getLastScanEpoch(): Long = prefs.getLong(PREF_LAST_SCAN_EPOCH, 0L)
}