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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnSms: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnUsage: Button
    private lateinit var btnContinue: Button

    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        btnSms       = findViewById(R.id.btnGrantSms)
        btnOverlay   = findViewById(R.id.btnGrantOverlay)
        btnUsage     = findViewById(R.id.btnGrantUsage)
        btnContinue  = findViewById(R.id.btnContinue)

        btnSms.setOnClickListener {
            smsLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnContinue.setOnClickListener {
            // Will navigate to the home screen in a later step
            statusText.text = "✓ Setup complete. Dashboard coming in a later step."
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

    private fun isOverlayGranted() = Settings.canDrawOverlays(this)

    private fun isUsageGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // --- UI update ---

    private fun updateUI() {
        btnSms.text = if (isSmsGranted()) "✓ SMS Granted" else "Grant SMS Permission"
        btnSms.isEnabled = !isSmsGranted()

        btnOverlay.text = if (isOverlayGranted()) "✓ Overlay Granted" else "Grant Overlay Permission"
        btnOverlay.isEnabled = !isOverlayGranted()

        btnUsage.text = if (isUsageGranted()) "✓ Usage Access Granted" else "Grant Usage Access"
        btnUsage.isEnabled = !isUsageGranted()

        val allGranted = isSmsGranted() && isOverlayGranted() && isUsageGranted()
        btnContinue.isEnabled = allGranted
        statusText.text = if (allGranted)
            "All permissions granted. You're ready."
        else
            "Grant all three permissions below to continue."
    }
}