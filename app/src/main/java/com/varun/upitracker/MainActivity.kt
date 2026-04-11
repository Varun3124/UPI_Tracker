package com.varun.upitracker

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.DashboardActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText:            TextView
    private lateinit var btnSms:                Button
    private lateinit var btnContinue:           Button
    private lateinit var btnManualEntry:        Button
    private lateinit var tvPendingCount:        TextView

    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUI() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText      = findViewById(R.id.statusText)
        btnSms          = findViewById(R.id.btnGrantSms)
        btnContinue     = findViewById(R.id.btnContinue)
        btnManualEntry  = findViewById(R.id.btnManualEntry)
        tvPendingCount  = findViewById(R.id.tvPendingCount)

        btnSms.setOnClickListener {
            smsLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnContinue.setOnClickListener {
            triggerBacklogScan()
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        btnManualEntry.setOnClickListener {
            launchManualEntry()
        }

        findViewById<Button>(R.id.btnManualEntry).setOnLongClickListener {
            val scanner = SmsBacklogScanner(applicationContext)
            val current = scanner.getWindowDays()
            // Cycles through: 7 → 30 → 60 → 90 → 7...
            val next = when (current) {
                7    -> 30
                30   -> 60
                60   -> 90
                else -> 7
            }
            scanner.setWindowDays(next)
            android.widget.Toast.makeText(this, "Backlog window: $next days", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        updateUI()
        observePendingTransactions()

        // Always scan on launch in case new messages arrived since last open
        if (isSmsGranted()){
            triggerBacklogScan()
            startActivity(Intent(this, DashboardActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // --- Backlog scan ---

    private fun triggerBacklogScan() {
        lifecycleScope.launch(Dispatchers.IO) {
            SmsBacklogScanner(applicationContext).scan()
        }
    }

    // --- Pending transactions observer ---

    private fun observePendingTransactions() {
        val db = AppDatabase.getInstance(applicationContext)
        db.transactionDao().getPendingTransactions().observe(this) { pending ->
            val count = pending.size
            tvPendingCount.text = when (count) {
                0    -> "No pending transactions"
                1    -> "1 pending transaction"
                else -> "$count pending transactions"
            }
        }
    }

    // --- Manual entry ---

    private fun launchManualEntry() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val id = withContext(Dispatchers.IO) {
                db.transactionDao().insert(
                    Transaction(
                        amountPaise = 0L,
                        direction   = "DEBIT",
                        payeeRaw    = "",
                        payeeType   = "UNKNOWN",
                        dateEpoch   = System.currentTimeMillis(),
                        source      = "MANUAL",
                        isPending   = true
                    )
                )
            }
            val intent = Intent(applicationContext,
                com.varun.upitracker.overlay.OverlayService::class.java).apply {
                putExtra(com.varun.upitracker.overlay.OverlayService.EXTRA_TRANSACTION_ID, id)
            }
            applicationContext.startForegroundService(intent)
        }
    }

    // --- Permission checks ---

    private fun isSmsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    private fun isOverlayGranted() = Settings.canDrawOverlays(this)

    private fun isUsageGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // --- UI update ---

    private fun updateUI() {
        btnSms.text      = if (isSmsGranted()) "✓ SMS Granted" else "Grant SMS Permission"
        btnSms.isEnabled = !isSmsGranted()

        val smsGranted = isSmsGranted()
        btnContinue.isEnabled = smsGranted
        statusText.text = if (smsGranted)
            "SMS permission granted. You're ready."
        else
            "Grant SMS permission to continue."
    }
}