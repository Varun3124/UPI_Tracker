package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.DatePickerDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
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
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AllTransactionsActivity : AppCompatActivity() {

    private companion object {
        const val ALL_ACCOUNTS = "All accounts"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val shortDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private lateinit var viewModel: AllTransactionsViewModel
    private lateinit var btnPickMonth: TextView
    private lateinit var btnPendingOnly: TextView
    private lateinit var btnAccountFilter: TextView
    private lateinit var cbShowBalance: CheckBox
    private var transactionsAdapter: AllTransactionsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_transactions)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.btnBackAll).setOnClickListener { finish() }
        btnPickMonth = findViewById(R.id.btnPickMonth)
        btnPickMonth.setOnClickListener {
            showMonthPicker(viewModel.uiState.value?.rangeStartEpoch ?: startOfCurrentMonth())
        }
        btnPickMonth.setOnLongClickListener {
            showRangePicker()
            true
        }

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[AllTransactionsViewModel::class.java]

        btnPendingOnly = findViewById(R.id.btnPendingOnly)
        btnPendingOnly.setOnClickListener {
            viewModel.setPendingOnly(viewModel.uiState.value?.pendingOnly != true)
        }

        btnAccountFilter = findViewById(R.id.btnAccountFilter)
        btnAccountFilter.setOnClickListener { showAccountFilterMenu() }

        cbShowBalance = findViewById(R.id.cbShowBalance)
        cbShowBalance.setOnCheckedChangeListener { _, checked -> viewModel.setShowBalance(checked) }

        findViewById<RecyclerView>(R.id.rvAllTransactions).layoutManager =
            LinearLayoutManager(this)

        viewModel.uiState.observe(this) { state ->
            renderList(state)
            btnPickMonth.text = rangeLabel(state)
            renderFilterBar(state)
        }
        viewModel.loadCurrentMonth()
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.loadCurrentMonth()
        }
    }

    /**
     * Rebinds rather than re-attaching whenever only the display flags changed: swapping the
     * adapter would throw away the scroll position every time the Balance box is ticked.
     */
    private fun renderList(state: AllTransactionsUiState) {
        val recycler = findViewById<RecyclerView>(R.id.rvAllTransactions)
        val existing = transactionsAdapter
        if (existing != null && existing.entries === state.entries) {
            existing.updateBalances(state.runningBalances, state.showBalance)
            return
        }
        transactionsAdapter = AllTransactionsAdapter(
            entries = state.entries,
            accountLabels = state.accountLabels,
            db = AppDatabase.getInstance(applicationContext),
            dateFmt = dateFmt,
            balances = state.runningBalances,
            showBalance = state.showBalance,
            onTap = ::openEntry,
            onLongPress = ::showEntryActions
        ).also { recycler.adapter = it }
    }

    private fun rangeLabel(state: AllTransactionsUiState): String {
        if (!state.isCustomRange) return monthFmt.format(Date(state.rangeStartEpoch))
        // The stored end is exclusive; show the inclusive day the user actually picked.
        val lastDay = Date(state.rangeEndExclusiveEpoch - 1)
        return "${shortDateFmt.format(Date(state.rangeStartEpoch))} - ${shortDateFmt.format(lastDay)}"
    }

    /** Long-press on the month button: pick From, then To. Both ends inclusive. */
    private fun showRangePicker() {
        val state = viewModel.uiState.value ?: return
        pickDate("Range starts", state.rangeStartEpoch) { fromEpoch ->
            pickDate("Range ends", maxOf(fromEpoch, state.rangeEndExclusiveEpoch - 1)) { toEpoch ->
                if (toEpoch < fromEpoch) {
                    Toast.makeText(this, "End date is before the start date", Toast.LENGTH_SHORT).show()
                    return@pickDate
                }
                // toEpoch is the start of the chosen day; the range must cover all of it.
                viewModel.loadRange(fromEpoch, toEpoch + DAY_MILLIS, isCustom = true)
            }
        }
    }

    private fun pickDate(title: String, initialEpoch: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialEpoch }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                onPicked(
                    Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle(title) }.show()
    }

    private fun renderBalanceRow(state: AllTransactionsUiState, scope: String?) {
        findViewById<TextView>(R.id.tvBalanceScope).text = when {
            !state.hasBalance -> "No cash or savings account"
            scope != null -> scope
            else -> "Cash + savings"
        }
        val opening = findViewById<TextView>(R.id.tvOpeningBalance)
        val closing = findViewById<TextView>(R.id.tvClosingBalance)
        if (state.hasBalance) {
            opening.text = formatRupees(state.openingBalancePaise)
            closing.text = formatRupees(state.closingBalancePaise)
        } else {
            opening.text = "-"
            closing.text = "-"
        }

        cbShowBalance.isEnabled = state.hasBalance
        if (cbShowBalance.isChecked != state.showBalance) {
            cbShowBalance.setOnCheckedChangeListener(null)
            cbShowBalance.isChecked = state.showBalance
            cbShowBalance.setOnCheckedChangeListener { _, checked ->
                viewModel.setShowBalance(checked)
            }
        }
    }

    private fun renderFilterBar(state: AllTransactionsUiState) {
        stylePill(btnPendingOnly, state.pendingOnly)

        val scope = state.selectedAccountId?.let { id ->
            state.accounts.firstOrNull { it.id == id }?.label ?: state.accountLabels[id]
        }
        btnAccountFilter.text = scope ?: ALL_ACCOUNTS
        stylePill(btnAccountFilter, scope != null)

        findViewById<TextView>(R.id.tvFilterHint).text = if (state.isFiltered) {
            "${state.entries.size} of ${state.totalEntryCount}"
        } else {
            ""
        }

        renderBalanceRow(state, scope)

        val empty = findViewById<TextView>(R.id.tvAllTransactionsEmpty)
        empty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.pendingOnly && scope != null -> "Nothing pending review in $scope this month."
            state.pendingOnly -> "Nothing pending review this month."
            scope != null -> "Nothing on $scope this month."
            else -> "No transactions this month."
        }
    }

    /**
     * The app has no selector drawables, so the checked look is built the same way
     * TransactionEntryActivity styles its actor tiles.
     */
    private fun stylePill(view: TextView, active: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (active) Color.parseColor("#006064") else Color.parseColor("#EEEEEE"))
            setStroke(dp(1), if (active) Color.parseColor("#006064") else Color.parseColor("#DDDDDD"))
        }
        view.setTextColor(if (active) Color.parseColor("#FFFFFF") else Color.parseColor("#212121"))
    }

    private fun showAccountFilterMenu() {
        val state = viewModel.uiState.value ?: return
        val popup = PopupMenu(this, btnAccountFilter)
        popup.menu.add(Menu.NONE, 0, 0, ALL_ACCOUNTS)
        state.accounts.forEachIndexed { index, account ->
            popup.menu.add(Menu.NONE, index + 1, index + 1, account.label)
        }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setAccountFilter(
                if (item.itemId == 0) null else state.accounts.getOrNull(item.itemId - 1)?.id
            )
            true
        }
        popup.show()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun showMonthPicker(monthStartEpoch: Long) {
        val selected = Calendar.getInstance().apply { timeInMillis = monthStartEpoch }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val months = DateFormatSymbols.getInstance(Locale.getDefault()).months.take(12).toTypedArray()

        val monthPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 11
            displayedValues = months
            value = selected.get(Calendar.MONTH)
            wrapSelectorWheel = true
        }
        val yearPicker = NumberPicker(this).apply {
            minValue = 2000
            maxValue = currentYear + 5
            value = selected.get(Calendar.YEAR).coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
        }
        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(24, 12, 24, 0)
            addView(monthPicker)
            addView(yearPicker)
        }

        AlertDialog.Builder(this)
            .setTitle("Select month")
            .setView(pickerRow)
            .setPositiveButton("Show") { _, _ ->
                val monthStart = Calendar.getInstance().apply {
                    set(Calendar.YEAR, yearPicker.value)
                    set(Calendar.MONTH, monthPicker.value)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                viewModel.loadMonth(monthStart)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openEntry(entry: LedgerEntry) {
        val intent = Intent(this, TransactionEntryActivity::class.java).apply {
            when (entry) {
                is LedgerEntry.Tx ->
                    putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, entry.transaction.id)
                is LedgerEntry.Transfer ->
                    putExtra(TransactionEntryActivity.EXTRA_TRANSFER_ID, entry.transfer.id)
            }
        }
        startActivity(intent)
    }

    private fun showEntryActions(entry: LedgerEntry) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Delete")) { _, _ -> showDeleteDialog(entry) }
            .show()
    }

    private fun showDeleteDialog(entry: LedgerEntry) {
        val (title, message) = when (entry) {
            is LedgerEntry.Tx ->
                "Delete transaction?" to "This will delete the transaction and its shares."
            is LedgerEntry.Transfer ->
                "Delete transfer?" to "This will delete the account transfer."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                when (entry) {
                    is LedgerEntry.Tx -> viewModel.deleteTransaction(entry.transaction.id)
                    is LedgerEntry.Transfer -> viewModel.deleteTransfer(entry.transfer.id) { error ->
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOfCurrentMonth(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

class AllTransactionsAdapter(
    val entries: List<LedgerEntry>,
    private val accountLabels: Map<String, String>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private var balances: Map<String, Long>,
    private var showBalance: Boolean,
    private val onTap: (LedgerEntry) -> Unit,
    private val onLongPress: (LedgerEntry) -> Unit
) : RecyclerView.Adapter<AllTransactionsAdapter.VH>() {

    fun updateBalances(updated: Map<String, Long>, show: Boolean) {
        if (show == showBalance && updated == balances) return
        balances = updated
        showBalance = show
        notifyDataSetChanged()
    }

    inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvPayee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate: TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvNote: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIou: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
        val tvBalance: TextView = view.findViewById(R.id.tvFriendTxBalance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.tvDate.text = dateFmt.format(Date(entry.dateEpoch))
        holder.tvIou.text = ""

        val balance = balances[entry.stableId()]
        if (showBalance && balance != null) {
            holder.tvBalance.visibility = android.view.View.VISIBLE
            holder.tvBalance.text = formatRupees(balance)
        } else {
            holder.tvBalance.visibility = android.view.View.GONE
        }

        when (entry) {
            is LedgerEntry.Tx -> {
                val tx = entry.transaction
                CoroutineScope(Dispatchers.Main).launch {
                    holder.tvPayee.text = withContext(Dispatchers.IO) { tx.resolvePrimaryDisplay(db) }
                }
                holder.tvAmount.text = tx.formatPerspectiveAmount()
                holder.tvAmount.setTextColor(tx.perspectiveColor())
                holder.tvNote.text = tx.resolveTypeLabel()
            }

            is LedgerEntry.Transfer -> {
                val transfer = entry.transfer
                holder.tvPayee.text = transfer.resolvePrimaryDisplay()
                holder.tvAmount.text = transfer.formatTransferAmount()
                holder.tvAmount.setTextColor(AmountPerspective.NEUTRAL.color())
                holder.tvNote.text = transfer.resolveRouteLabel(accountLabels)
            }
        }

        holder.itemView.setOnClickListener { onTap(entry) }
        holder.itemView.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }
}
