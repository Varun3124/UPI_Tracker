package com.varun.upitracker.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import com.varun.upitracker.ui.dashboard.DashboardActivity

/**
 * The dashboard is the root of the app: going back from any page lands on the page before it, and
 * only going back from the dashboard leaves.
 *
 * A page opened from outside -- a transaction notification, the SMS overlay, the mailbox's
 * notification, an invite link -- can be the first thing in its task, with nothing behind it to go
 * back to. Called from such a page's `finish()`, this puts the dashboard behind it as it goes, so
 * back, Close and Save all end there rather than closing the app. A page with something under it is
 * left alone: back already goes to that.
 *
 * Not over the lock screen, where the entry screen is shown to categorise a payment without
 * unlocking: the dashboard would only be waiting behind the keyguard.
 */
fun Activity.openDashboardIfLeavingRoot() {
    if (!isTaskRoot || isChangingConfigurations) return
    if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
    startActivity(Intent(this, DashboardActivity::class.java))
}
