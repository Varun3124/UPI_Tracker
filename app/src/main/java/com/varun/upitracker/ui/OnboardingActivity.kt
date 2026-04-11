package com.varun.upitracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.sms.SmsBacklogScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    // --- Screens ---
    private lateinit var screenPermissions: LinearLayout
    private lateinit var screenFriends:     LinearLayout
    private lateinit var screenScanning:    LinearLayout

    // --- Permission screen ---
    private lateinit var btnGrantSms:           Button
    private lateinit var btnGrantNotifications: Button
    private lateinit var btnNextToFriends:      Button

    // --- Friends screen ---
    private lateinit var friendsInputContainer: LinearLayout
    private lateinit var btnAddFriendRow:       Button
    private lateinit var btnSkipFriends:        Button
    private lateinit var btnNextToScan:         Button

    // --- Scan screen ---
    private lateinit var tvScanStatus: TextView

    // --- Permission launchers ---
    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionButtons() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionButtons() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Screens
        screenPermissions = findViewById(R.id.screenPermissions)
        screenFriends     = findViewById(R.id.screenFriends)
        screenScanning    = findViewById(R.id.screenScanning)

        // Permission screen
        btnGrantSms           = findViewById(R.id.btnGrantSms)
        btnGrantNotifications = findViewById(R.id.btnGrantNotifications)
        btnNextToFriends      = findViewById(R.id.btnNextToFriends)

        // Friends screen
        friendsInputContainer = findViewById(R.id.friendsInputContainer)
        btnAddFriendRow       = findViewById(R.id.btnAddFriendRow)
        btnSkipFriends        = findViewById(R.id.btnSkipFriends)
        btnNextToScan         = findViewById(R.id.btnNextToScan)

        // Scan screen
        tvScanStatus = findViewById(R.id.tvScanStatus)

        setupPermissionScreen()
        setupFriendsScreen()
        updatePermissionButtons()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
    }

    // ------------------------------------------------------------------
    // Screen 1 — Permissions
    // ------------------------------------------------------------------

    private fun setupPermissionScreen() {
        btnGrantSms.setOnClickListener {
            smsLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }

        btnGrantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Hide notification button entirely on Android < 13
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            btnGrantNotifications.visibility = View.GONE
        }

        btnNextToFriends.setOnClickListener {
            showScreen(screenFriends)
        }
    }

    private fun updatePermissionButtons() {
        val smsGranted = isSmsGranted()

        btnGrantSms.text      = if (smsGranted) "✓ SMS Granted" else "Grant SMS Permission"
        btnGrantSms.isEnabled = !smsGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            btnGrantNotifications.text      = if (notifGranted) "✓ Notifications Granted"
            else "Grant Notification Permission"
            btnGrantNotifications.isEnabled = !notifGranted
        }

        // Only SMS is mandatory to proceed
        btnNextToFriends.isEnabled = smsGranted
    }

    private fun isSmsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Screen 2 — Friends
    // ------------------------------------------------------------------

    private fun setupFriendsScreen() {
        btnAddFriendRow.setOnClickListener { addFriendRow() }
        btnSkipFriends.setOnClickListener  { startScan() }
        btnNextToScan.setOnClickListener   { saveFriendsAndScan() }

        // Start with one empty row
        addFriendRow()
    }

    private fun addFriendRow() {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_friend_input_row, friendsInputContainer, false)

        row.findViewById<TextView>(R.id.btnRemoveRow).setOnClickListener {
            friendsInputContainer.removeView(row)
        }

        friendsInputContainer.addView(row)
    }

    private fun saveFriendsAndScan() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)

            // Collect all filled rows
            for (i in 0 until friendsInputContainer.childCount) {
                val row    = friendsInputContainer.getChildAt(i)
                val name   = row.findViewById<EditText>(R.id.etFriendName).text
                    .toString().trim()
                val upiId  = row.findViewById<EditText>(R.id.etFriendUpiId).text
                    .toString().trim()

                if (name.isEmpty()) continue   // Skip blank rows

                withContext(Dispatchers.IO) {
                    val initials = name.split(" ")
                        .filter { it.isNotEmpty() }.take(2)
                        .joinToString("") { it.first().uppercaseChar().toString() }

                    val friendId = db.friendDao().insertFriend(
                        Friend(
                            name           = name,
                            avatarInitials = initials,
                            addedEpoch     = System.currentTimeMillis()
                        )
                    )

                    if (upiId.isNotEmpty()) {
                        db.friendDao().insertUpiId(
                            FriendUpiId(friendId = friendId, upiId = upiId)
                        )
                    }
                }
            }

            startScan()
        }
    }

    // ------------------------------------------------------------------
    // Screen 3 — Scan
    // ------------------------------------------------------------------

    private fun startScan() {
        showScreen(screenScanning)

        lifecycleScope.launch(Dispatchers.IO) {
            SmsBacklogScanner(applicationContext).scan()

            withContext(Dispatchers.Main) {
                tvScanStatus.text = "Done! Taking you to your dashboard…"
                markOnboardingComplete()
                startActivity(Intent(this@OnboardingActivity, DashboardActivity::class.java))
                finish()
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun showScreen(screen: LinearLayout) {
        screenPermissions.visibility = View.GONE
        screenFriends.visibility     = View.GONE
        screenScanning.visibility    = View.GONE
        screen.visibility            = View.VISIBLE
    }

    private fun markOnboardingComplete() {
        getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_complete", true)
            .apply()
    }
}