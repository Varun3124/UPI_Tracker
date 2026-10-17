package com.varun.upitracker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.data.prefs.SelfIdentity
import com.varun.upitracker.ui.AliasMappingsActivity
import com.varun.upitracker.ui.CategorySettingsActivity
import com.varun.upitracker.ui.backup.BackupActivity
import com.varun.upitracker.ui.mailbox.MailboxActivity
import com.varun.upitracker.ui.parcel.ParcelImportActivity
import com.varun.upitracker.ui.share.SelfIdentityDialog
import com.varun.upitracker.ui.statement.StatementImportActivity
import com.varun.upitracker.ui.theme.padRootForSystemBars

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        padRootForSystemBars(R.id.main)

        findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<View>(R.id.cardCategories).setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardImportStatement).setOnClickListener {
            startActivity(Intent(this, StatementImportActivity::class.java))
        }

        findViewById<View>(R.id.cardImportParcel).setOnClickListener {
            startActivity(Intent(this, ParcelImportActivity::class.java))
        }

        findViewById<View>(R.id.cardMailbox).setOnClickListener {
            startActivity(Intent(this, MailboxActivity::class.java))
        }

        findViewById<View>(R.id.cardBackup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        findViewById<View>(R.id.cardAccounts).setOnClickListener {
            startActivity(Intent(this, AccountsActivity::class.java))
        }
        findViewById<View>(R.id.cardSelfIdentity).setOnClickListener {
            SelfIdentityDialog.show(this) { renderSelfIdentity() }
        }
        renderSelfIdentity()
        findViewById<View>(R.id.cardFriendAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_FRIEND))
        }
        findViewById<View>(R.id.cardMerchantAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_MERCHANT))
        }
        findViewById<View>(R.id.cardNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun renderSelfIdentity() {
        val identity = SelfIdentity(this)
        val alias = identity.alias()
        findViewById<TextView>(R.id.tvSelfIdentitySummary).text = when {
            alias == null -> "Not set. Friends' apps need it to tell who a parcel is from."
            identity.upiId() == null -> "Friends see you as $alias."
            else -> "Friends see you as $alias · ${identity.upiId()}"
        }
    }
}
