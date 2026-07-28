package com.varun.upitracker.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.sms.parser.SmsParser
import com.varun.upitracker.sms.resolver.AliasResolver
import com.varun.upitracker.sms.resolver.ResolvedAs
import com.varun.upitracker.ui.ActorType

private const val TAG = "SmsBacklogScanner"

class SmsBacklogScanner(private val context: Context) {

    companion object {
        const val PREF_NAME = "upi_tracker_prefs"
        const val PREF_BACKLOG_DAYS = "backlog_window_days"
        const val PREF_LAST_SCAN_EPOCH = "last_scan_epoch"
        const val DEFAULT_WINDOW_DAYS = 30
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun scan() {
        val windowDays = prefs.getInt(PREF_BACKLOG_DAYS, DEFAULT_WINDOW_DAYS)
        val windowStart = System.currentTimeMillis() - (windowDays * 24 * 60 * 60 * 1000L)
        val db = AppDatabase.getInstance(context)
        AccountRepository(db).ensureDefaultAccounts()
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
                val sender = it.getString(colAddress) ?: continue
                val body = it.getString(colBody) ?: continue
                val parsed = SmsParser.parse(sender, body, it.getLong(colDate)) ?: continue
                scanned++

                if (db.transactionDao().findByRefId(parsed.upiRefId) != null) {
                    skipped++
                    continue
                }

                val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)
                val matchedFriendId = (resolution as? ResolvedAs.AsFriend)?.friendId
                val matchedMerchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId
                val resolvedActorType = resolution.actorType()
                val needsReview = when (resolution) {
                    is ResolvedAs.AsMerchant -> false
                    is ResolvedAs.AsFriend -> !resolution.isConfident
                    is ResolvedAs.Unknown -> true
                }

                val transaction = Transaction(
                    amountPaise = parsed.amountPaise,
                    payerActorType = if (parsed.direction == "DEBIT") ActorType.ME else resolvedActorType,
                    payerFriendId = if (parsed.direction == "CREDIT") matchedFriendId else null,
                    payerMerchantId = if (parsed.direction == "CREDIT") matchedMerchantId else null,
                    payerRawLabel = if (parsed.direction == "CREDIT") parsed.payeeRaw else null,
                    payeeActorType = if (parsed.direction == "CREDIT") ActorType.ME else resolvedActorType,
                    payeeFriendId = if (parsed.direction == "DEBIT") matchedFriendId else null,
                    payeeMerchantId = if (parsed.direction == "DEBIT") matchedMerchantId else null,
                    payeeRawLabel = if (parsed.direction == "DEBIT") parsed.payeeRaw else null,
                    upiRefId = parsed.upiRefId,
                    dateEpoch = parsed.dateEpoch,
                    source = "SMS",
                    isPending = needsReview
                )

                try {
                    val id = db.transactionDao().insert(transaction)
                    if (!needsReview && parsed.direction == "DEBIT") {
                        db.transactionShareDao().insert(
                            TransactionShare(
                                transactionId = id,
                                side = "PAYER",
                                participantType = ActorType.ME,
                                amountPaise = parsed.amountPaise
                            )
                        )
                    }
                    inserted++
                } catch (e: Exception) {
                    skipped++
                    Log.d(TAG, "Skipped duplicate ref=${parsed.upiRefId}")
                }
            }
        }

        prefs.edit().putLong(PREF_LAST_SCAN_EPOCH, System.currentTimeMillis()).apply()
        Log.d(TAG, "Backlog scan complete scanned=$scanned inserted=$inserted skipped=$skipped")
    }

    fun getWindowDays(): Int = prefs.getInt(PREF_BACKLOG_DAYS, DEFAULT_WINDOW_DAYS)

    fun setWindowDays(days: Int) {
        prefs.edit().putInt(PREF_BACKLOG_DAYS, days).apply()
        Log.d(TAG, "Backlog window updated to $days days")
    }

    fun getLastScanEpoch(): Long = prefs.getLong(PREF_LAST_SCAN_EPOCH, 0L)

    private fun ResolvedAs.actorType(): String = when (this) {
        is ResolvedAs.AsFriend -> ActorType.FRIEND
        is ResolvedAs.AsMerchant -> ActorType.MERCHANT
        is ResolvedAs.Unknown -> ActorType.UNKNOWN
    }
}
