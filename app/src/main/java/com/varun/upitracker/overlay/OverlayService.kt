package com.varun.upitracker.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.varun.upitracker.R
import com.varun.upitracker.ui.TransactionEntryActivity

class OverlayService : LifecycleService() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        private const val CHANNEL_ID = "transaction_channel"
        private const val NOTIF_ID = 1001
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val transactionId = intent?.getLongExtra(EXTRA_TRANSACTION_ID, -1L) ?: -1L
        if (transactionId != -1L) {
            showHighPriorityNotification(transactionId)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun showHighPriorityNotification(transactionId: Long) {
        val intent = Intent(this, TransactionEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            transactionId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("New Transaction Detected")
            .setContentText("Tap to categorize your transaction")
            .setPriority(NotificationCompat.PRIORITY_MAX) // Increased to MAX
            .setCategory(NotificationCompat.CATEGORY_ALARM) // Changed to ALARM for higher interruption priority
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show content on lock screen
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Ensure sound, vibration, and lights are used
            .setAutoCancel(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Tracking",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new UPI transactions"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
