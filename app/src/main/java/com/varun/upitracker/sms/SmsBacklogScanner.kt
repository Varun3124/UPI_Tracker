package com.varun.upitracker.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.ShareSide

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

        Log.d(TAG, "Starting backlog scan window=$windowDays from=$windowStart")

        val db = AppDatabase.getInstance(context)
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
                val date = it.getLong(colDate)

                scanned++

                val parsed = SmsParser.parse(sender, body, date) ?: continue

                if (db.transactionDao().findByRefId(parsed.upiRefId) != null) {
                    skipped++
                    continue
                }

                val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)
                val payeeType = when (resolution) {
                    is ResolvedAs.AsFriend -> "FRIEND"
                    is ResolvedAs.AsMerchant -> "MERCHANT"
                    is ResolvedAs.Unknown -> "UNKNOWN"
                }
                val resolvedFriendId = (resolution as? ResolvedAs.AsFriend)?.friendId
                val resolvedMerchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId

                val needsOverlay = when (resolution) {
                    is ResolvedAs.AsMerchant -> false
                    is ResolvedAs.AsFriend -> !resolution.isConfident
                    is ResolvedAs.Unknown -> true
                }

                val transaction = Transaction(
                    amountPaise = parsed.amountPaise,
                    direction = parsed.direction,
                    observedDirection = parsed.direction,
                    payeeRaw = parsed.payeeRaw,
                    payeeType = payeeType,
                    mySharePaise = if (needsOverlay) null else parsed.amountPaise,
                    resolvedFriendId = resolvedFriendId,
                    resolvedMerchantId = resolvedMerchantId,
                    payerActorType = if (parsed.direction == "DEBIT") {
                        ActorType.ME
                    } else {
                        when (resolution) {
                            is ResolvedAs.AsFriend -> ActorType.FRIEND
                            is ResolvedAs.AsMerchant -> ActorType.MERCHANT
                            is ResolvedAs.Unknown -> ActorType.UNKNOWN
                        }
                    },
                    payerFriendId = if (parsed.direction == "CREDIT") resolvedFriendId else null,
                    payerMerchantId = if (parsed.direction == "CREDIT") resolvedMerchantId else null,
                    payerRawLabel = if (parsed.direction == "CREDIT") parsed.payeeRaw else null,
                    payeeActorType = if (parsed.direction == "CREDIT") {
                        ActorType.ME
                    } else {
                        ActorType.MERCHANT
                    },
                    payeeFriendId = null,
                    payeeMerchantId = if (parsed.direction == "DEBIT" && resolution is ResolvedAs.AsMerchant) {
                        resolvedMerchantId
                    } else null,
                    payeeRawLabel = if (parsed.direction == "DEBIT") parsed.payeeRaw else null,
                    upiRefId = parsed.upiRefId,
                    dateEpoch = parsed.dateEpoch,
                    source = "SMS",
                    isPending = needsOverlay
                )

                try {
                    val id = db.transactionDao().insert(transaction)
                    if (!needsOverlay) {
                        db.transactionShareDao().insert(
                            TransactionShare(
                                transactionId = id,
                                participantType = ActorType.ME,
                                shareSide = if (parsed.direction == "DEBIT") {
                                    ShareSide.MEANT_TO_PAY
                                } else {
                                    ShareSide.MEANT_TO_RECEIVE
                                },
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
}
