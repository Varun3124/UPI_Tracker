package com.varun.upitracker.sms.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.varun.upitracker.R
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import com.varun.upitracker.util.AmountFormat

object TransactionNotificationHelper {

    private const val CHANNEL_ID = "transaction_channel"

    /**
     * [category] is what the merchant has always been filed under, shown on the notification and
     * offered as "Save as ...". [canConfirm] is false when confirming from here would decide something
     * that is the user's -- then the row can only be opened.
     */
    fun showPendingTransactionNotification(
        context: Context,
        transactionId: Long,
        amountPaise: Long,
        displayLabel: String,
        needsReview: Boolean = true,
        category: Category? = null,
        canConfirm: Boolean = true
    ) {
        ensureChannel(context)

        val notificationId = transactionId.toInt()
        val safeLabel      = displayLabel.ifBlank { "Unknown" }
        val amount         = AmountFormat.rupees(amountPaise)

        val title = when {
            !needsReview -> "Transaction recorded"
            category != null -> "Looks like ${category.name}"
            else -> "Tap to categorise"
        }
        val text = listOfNotNull(amount, safeLabel, category?.name).joinToString("  •  ")

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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)

        if (needsReview && canConfirm) {
            val reviewIntent = Intent(context, PendingTransactionReviewReceiver::class.java).apply {
                action = PendingTransactionReviewReceiver.ACTION_MARK_REVIEWED
                putExtra(PendingTransactionReviewReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(PendingTransactionReviewReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                category?.let { putExtra(PendingTransactionReviewReceiver.EXTRA_CATEGORY_ID, it.id) }
            }
            val reviewPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 100_000,
                reviewIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, category?.let { "Save as ${it.name}" } ?: "Mark as reviewed", reviewPendingIntent)
        }

        // Fix: Explicitly check for POST_NOTIFICATIONS permission (Required for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Cannot show notification without permission
                return
            }
        }

        // Check for POST_NOTIFICATIONS permission (Required for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun ensureChannel(context: Context) {
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
