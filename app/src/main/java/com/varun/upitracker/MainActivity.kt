package com.varun.upitracker

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnSms: Button
    private lateinit var btnContinue: Button

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        btnSms       = findViewById(R.id.btnGrantSms)
        btnContinue  = findViewById(R.id.btnContinue)

        btnSms.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionsLauncher.launch(perms.toTypedArray())
        }





        btnContinue.setOnClickListener {
            // Will navigate to the home screen in a later step
            statusText.text = "✓ Setup complete. Dashboard coming in a later step."
        }

        findViewById<Button>(R.id.btnManualEntry).setOnClickListener {
            val db = AppDatabase.getInstance(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val id = db.transactionDao().insert(
                    Transaction(
                        amountPaise = 0L,
                        direction   = "DEBIT",
                        payeeRaw    = "Manual Entry",
                        payeeType   = "UNKNOWN",
                        dateEpoch   = System.currentTimeMillis(),
                        source      = "MANUAL",
                        isPending   = true
                    )
                )
                val intent = Intent(this@MainActivity, com.varun.upitracker.overlay.TransactionEntryActivity::class.java).apply {
                    putExtra(com.varun.upitracker.overlay.TransactionEntryActivity.EXTRA_TRANSACTION_ID, id)
                }
                startActivity(intent)
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI() // Re-check every time user comes back from Settings
    }

    // --- Permission check helpers ---

    private fun isSmsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    private fun isNotificationGranted(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }





    // --- UI update ---

    private fun updateUI() {
        val sms = isSmsGranted()
        val notif = isNotificationGranted()

        btnSms.text = if (sms && notif) "✓ Permissions Granted" else "Grant Permissions"
        btnSms.isEnabled = !(sms && notif)

        val allGranted = sms && notif
        btnContinue.isEnabled = allGranted
        statusText.text = if (allGranted)
            "All permissions granted. You're ready."
        else if (!sms)
            "Grant the SMS permission below to continue."
        else
            "Grant Notification permission to see alerts."
    }
}