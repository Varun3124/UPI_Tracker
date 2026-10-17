package com.varun.upitracker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.data.prefs.IouThresholdPrefs
import com.varun.upitracker.data.prefs.SelfIdentity
import com.varun.upitracker.domain.iou.IouThresholds
import com.varun.upitracker.util.AmountFormat
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
        findViewById<View>(R.id.cardIouThresholds).setOnClickListener { editIouThresholds() }
        renderIouThresholds()
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

    private fun renderIouThresholds() {
        val thresholds = IouThresholdPrefs(this).get()
        findViewById<TextView>(R.id.tvIouThresholdsSummary).text =
            "Settled below ${AmountFormat.rupeesExact(thresholds.settledBelowPaise)} · " +
                "insignificant below ${AmountFormat.rupeesExact(thresholds.insignificantBelowPaise)}"
    }

    /** Both lines in one dialog, since each is only right in relation to the other. */
    private fun editIouThresholds() {
        val store = IouThresholdPrefs(this)
        val current = store.get()
        val content = layoutInflater.inflate(R.layout.dialog_iou_thresholds, null)
        val etSettled = content.findViewById<EditText>(R.id.etSettledBelow)
        val etInsignificant = content.findViewById<EditText>(R.id.etInsignificantBelow)
        etSettled.setText(AmountFormat.forInput(current.settledBelowPaise))
        etInsignificant.setText(AmountFormat.forInput(current.insignificantBelowPaise))

        val dialog = AlertDialog.Builder(this)
            .setTitle("IOU thresholds")
            .setView(content)
            .setPositiveButton("Save", null)
            .setNeutralButton("Defaults", null)
            .setNegativeButton("Cancel", null)
            .create()
        // Wired after show so a refusal keeps the dialog open with what was typed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                etSettled.setText(AmountFormat.forInput(IouThresholds.DEFAULT_SETTLED_BELOW_PAISE))
                etInsignificant.setText(AmountFormat.forInput(IouThresholds.DEFAULT_INSIGNIFICANT_BELOW_PAISE))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val settled = AmountFormat.paiseOrNull(etSettled.text.toString().ifBlank { "0" })
                val insignificant = AmountFormat.paiseOrNull(etInsignificant.text.toString().ifBlank { "0" })
                val problem = if (settled == null || insignificant == null) {
                    "Enter an amount in rupees for both."
                } else {
                    store.set(IouThresholds(settled, insignificant))
                }
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    renderIouThresholds()
                }
            }
        }
        dialog.show()
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
