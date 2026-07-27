package com.varun.upitracker.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PendingTransactionReviewReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_REVIEWED = "com.varun.upitracker.action.MARK_REVIEWED"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_REVIEWED) return

        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (transactionId == -1L || notificationId == -1) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reviewed = PendingTransactionReviewer.review(context.applicationContext, transactionId)
                if (reviewed) {
                    TransactionNotificationHelper.cancel(context.applicationContext, notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
