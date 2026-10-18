package com.varun.upitracker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.data.prefs.DuplicateRulePrefs
import com.varun.upitracker.data.prefs.IouThresholdPrefs
import com.varun.upitracker.domain.dedup.DuplicateRules
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
        findViewById<View>(R.id.cardDuplicateRules).setOnClickListener { editDuplicateRules() }
        renderDuplicateRules()
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

    private fun renderDuplicateRules() {
        val rules = DuplicateRulePrefs(this).get()
        val days = when (rules.daysEitherSide) {
            0 -> "same day"
            1 -> "1 day either side"
            else -> "${rules.daysEitherSide} days either side"
        }
        val amount = if (rules.amountTolerancePaise == 0L) {
            "exact amount"
        } else {
            "within ${AmountFormat.rupees(rules.amountTolerancePaise)}"
        }
        val extras = listOfNotNull(
            "same person or shop".takeIf { rules.sameCounterpartyOnly },
            "same account".takeIf { rules.sameAccountOnly }
        )
        findViewById<TextView>(R.id.tvDuplicateRulesSummary).text =
            (listOf(days, amount) + extras).joinToString(" · ")
    }

    /** Statement and parcel imports both read these when they look for a row already recorded. */
    private fun editDuplicateRules() {
        val store = DuplicateRulePrefs(this)
        val content = layoutInflater.inflate(R.layout.dialog_duplicate_rules, null)
        val etDays = content.findViewById<EditText>(R.id.etDuplicateDays)
        val etAmount = content.findViewById<EditText>(R.id.etDuplicateAmount)
        val sameCounterparty = content.findViewById<CheckBox>(R.id.cbDuplicateSameCounterparty)
        val sameAccount = content.findViewById<CheckBox>(R.id.cbDuplicateSameAccount)
        val skipReferences = content.findViewById<CheckBox>(R.id.cbDuplicateSkipReferences)
        fun show(rules: DuplicateRules) {
            etDays.setText(rules.daysEitherSide.toString())
            etAmount.setText(AmountFormat.forInput(rules.amountTolerancePaise))
            sameCounterparty.isChecked = rules.sameCounterpartyOnly
            sameAccount.isChecked = rules.sameAccountOnly
            skipReferences.isChecked = rules.skipOtherReferences
        }
        show(store.get())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Duplicate check")
            .setView(content)
            .setPositiveButton("Save", null)
            .setNeutralButton("Defaults", null)
            .setNegativeButton("Cancel", null)
            .create()
        // Wired after show so a refusal keeps the dialog open with what was typed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { show(DuplicateRules()) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val days = etDays.text.toString().trim().ifBlank { "0" }.toIntOrNull()
                val tolerance = AmountFormat.paiseOrNull(etAmount.text.toString().ifBlank { "0" })
                val problem = if (days == null || tolerance == null) {
                    "Enter a number of days and an amount in rupees."
                } else {
                    store.set(
                        DuplicateRules(
                            daysEitherSide = days,
                            amountTolerancePaise = tolerance,
                            sameCounterpartyOnly = sameCounterparty.isChecked,
                            sameAccountOnly = sameAccount.isChecked,
                            skipOtherReferences = skipReferences.isChecked
                        )
                    )
                }
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    renderDuplicateRules()
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
