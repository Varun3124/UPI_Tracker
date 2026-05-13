package com.varun.upitracker.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var tvBalanceValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        repository = SettingsRepository(applicationContext)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvBalanceValue = findViewById(R.id.tvBalanceValue)

        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnEditBalance).setOnClickListener { showBalanceDialog() }
        findViewById<android.view.View>(R.id.cardCategories).setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardFriendAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_FRIEND))
        }
        findViewById<android.view.View>(R.id.cardMerchantAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_MERCHANT))
        }
    }

    override fun onResume() {
        super.onResume()
        loadBalance()
    }

    private fun loadBalance() {
        lifecycleScope.launch {
            val balance = withContext(Dispatchers.IO) { repository.getTotalBalancePaise() }
            tvBalanceValue.text = if (balance == null) "Not set" else "Rs${"%.2f".format(balance / 100.0)}"
        }
    }

    private fun showBalanceDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "0.00"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.updateTotalBalancePaise(paise) }
                        loadBalance()
                    } catch (error: Exception) {
                        Toast.makeText(this@SettingsActivity, error.message ?: "Could not save balance.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
