package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import android.widget.ImageButton
import com.varun.upitracker.domain.BalanceConfidence
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.dp

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
    private lateinit var btnThirdPartyOnly: TextView
    private lateinit var cbShowBalance: CheckBox
    private var transactionsAdapter: AllTransactionsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_transactions)

        padRootForSystemBars(R.id.main)

        findViewById<ImageButton>(R.id.btnBackAll).setOnClickListener { finish() }
        btnPickMonth = findViewById(R.id.btnPickMonth)
        btnPickMonth.setOnClickListener {
            val state = viewModel.uiState.value
            val initial = if (state == null || state.isAllTime) startOfCurrentMonth() else state.rangeStartEpoch
            showMonthPicker(initial)
        }
        btnPickMonth.setOnLongClickListener {
            showRangeChoiceMenu()
            true
        }
        wireMonthSwipeGesture()

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

        btnThirdPartyOnly = findViewById(R.id.btnThirdPartyOnly)
        btnThirdPartyOnly.setOnClickListener {
            viewModel.setThirdPartyOnly(viewModel.uiState.value?.thirdPartyOnly != true)
        }

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
            existing.updateBalances(
                state.runningBalances, state.showBalance, state.balanceCertainFromEpoch
            )
            return
        }
        transactionsAdapter = AllTransactionsAdapter(
            entries = state.entries,
            accountLabels = state.accountLabels,
            db = AppDatabase.getInstance(applicationContext),
            dateFmt = dateFmt,
            balances = state.runningBalances,
            showBalance = state.showBalance,
            balanceCertainFromEpoch = state.balanceCertainFromEpoch,
            onTap = ::openEntry,
            onLongPress = ::showEntryActions
        ).also { recycler.adapter = it }
    }

    private fun rangeLabel(state: AllTransactionsUiState): String {
        if (state.isAllTime) return "All time"
        if (!state.isCustomRange) return monthFmt.format(Date(state.rangeStartEpoch))
        // The stored end is exclusive; show the inclusive day the user actually picked.
        val lastDay = Date(state.rangeEndExclusiveEpoch - 1)
        return "${shortDateFmt.format(Date(state.rangeStartEpoch))} - ${shortDateFmt.format(lastDay)}"
    }

    /** Long-press on the month button: "All time" jumps straight there; otherwise pick a range. */
    private fun showRangeChoiceMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("All time", "Custom date range")) { _, which ->
                if (which == 0) viewModel.loadAllTime() else showRangePicker()
            }
            .show()
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

    /**
     * Any vertical drag on the month button changes the month on finger-up, whatever its length or
     * speed — up for next, down for previous. Only [ViewConfiguration.getScaledTouchSlop] worth of
     * movement is required, purely to tell a drag apart from a stationary tap; there is no minimum
     * swipe distance or fling velocity beyond that.
     *
     * Implemented as a raw [View.OnTouchListener] rather than a [android.view.GestureDetector]
     * because a fling detector requires velocity and is built to reject slow drags, which is
     * exactly what this needs to accept. Once slop is crossed we dispatch ACTION_CANCEL into the
     * button's own touch handling so its pressed state clears and no click fires alongside the
     * swipe; a movement that stays under slop is left untouched so tap and long-press keep working.
     */
    private fun wireMonthSwipeGesture() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var isDragging = false

        btnPickMonth.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && kotlin.math.abs(event.rawY - startY) > touchSlop) {
                        isDragging = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val movedUp = event.rawY - startY < 0
                        viewModel.shiftMonth(if (movedUp) 1 else -1)
                    }
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    false
                }
                else -> false
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

    private fun speculationColor(speculative: Boolean): Int =
        themeColor(if (speculative) ThemeAttr.speculative else ThemeAttr.onSurfaceVariant)

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
            // A range that opens before the first reconciliation starts from a figure the app
            // worked out. It can close on a counted one, so the two are judged separately.
            opening.setTextColor(speculationColor(state.isOpeningSpeculative))
            closing.setTextColor(speculationColor(state.isClosingSpeculative))
        } else {
            opening.text = "-"
            closing.text = "-"
            opening.setTextColor(speculationColor(false))
            closing.setTextColor(speculationColor(false))
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
        btnPendingOnly.isSelected = state.pendingOnly
        btnThirdPartyOnly.isSelected = state.thirdPartyOnly

        val scope = state.selectedAccountId?.let { id ->
            state.accounts.firstOrNull { it.id == id }?.label ?: state.accountLabels[id]
        }
        btnAccountFilter.text = scope ?: ALL_ACCOUNTS
        btnAccountFilter.isSelected = scope != null

        findViewById<TextView>(R.id.tvFilterHint).text = if (state.isFiltered) {
            "${state.entries.size} of ${state.totalEntryCount}"
        } else {
            ""
        }

        renderBalanceRow(state, scope)

        val empty = findViewById<TextView>(R.id.tvAllTransactionsEmpty)
        empty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (state.isFiltered) {
            val reasons = buildList {
                if (state.pendingOnly) add("pending review")
                if (state.thirdPartyOnly) add("with neither side me")
                if (scope != null) add("on $scope")
            }
            "Nothing ${reasons.joinToString(", ")} this month."
        } else {
            "No transactions this month."
        }
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
                    is LedgerEntry.Tx -> viewModel.deleteTransaction(entry.transaction.id) { error ->
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
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
    private var balanceCertainFromEpoch: Long?,
    private val onTap: (LedgerEntry) -> Unit,
    private val onLongPress: (LedgerEntry) -> Unit
) : RecyclerView.Adapter<AllTransactionsAdapter.VH>() {

    fun updateBalances(updated: Map<String, Long>, show: Boolean, certainFromEpoch: Long?) {
        if (show == showBalance && updated == balances && certainFromEpoch == balanceCertainFromEpoch) {
            return
        }
        balances = updated
        showBalance = show
        balanceCertainFromEpoch = certainFromEpoch
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
            // Everything before the first reconciliation is worked out from the transaction record
            // alone, which is the part nobody has ever checked. Coloured on both paths so a
            // recycled row cannot keep the brown.
            val speculative = BalanceConfidence.isSpeculative(entry.dateEpoch, balanceCertainFromEpoch)
            holder.tvBalance.setTextColor(
                holder.tvBalance.themeColor(
                    if (speculative) ThemeAttr.speculative else ThemeAttr.textMuted
                )
            )
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
                holder.tvAmount.setTextColor(tx.perspectiveColor(holder.tvAmount.context))
                holder.tvNote.text = tx.resolveTypeLabel()
            }

            is LedgerEntry.Transfer -> {
                val transfer = entry.transfer
                holder.tvPayee.text = transfer.resolvePrimaryDisplay()
                holder.tvAmount.text = transfer.formatTransferAmount()
                holder.tvAmount.setTextColor(AmountPerspective.NEUTRAL.color(holder.tvAmount.context))
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
