package com.varun.upitracker.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.varun.upitracker.R
import com.varun.upitracker.ui.TransactionEntryActivity

object TransactionNotificationHelper {

    private const val CHANNEL_ID = "transaction_channel"

    fun showPendingTransactionNotification(
        context: Context,
        transactionId: Long,
        amountPaise: Long,
        displayLabel: String
    ) {
        ensureChannel(context)

        val notificationId = transactionId.toInt()
        val openIntent = Intent(context, TransactionEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reviewIntent = Intent(context, PendingTransactionReviewReceiver::class.java).apply {
            action = PendingTransactionReviewReceiver.ACTION_MARK_REVIEWED
            putExtra(PendingTransactionReviewReceiver.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(PendingTransactionReviewReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val reviewPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 100_000,
            reviewIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val safeLabel = displayLabel.ifBlank { "Unknown" }
        val amount = "Rs${"%.0f".format(amountPaise / 100.0)}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("UPI Transaction Detected")
            .setContentText("$amount • $safeLabel")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$amount • $safeLabel"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Mark as reviewed", reviewPendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transaction Tracking",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for newly detected UPI transactions"
        }
        manager.createNotificationChannel(channel)
    }
}
