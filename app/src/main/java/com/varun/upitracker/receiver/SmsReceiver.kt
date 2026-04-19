package com.varun.upitracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.ShareSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val body   = sms.messageBody        ?: continue
            val timestamp = sms.timestampMillis

            Log.d(TAG, "SMS from $sender: $body")

            val parsed = SmsParser.parse(sender, body, timestamp) ?: continue

            Log.d(TAG, "Parsed: $parsed")

            CoroutineScope(Dispatchers.IO).launch {
                saveTransaction(context, parsed)
            }
        }
    }

    private suspend fun saveTransaction(context: Context, parsed: com.varun.upitracker.parser.ParsedSms) {
        val db       = AppDatabase.getInstance(context)
        val resolver = AliasResolver(db)

        // Deduplication — skip if we already have this ref ID
        val existing = db.transactionDao().findByRefId(parsed.upiRefId)
        if (existing != null) {
            Log.d(TAG, "Duplicate ref ${parsed.upiRefId}, skipping")
            return
        }

        // Run alias resolution
        val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)
        Log.d(TAG, "Resolution for '${parsed.payeeRaw}': $resolution")

        // Map resolution result to transaction fields
        val payeeType          = when (resolution) {
            is ResolvedAs.AsFriend   -> "FRIEND"
            is ResolvedAs.AsMerchant -> "MERCHANT"
            is ResolvedAs.Unknown    -> "UNKNOWN"
        }
        val resolvedFriendId   = (resolution as? ResolvedAs.AsFriend)?.friendId
        val resolvedMerchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId

        // A merchant debit that resolved confidently doesn't need overlay confirmation
        // A friend match from raw name is never confident — always show overlay
        val needsOverlay = when (resolution) {
            is ResolvedAs.AsMerchant -> false
            is ResolvedAs.AsFriend   -> !resolution.isConfident
            is ResolvedAs.Unknown    -> true
        }

        val transaction = Transaction(
            amountPaise        = parsed.amountPaise,
            direction          = parsed.direction,
            observedDirection  = parsed.direction,
            payeeRaw           = parsed.payeeRaw,
            payeeType          = payeeType,
            resolvedFriendId   = resolvedFriendId,
            resolvedMerchantId = resolvedMerchantId,
            payerActorType     = when (parsed.direction) {
                "DEBIT" -> ActorType.ME
                else -> when (resolution) {
                    is ResolvedAs.AsFriend -> ActorType.FRIEND
                    is ResolvedAs.AsMerchant -> ActorType.MERCHANT
                    else -> ActorType.UNKNOWN
                }
            },
            payerFriendId      = if (parsed.direction == "CREDIT") resolvedFriendId else null,
            payerMerchantId    = if (parsed.direction == "CREDIT") resolvedMerchantId else null,
            payerRawLabel      = if (parsed.direction == "CREDIT") parsed.payeeRaw else null,
            payeeActorType     = when (parsed.direction) {
                "CREDIT" -> ActorType.ME
                else -> ActorType.MERCHANT
            },
            payeeFriendId      = null,
            payeeMerchantId    = if (parsed.direction == "DEBIT" && resolution is ResolvedAs.AsMerchant) {
                resolvedMerchantId
            } else null,
            payeeRawLabel      = if (parsed.direction == "DEBIT") parsed.payeeRaw else null,
            mySharePaise       = if (needsOverlay) null else parsed.amountPaise,
            upiRefId           = parsed.upiRefId,
            dateEpoch          = parsed.dateEpoch,
            source             = "SMS",
            isPending          = needsOverlay
        )

        val id = db.transactionDao().insert(transaction)
        Log.d(TAG, "Saved transaction id=$id | type=$payeeType | pending=$needsOverlay")

        if (!needsOverlay) {
            db.transactionShareDao().insert(
                TransactionShare(
                    transactionId = id,
                    participantType = ActorType.ME,
                    friendId = null,
                    shareSide = if (parsed.direction == "DEBIT") {
                        ShareSide.MEANT_TO_PAY
                    } else {
                        ShareSide.MEANT_TO_RECEIVE
                    },
                    amountPaise = parsed.amountPaise
                )
            )
        }

        if (needsOverlay && id != -1L) {
            val overlayIntent = Intent(context, com.varun.upitracker.overlay.OverlayService::class.java).apply {
                putExtra(com.varun.upitracker.overlay.OverlayService.EXTRA_TRANSACTION_ID, id)
            }
            context.startForegroundService(overlayIntent)
        }
    }
}
