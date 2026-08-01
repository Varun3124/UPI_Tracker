package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
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
import com.varun.upitracker.database.entity.Transaction
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
        viewModel.uiState.observe(this) { state ->
            val db = AppDatabase.getInstance(applicationContext)
            findViewById<RecyclerView>(R.id.rvAllTransactions).apply {
                layoutManager = LinearLayoutManager(this@AllTransactionsActivity)
                adapter = AllTransactionsAdapter(
                    transactions = state.transactions,
                    db = db,
                    dateFmt = dateFmt,
                    onTap = { txId ->
                        startActivity(Intent(this@AllTransactionsActivity, TransactionEntryActivity::class.java).apply {
                            putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, txId)
                        })
                    },
                    onLongPress = ::showTransactionActions
                )
            }
            btnPickMonth.text = monthFmt.format(Date(state.selectedMonthStartEpoch))
        }
        viewModel.loadCurrentMonth()
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.loadCurrentMonth()
        }
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

    private fun showTransactionActions(transaction: Transaction) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Delete")) { _, _ -> showDeleteDialog(transaction) }
            .show()
    }

    private fun showDeleteDialog(transaction: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Delete transaction?")
            .setMessage("This will delete the transaction and its shares.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTransaction(transaction.id) }
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
    private val transactions: List<Transaction>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit,
    private val onLongPress: (Transaction) -> Unit
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

    override fun getItemCount() = transactions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = transactions[position]
        CoroutineScope(Dispatchers.Main).launch {
            holder.tvPayee.text = withContext(Dispatchers.IO) { tx.resolvePrimaryDisplay(db) }
        }
        holder.tvDate.text = dateFmt.format(Date(tx.dateEpoch))
        holder.tvAmount.text = tx.formatPerspectiveAmount()
        holder.tvAmount.setTextColor(tx.perspectiveColor())
        holder.tvNote.text = tx.resolveTypeLabel()
        holder.tvIou.text = ""
        holder.itemView.setOnClickListener { onTap(tx.id) }
        holder.itemView.setOnLongClickListener {
            onLongPress(tx)
            true
        }
    }
}

private fun Transaction.perspectiveColor(): Int = when (amountPerspective()) {
    AmountPerspective.OUTGOING -> Color.parseColor("#C62828")
    AmountPerspective.INCOMING -> Color.parseColor("#2E7D32")
    AmountPerspective.NEUTRAL -> Color.parseColor("#AAAAAA")
}
