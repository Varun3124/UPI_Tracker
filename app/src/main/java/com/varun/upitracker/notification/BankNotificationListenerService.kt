package com.varun.upitracker.notification

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.notification.parser.NotificationParser
import com.varun.upitracker.parser.ParsedTransaction
import com.varun.upitracker.parser.TransactionSaver
import com.varun.upitracker.resolver.AliasResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "BankNotifListener"
private const val SOURCE = "NOTIFICATION"

class BankNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != NotificationParser.GMAIL_PACKAGE) return

        val text = extractText(sbn.notification) ?: return
        val parsed = NotificationParser.parse(sbn.packageName, text, sbn.postTime) ?: return

        Log.d(TAG, "Parsed notification: $parsed")
        CoroutineScope(Dispatchers.IO).launch {
            saveOne(applicationContext, parsed)
        }
    }

    // Android exposes no history of dismissed/past notifications — this is the closest thing to a
    // backlog scan: whatever's still sitting in the tray from before the listener connected.
    override fun onListenerConnected() {
        super.onListenerConnected()

        val candidates = runCatching { activeNotifications }
            .getOrDefault(emptyArray())
            .filter { it.packageName == NotificationParser.GMAIL_PACKAGE }
        if (candidates.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(applicationContext)
            val accountRepository = AccountRepository(db)
            val defaultSavingsAccount = accountRepository.getDefaultAccountByType(AccountType.SAVINGS) ?: run {
                Log.w(TAG, "No default savings account found, skipping active-notification catch-up")
                return@launch
            }
            val resolver = AliasResolver(db)

            candidates.forEach { sbn ->
                val text = extractText(sbn.notification) ?: return@forEach
                val parsed = NotificationParser.parse(sbn.packageName, text, sbn.postTime) ?: return@forEach
                TransactionSaver.saveIfNew(
                    context = applicationContext,
                    db = db,
                    resolver = resolver,
                    defaultAccountId = defaultSavingsAccount.id,
                    parsed = parsed,
                    source = SOURCE,
                    notify = true
                )
            }
        }
    }

    private suspend fun saveOne(context: Context, parsed: ParsedTransaction) {
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
            source = SOURCE,
            notify = true
        )
    }

    private fun extractText(notification: Notification): String? {
        val extras = notification.extras ?: return null
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return bigText ?: text
    }
}
