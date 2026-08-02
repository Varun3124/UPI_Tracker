package com.varun.upitracker.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.varun.upitracker.database.entity.AccountType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    private lateinit var screenAccounts: LinearLayout
    private lateinit var screenScanning: LinearLayout

    // --- Permission screen ---
    private lateinit var btnGrantSms: Button
    private lateinit var btnGrantNotifications: Button
    private lateinit var btnNextToAccount: Button

    // --- Accounts screen ---
    private lateinit var accountInputContainer: LinearLayout
    private lateinit var btnAddAccountRow: Button
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
        screenAccounts     = findViewById(R.id.screenAccounts)
        screenScanning    = findViewById(R.id.screenScanning)

        // Permission screen
        btnGrantSms           = findViewById(R.id.btnGrantSms)
        btnGrantNotifications = findViewById(R.id.btnGrantNotifications)
        btnNextToAccount      = findViewById(R.id.btnNextToAccounts)

        // Accounts screen
        accountInputContainer = findViewById(R.id.accountInputContainer)
        btnAddAccountRow       = findViewById(R.id.btnAddAccountRow)
        btnNextToScan         = findViewById(R.id.btnNextToScan)

        // Scan screen
        tvScanStatus = findViewById(R.id.tvScanStatus)

        showScreen(screenPermissions)
        bindViewModel()
        setupPermissionScreen()
        setupAccountsScreen()
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

        btnNextToAccount.setOnClickListener {
            showScreen(screenAccounts)
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
        btnNextToAccount.isEnabled = smsGranted
    }

    private fun isSmsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Screen 2 — Accounts
    // ------------------------------------------------------------------

    private fun setupAccountsScreen() {
        btnAddAccountRow.setOnClickListener { addAccountRow() }
        btnNextToScan.setOnClickListener {
            val inputs = collectAccountInputs()
            if (inputs.isEmpty()) {
                tvScanStatus.text = "Please add at least one account."
                return@setOnClickListener
            }
            showScreen(screenScanning)
            viewModel.saveAccountsAndScan(inputs)
        }

        // Start with one empty row
        addAccountRow()

        // Make the first row compulsory and default its type to SAVINGS
        if (accountInputContainer.childCount > 0) {
            val firstRow = accountInputContainer.getChildAt(0)
            val spFirst = firstRow.findViewById<Spinner>(R.id.spAccountType)
            val savingsIndex = AccountType.values().indexOf(AccountType.SAVINGS)
            if (savingsIndex >= 0) spFirst.setSelection(savingsIndex)

            // Hide/remove button for the compulsory row
            val btnRemoveFirst = firstRow.findViewById<TextView>(R.id.btnRemoveRow)
            btnRemoveFirst.visibility = View.GONE

            // Prefill label to 'Savings' if empty
            val etLabel = firstRow.findViewById<EditText>(R.id.etAccountLabel)
            if (etLabel.text.toString().isBlank()) etLabel.setText("Savings")
        }
    }

    private fun addAccountRow() {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_account_input_row, accountInputContainer, false)

        val btnRemove = row.findViewById<TextView>(R.id.btnRemoveRow)
        btnRemove.setOnClickListener {
            accountInputContainer.removeView(row)
        }

        // Setup spinner with AccountType values
        val sp = row.findViewById<Spinner>(R.id.spAccountType)
        val types = AccountType.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter

        // Snapshot picker: set tag to epoch when selected
        val etSnapshot = row.findViewById<EditText>(R.id.etSnapshotEpoch)
        etSnapshot.setOnClickListener {
            showDateTimePicker(etSnapshot)
        }

        accountInputContainer.addView(row)
    }

    private fun collectAccountInputs(): List<AccountInput> {
        val inputs = mutableListOf<AccountInput>()

        for (i in 0 until accountInputContainer.childCount) {
            val row = accountInputContainer.getChildAt(i)
            val label = row.findViewById<EditText>(R.id.etAccountLabel).text
                .toString()
                .trim()
            if (label.isEmpty()) continue

            val sp = row.findViewById<Spinner>(R.id.spAccountType)
            val type = AccountType.values()[sp.selectedItemPosition]

            val balanceText = row.findViewById<EditText>(R.id.etInitialBalance).text
                .toString()
                .trim()
            val initialPaise = try {
                BigDecimal(balanceText).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            } catch (e: Exception) {
                0L
            }

            val snapshotView = row.findViewById<EditText>(R.id.etSnapshotEpoch)
            val snapshotEpoch = (snapshotView.tag as? Long) ?: System.currentTimeMillis()

            inputs.add(
                AccountInput(
                    label = label,
                    type = type,
                    initialBalancePaise = initialPaise,
                    snapshotEpoch = snapshotEpoch
                )
            )
        }

        return inputs
    }

    private fun showDateTimePicker(target: EditText) {
        val cal = Calendar.getInstance()
        val dateSet = DatePickerDialog(this, { _, year, month, dayOfMonth ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            TimePickerDialog(this, { _, hourOfDay, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                val epoch = cal.timeInMillis
                target.tag = epoch
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                target.setText(fmt.format(cal.time))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()

        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dateSet.show()
    }

    // ------------------------------------------------------------------
    // Screen 3 — Scan
    // ------------------------------------------------------------------

    private fun bindViewModel() {
        viewModel.uiState.observe(this) { state ->
            if (state.statusMessage.isNotEmpty()) {
                tvScanStatus.text = state.statusMessage
            }
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
        screenAccounts.visibility     = View.GONE
        screenScanning.visibility    = View.GONE
        screen.visibility            = View.VISIBLE
    }
}