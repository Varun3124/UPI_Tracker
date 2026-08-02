package com.varun.upitracker.ui.settings

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.data.repository.AccountCreateRequest
import com.varun.upitracker.data.repository.AccountDeleteResult
import com.varun.upitracker.data.repository.FixedDepositCreateRequest
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.database.entity.EntrySource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AccountsActivity : AppCompatActivity() {

    private lateinit var viewModel: AccountsViewModel
    private lateinit var adapter: AccountsAdapter
    private lateinit var tvEmpty: TextView
    private var state = AccountsUiState()
    private val expandedAccountIds = mutableSetOf<String>()
    private val dateTimeFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        viewModel = ViewModelProvider(
            this,
            AppViewModelFactory(applicationContext)
        )[AccountsViewModel::class.java]

        tvEmpty = findViewById(R.id.tvEmptyAccounts)
        adapter = AccountsAdapter(
            expandedIds = expandedAccountIds,
            dateTimeFmt = dateTimeFmt,
            onToggle = { accountId ->
                if (!expandedAccountIds.add(accountId)) expandedAccountIds.remove(accountId)
                adapter.notifyDataSetChanged()
            },
            onActions = ::showAccountActions,
            onAddSnapshot = { account -> showSnapshotDialog(account, null) },
            onEditSnapshot = { snapshot -> showSnapshotDialog(null, snapshot) },
            onDeleteSnapshot = ::showDeleteSnapshotDialog
        )

        findViewById<TextView>(R.id.btnBackAccounts).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddAccount).setOnClickListener { showAddAccountChoice() }
        findViewById<RecyclerView>(R.id.rvAccounts).apply {
            layoutManager = LinearLayoutManager(this@AccountsActivity)
            adapter = this@AccountsActivity.adapter
        }

        viewModel.state.observe(this) { newState ->
            state = newState
            adapter.submit(newState.rows)
            tvEmpty.visibility = if (newState.rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun showAddAccountChoice() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Account", "Fixed deposit")) { _, which ->
                if (which == 0) showRegularAccountDialog() else showFixedDepositDialog()
            }
            .show()
    }

    private fun showRegularAccountDialog() {
        val accountTypes = listOf(
            AccountType.CASH,
            AccountType.SAVINGS,
            AccountType.INVESTMENT_INVESTED,
            AccountType.INVESTMENT_UNINVESTED
        )
        val labelInput = editText("Label")
        val openingInput = amountInput("Opening balance")
        val defaultCheck = CheckBox(this).apply { text = "Set as default for this type" }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AccountsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                accountTypes.map { it.displayName() }
            )
        }
        val form = formLayout().apply {
            addView(labelInput)
            addView(typeSpinner)
            addView(openingInput)
            addView(defaultCheck)
        }

        AlertDialog.Builder(this)
            .setTitle("Add account")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val type = accountTypes[typeSpinner.selectedItemPosition]
                viewModel.createAccount(
                    AccountCreateRequest(
                        label = labelInput.text.toString(),
                        type = type,
                        openingBalancePaise = openingInput.text.toString().toPaiseOrNull(),
                        isDefault = defaultCheck.isChecked
                    ),
                    ::showError
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFixedDepositDialog() {
        val sourceAccounts = state.sourceAccounts
        if (sourceAccounts.isEmpty()) {
            showError("Create a cash or savings source account first.")
            return
        }
        val labelInput = editText("Label")
        val principalInput = amountInput("Principal")
        val defaultCheck = CheckBox(this).apply { text = "Set as default FD account" }
        val sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AccountsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                sourceAccounts.map { it.label }
            )
        }
        var bookedEpoch = System.currentTimeMillis()
        var maturityEpoch = Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.timeInMillis
        val bookedButton = dateButton(bookedEpoch)
        val maturityButton = dateButton(maturityEpoch)
        bookedButton.setOnClickListener {
            pickDateTime(bookedEpoch) {
                bookedEpoch = it
                bookedButton.text = dateTimeFmt.format(Date(it))
            }
        }
        maturityButton.setOnClickListener {
            pickDateTime(maturityEpoch) {
                maturityEpoch = it
                maturityButton.text = dateTimeFmt.format(Date(it))
            }
        }
        val form = formLayout().apply {
            addView(labelInput)
            addView(sourceSpinner)
            addView(principalInput)
            addView(label("Booked"))
            addView(bookedButton)
            addView(label("Maturity"))
            addView(maturityButton)
            addView(defaultCheck)
        }

        AlertDialog.Builder(this)
            .setTitle("Add fixed deposit")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val principal = principalInput.text.toString().toPaiseOrNull()
                if (principal == null) {
                    showError("Enter a valid principal.")
                    return@setPositiveButton
                }
                viewModel.createFixedDeposit(
                    FixedDepositCreateRequest(
                        label = labelInput.text.toString(),
                        sourceAccountId = sourceAccounts[sourceSpinner.selectedItemPosition].id,
                        principalPaise = principal,
                        bookedEpoch = bookedEpoch,
                        maturityEpoch = maturityEpoch,
                        source = EntrySource.MANUAL,
                        isDefault = defaultCheck.isChecked
                    ),
                    ::showError
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccountActions(account: Account) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Rename", "Set default", "Archive or delete")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(account)
                    1 -> viewModel.setDefault(account.id, ::showError)
                    2 -> showArchiveOrDeleteDialog(account)
                }
            }
            .show()
    }

    private fun showRenameDialog(account: Account) {
        val input = editText("Label").apply {
            setText(account.label)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename account")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                viewModel.updateAccountLabel(account.id, input.text.toString(), ::showError)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showArchiveOrDeleteDialog(account: Account) {
        AlertDialog.Builder(this)
            .setTitle("Archive or delete?")
            .setMessage("Accounts with history are archived. Accounts with no history are deleted.")
            .setPositiveButton("Continue") { _, _ ->
                viewModel.deleteOrArchive(
                    account.id,
                    onResult = { result ->
                        val message = when (result) {
                            AccountDeleteResult.Archived -> "Account archived."
                            AccountDeleteResult.Deleted -> "Account deleted."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    },
                    onError = ::showError
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSnapshotDialog(account: Account?, snapshot: BalanceSnapshot?) {
        val targetAccount = account ?: state.rows.firstOrNull { it.account.id == snapshot?.accountId }?.account
        if (targetAccount == null) {
            showError("Account not found.")
            return
        }
        var snapshotEpoch = snapshot?.snapshotEpoch ?: System.currentTimeMillis()
        val amountInput = amountInput("Amount").apply {
            snapshot?.let {
                setText((it.balancePaise / 100.0).toString())
                setSelection(text.length)
            }
        }
        val notesInput = editText("Notes").apply {
            setText(snapshot?.notes.orEmpty())
            setSelection(text.length)
        }
        val sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AccountsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BalanceSnapshotSource.entries.map { it.name }
            )
            setSelection(snapshot?.source?.ordinal ?: BalanceSnapshotSource.MANUAL.ordinal)
        }
        val dateButton = dateButton(snapshotEpoch).apply {
            setOnClickListener {
                pickDateTime(snapshotEpoch) {
                    snapshotEpoch = it
                    text = dateTimeFmt.format(Date(it))
                }
            }
        }
        val form = formLayout().apply {
            addView(label(targetAccount.label))
            addView(dateButton)
            addView(amountInput)
            addView(sourceSpinner)
            addView(notesInput)
        }

        AlertDialog.Builder(this)
            .setTitle(if (snapshot == null) "Add snapshot" else "Edit snapshot")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val amount = amountInput.text.toString().toPaiseOrNull()
                if (amount == null) {
                    showError("Enter a valid amount.")
                    return@setPositiveButton
                }
                val source = BalanceSnapshotSource.entries[sourceSpinner.selectedItemPosition]
                if (snapshot == null) {
                    viewModel.addSnapshot(
                        targetAccount.id,
                        snapshotEpoch,
                        amount,
                        source,
                        notesInput.text.toString(),
                        ::showError
                    )
                } else {
                    viewModel.updateSnapshot(
                        snapshot.copy(
                            snapshotEpoch = snapshotEpoch,
                            balancePaise = amount,
                            source = source,
                            notes = notesInput.text.toString().takeIf { it.isNotBlank() }
                        ),
                        ::showError
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSnapshotDialog(snapshot: BalanceSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("Delete snapshot?")
            .setMessage("This removes the balance point from this account.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteSnapshot(snapshot.id, ::showError) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDateTime(initialEpoch: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialEpoch }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(year, month, day, hour, minute, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        onPicked(cal.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun formLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 12, 48, 0)
    }

    private fun editText(hintValue: String) = EditText(this).apply {
        hint = hintValue
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun amountInput(hintValue: String) = EditText(this).apply {
        hint = hintValue
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#757575"))
        setPadding(0, 12, 0, 0)
    }

    private fun dateButton(epoch: Long) = TextView(this).apply {
        text = dateTimeFmt.format(Date(epoch))
        textSize = 15f
        setTextColor(android.graphics.Color.parseColor("#212121"))
        setPadding(0, 12, 0, 12)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private class AccountsAdapter(
    private val expandedIds: Set<String>,
    private val dateTimeFmt: SimpleDateFormat,
    private val onToggle: (String) -> Unit,
    private val onActions: (Account) -> Unit,
    private val onAddSnapshot: (Account) -> Unit,
    private val onEditSnapshot: (BalanceSnapshot) -> Unit,
    private val onDeleteSnapshot: (BalanceSnapshot) -> Unit
) : RecyclerView.Adapter<AccountsAdapter.VH>() {

    private val rows = mutableListOf<AccountRowUi>()

    fun submit(items: List<AccountRowUi>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_account_row, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val account = row.account
        val expanded = expandedIds.contains(account.id)
        holder.tvLabel.text = account.label
        holder.tvMeta.text = buildMeta(account)
        holder.tvBalance.text = row.balancePaise.formatPaise()
        holder.header.setOnClickListener { onToggle(account.id) }
        holder.header.setOnLongClickListener {
            onActions(account)
            true
        }
        holder.snapshotContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) bindSnapshots(holder.snapshotContainer, row)
    }

    private fun bindSnapshots(container: LinearLayout, row: AccountRowUi) {
        container.removeAllViews()
        if (row.snapshots.isEmpty()) {
            container.addView(snapshotText(container, "No snapshots yet"))
        } else {
            row.snapshots.forEach { snapshot ->
                val line = LinearLayout(container.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                line.addView(snapshotText(line, "${dateTimeFmt.format(Date(snapshot.snapshotEpoch))}\n${snapshot.balancePaise.formatPaise()} • ${snapshot.source.name}").apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                line.addView(actionText(line, "Edit") { onEditSnapshot(snapshot) })
                line.addView(actionText(line, "Delete") { onDeleteSnapshot(snapshot) })
                container.addView(line)
            }
        }
        container.addView(actionText(container, "+ Add snapshot") { onAddSnapshot(row.account) }.apply {
            setPadding(0, 12, 0, 0)
        })
    }

    private fun snapshotText(parent: ViewGroup, value: String) = TextView(parent.context).apply {
        text = value
        textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#616161"))
    }

    private fun actionText(parent: ViewGroup, value: String, action: () -> Unit) = TextView(parent.context).apply {
        text = value
        textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#1E88E5"))
        setPadding(16, 6, 0, 6)
        setOnClickListener { action() }
    }

    private fun buildMeta(account: Account): String {
        val flags = listOfNotNull(
            account.type.displayName(),
            if (account.isDefault) "Default" else null,
            if (account.isArchived) "Archived" else null
        )
        return flags.joinToString(" • ")
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val header: View = view.findViewById(R.id.accountHeader)
        val tvLabel: TextView = view.findViewById(R.id.tvAccountLabel)
        val tvMeta: TextView = view.findViewById(R.id.tvAccountMeta)
        val tvBalance: TextView = view.findViewById(R.id.tvAccountBalance)
        val snapshotContainer: LinearLayout = view.findViewById(R.id.snapshotContainer)
    }
}

private fun AccountType.displayName(): String = name.lowercase()
    .split("_")
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

private fun Long.formatPaise(): String = "Rs${"%.2f".format(this / 100.0)}"

private fun String.toPaiseOrNull(): Long? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.toDoubleOrNull()?.let { (it * 100).toLong() }
}
