package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private lateinit var viewModel: AllTransactionsViewModel
    private lateinit var btnPickMonth: TextView
    private lateinit var btnPendingOnly: TextView

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
            showMonthPicker(viewModel.uiState.value?.selectedMonthStartEpoch ?: startOfCurrentMonth())
        }

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[AllTransactionsViewModel::class.java]

        btnPendingOnly = findViewById(R.id.btnPendingOnly)
        btnPendingOnly.setOnClickListener {
            viewModel.setPendingOnly(viewModel.uiState.value?.pendingOnly != true)
        }

        viewModel.uiState.observe(this) { state ->
            val db = AppDatabase.getInstance(applicationContext)
            findViewById<RecyclerView>(R.id.rvAllTransactions).apply {
                layoutManager = LinearLayoutManager(this@AllTransactionsActivity)
                adapter = AllTransactionsAdapter(
                    entries = state.entries,
                    accountLabels = state.accountLabels,
                    db = db,
                    dateFmt = dateFmt,
                    onTap = ::openEntry,
                    onLongPress = ::showEntryActions
                )
            }
            btnPickMonth.text = monthFmt.format(Date(state.selectedMonthStartEpoch))
            renderPendingToggle(state)
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
     * The app has no selector drawables, so the checked look is built the same way
     * TransactionEntryActivity styles its actor tiles.
     */
    private fun renderPendingToggle(state: AllTransactionsUiState) {
        val active = state.pendingOnly
        btnPendingOnly.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (active) Color.parseColor("#006064") else Color.parseColor("#EEEEEE"))
            setStroke(dp(1), if (active) Color.parseColor("#006064") else Color.parseColor("#DDDDDD"))
        }
        btnPendingOnly.setTextColor(
            if (active) Color.parseColor("#FFFFFF") else Color.parseColor("#212121")
        )

        val hint = findViewById<TextView>(R.id.tvPendingOnlyHint)
        hint.text = if (active) {
            "${state.entries.size} of ${state.totalEntryCount} entries"
        } else {
            ""
        }

        val empty = findViewById<TextView>(R.id.tvAllTransactionsEmpty)
        empty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (active) {
            "Nothing pending review this month."
        } else {
            "No transactions this month."
        }
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
    private val entries: List<LedgerEntry>,
    private val accountLabels: Map<String, String>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (LedgerEntry) -> Unit,
    private val onLongPress: (LedgerEntry) -> Unit
) : RecyclerView.Adapter<AllTransactionsAdapter.VH>() {

    inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvPayee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate: TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvNote: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIou: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.tvDate.text = dateFmt.format(Date(entry.dateEpoch))
        holder.tvIou.text = ""

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
