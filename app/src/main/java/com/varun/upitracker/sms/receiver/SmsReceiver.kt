package com.varun.upitracker.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.sms.parser.ParsedSms
import com.varun.upitracker.sms.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { sms ->
            val sender = sms.originatingAddress ?: return@forEach
            val body = sms.messageBody ?: return@forEach
            val parsed = SmsParser.parse(sender, body, sms.timestampMillis) ?: return@forEach

            Log.d(TAG, "Parsed SMS: $parsed")
            CoroutineScope(Dispatchers.IO).launch {
                saveTransaction(context, parsed)
            }
        }
    }

    private suspend fun saveTransaction(context: Context, parsed: ParsedSms) {
        val db = AppDatabase.getInstance(context)
        val accountRepository = AccountRepository(db)
        val defaultSavingsAccount = accountRepository.getDefaultAccountByType(AccountType.SAVINGS) ?: run {
            Log.w(TAG, "No default savings account found, cannot save transaction")
            return
        }
        if (db.transactionDao().findByRefId(parsed.upiRefId) != null) {
            Log.d(TAG, "Duplicate ref ${parsed.upiRefId}, skipping")
            return
        }

        val resolver = AliasResolver(db)
        val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)
        val matchedFriendId = (resolution as? ResolvedAs.AsFriend)?.friendId
        val matchedMerchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId
        val resolvedActorType = resolution.actorType()


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
            myAccountId = defaultSavingsAccount.id,
            dateEpoch = parsed.dateEpoch,
            source = "SMS",
            isPending = true
        )

        val id = db.transactionDao().insert(transaction)
        Log.d(TAG, "Saved transaction id=$id actor=$resolvedActorType pending=true")

        val displayLabel = when (resolution) {
            is ResolvedAs.AsFriend -> resolution.name
            is ResolvedAs.AsMerchant -> resolution.name
            is ResolvedAs.Unknown -> parsed.payeeRaw
        }
        TransactionNotificationHelper.showPendingTransactionNotification(
            context = context,
            transactionId = id,
            amountPaise = parsed.amountPaise,
            displayLabel = displayLabel,
            needsReview = true
        )
    }

    private fun ResolvedAs.actorType(): String = when (this) {
        is ResolvedAs.AsFriend -> ActorType.FRIEND
        is ResolvedAs.AsMerchant -> ActorType.MERCHANT
        is ResolvedAs.Unknown -> ActorType.UNKNOWN
    }
}
