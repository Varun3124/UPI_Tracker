package com.varun.upitracker.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.varun.upitracker.R
import com.varun.upitracker.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    // --- Screens ---
    private lateinit var screenPermissions: LinearLayout
    private lateinit var screenFriends: LinearLayout
    private lateinit var screenScanning: LinearLayout

    // --- Permission screen ---
    private lateinit var btnGrantSms: Button
    private lateinit var btnGrantNotifications: Button
    private lateinit var btnNextToFriends: Button

    // --- Friends screen ---
    private lateinit var friendsInputContainer: LinearLayout
    private lateinit var btnAddFriendRow: Button
    private lateinit var btnSkipFriends: Button
    private lateinit var btnNextToScan: Button

    // --- Scan screen ---
    private lateinit var tvScanStatus: TextView

    // --- ViewModel ---
    private val viewModel by lazy {
        ViewModelProvider(
            this,
            OnboardingViewModelFactory(applicationContext)
        )[OnboardingViewModel::class.java]
    }

    // --- Permission launchers ---
    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionButtons() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionButtons() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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


        bindViewModel()
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
        btnSkipFriends.setOnClickListener {
            showScreen(screenScanning)
            viewModel.scanOnly()
        }
        btnNextToScan.setOnClickListener {
            showScreen(screenScanning)
            viewModel.saveFriendsAndScan(collectFriendInputs())
        }

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

    private fun collectFriendInputs(): List<FriendInput> {
        val inputs = mutableListOf<FriendInput>()

        for (i in 0 until friendsInputContainer.childCount) {
            val row = friendsInputContainer.getChildAt(i)
            val name = row.findViewById<EditText>(R.id.etFriendName).text
                .toString()
                .trim()
            val upiId = row.findViewById<EditText>(R.id.etFriendUpiId).text
                .toString()
                .trim()

            if (name.isEmpty()) continue
            inputs.add(FriendInput(name = name, upiId = upiId))
        }

        return inputs
    }

    // ------------------------------------------------------------------
    // Screen 3 — Scan
    // ------------------------------------------------------------------

    private fun bindViewModel() {
        viewModel.uiState.observe(this) { state ->
            if (state.statusMessage.isNotEmpty()) {
                tvScanStatus.text = state.statusMessage
            }
            btnSkipFriends.isEnabled = !state.isBusy
            btnNextToScan.isEnabled = !state.isBusy
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        OnboardingEffect.NavigateToDashboard -> {
                            startActivity(
                                Intent(this@OnboardingActivity, DashboardActivity::class.java)
                            )
                            finish()
                        }

                        is OnboardingEffect.ShowError -> {
                            tvScanStatus.text = effect.message
                        }
                    }
                }
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
}