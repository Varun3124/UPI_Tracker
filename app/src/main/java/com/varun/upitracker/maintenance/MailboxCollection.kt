package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.mailbox.MailboxSync
import com.varun.upitracker.data.mailbox.SyncReport
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ui.mailbox.MailboxNotifications

private const val TAG = "MailboxCollection"

/**
 * Collects the friends mailbox with nobody watching -- at launch, and when the dashboard comes back
 * -- and tells the user about anything that needs them.
 *
 * Conservative the way [OpportunisticBackup] is: throttled by [MailboxSync], silent when the mailbox
 * is off, and every failure swallowed after a log. A parcel that did not arrive this time arrives
 * next time; nothing about that is worth a crash or a toast on somebody's dashboard.
 */
class MailboxCollection(private val context: Context) {

    suspend fun run(force: Boolean = false): SyncReport? = try {
        val db = AppDatabase.getInstance(context)
        MailboxSync(context, db).run(force)?.also { report ->
            val names = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
            MailboxNotifications.show(context, report, names)
        }
    } catch (error: Exception) {
        // Including cancellation: at launch this runs on a detached scope with no one to tell.
        Log.w(TAG, "Mailbox collection did not run", error)
        null
    }
}
