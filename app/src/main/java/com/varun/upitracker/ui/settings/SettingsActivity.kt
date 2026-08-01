package com.varun.upitracker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.varun.upitracker.R
import com.varun.upitracker.ui.AliasMappingsActivity
import com.varun.upitracker.ui.CategorySettingsActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var tvBalanceValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        viewModel = ViewModelProvider(
            this,
            AppViewModelFactory(applicationContext)
        )[SettingsViewModel::class.java]

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvBalanceValue = findViewById(R.id.tvBalanceValue)

        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnEditBalance).setOnClickListener { showBalanceDialog() }
        findViewById<View>(R.id.cardCategories).setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardFriendAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.Companion.createIntent(this, AliasMappingsActivity.Companion.MODE_FRIEND))
        }
        findViewById<View>(R.id.cardMerchantAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.Companion.createIntent(this, AliasMappingsActivity.Companion.MODE_MERCHANT))
        }

        viewModel.balancePaise.observe(this) { balance ->
            tvBalanceValue.text = if (balance == null) "Not set" else "Rs${"%.2f".format(balance / 100.0)}"
        }
    }

    override fun onResume() {
        super.onResume()
        loadBalance()
    }

    private fun loadBalance() {
        viewModel.loadBalance()
    }

    private fun showBalanceDialog() {
        val input = EditText(this).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val existing = tvBalanceValue.text.toString()
        if (existing.startsWith("Rs")) {
            input.setText(existing.removePrefix("Rs"))
            input.setSelection(input.text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Set total balance")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val paise = (input.text.toString().trim().toDoubleOrNull()?.times(100))?.toLong()
                if (paise == null) {
                    Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.saveBalance(paise) { message ->
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
