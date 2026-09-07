package com.varun.upitracker.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.parser.ParsedTransaction
import com.varun.upitracker.parser.TransactionSaver
import com.varun.upitracker.sms.parser.SmsParser
import com.varun.upitracker.resolver.AliasResolver
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

    private suspend fun saveTransaction(context: Context, parsed: ParsedTransaction) {
        val db = AppDatabase.getInstance(context)
        val accountRepository = AccountRepository(db)
        val defaultSavingsAccount = accountRepository.getDefaultAccountByType(AccountType.SAVINGS) ?: run {
            Log.w(TAG, "No default savings account found, cannot save transaction")
            return
        }

        TransactionSaver.saveIfNew(
            context = context,
            db = db,
            resolver = AliasResolver(db),
            defaultAccountId = defaultSavingsAccount.id,
            parsed = parsed,
            source = "SMS",
            notify = true
        )
    }
}
