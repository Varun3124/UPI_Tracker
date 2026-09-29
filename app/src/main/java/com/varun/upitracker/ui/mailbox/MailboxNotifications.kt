package com.varun.upitracker.ui.mailbox

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
import com.varun.upitracker.data.mailbox.SyncReport

/**
 * Tells the user something arrived from a friend.
 *
 * Says who, never what: no amounts and no notes, since a notification shows on a lock screen where
 * anyone can read it. One notification, replaced each time, so a busy evening is a summary rather
 * than a pile.
 */
object MailboxNotifications {

    private const val CHANNEL_ID = "mailbox_channel"

    private const val NOTIFICATION_ID = 7_300_001

    fun show(context: Context, report: SyncReport, friendNames: Map<Long, String>) {
        val lines = buildList {
            report.parcelsByFriend.keys.forEach { friendId ->
                add("${friendNames[friendId] ?: "A friend"} sent you transactions to look at")
            }
            when (report.newLinkReplies) {
                0 -> Unit
                1 -> add("Someone answered your link invite")
                else -> add("${report.newLinkReplies} people answered your link invites")
            }
            report.confirmedFriendIds.forEach { friendId ->
                add("You are now linked with ${friendNames[friendId] ?: "a friend"}")
            }
            report.proposalsFrom.forEach { friendId ->
                add("${friendNames[friendId] ?: "A friend"} asked you to agree on your balance")
            }
            report.acceptedBy.forEach { friendId ->
                add("${friendNames[friendId] ?: "A friend"} agreed to your balance")
            }
            report.deniedBy.forEach { friendId ->
                add("${friendNames[friendId] ?: "A friend"} did not agree to your balance")
            }
        }
        if (lines.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)
        val open = Intent(context, MailboxInboxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lines.first())
            .setContentText(if (lines.size > 1) lines.drop(1).joinToString(" · ") else "Nothing is saved until you look.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "From friends",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Transactions and link requests from friends you link with"
        }
        manager.createNotificationChannel(channel)
    }
}
