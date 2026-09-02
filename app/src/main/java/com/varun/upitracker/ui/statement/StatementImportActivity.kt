package com.varun.upitracker.ui.statement

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.data.repository.MatchCandidate
import com.varun.upitracker.data.repository.UnresolvedGroup
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ui.resolvePrimaryDisplay
import com.varun.upitracker.ui.settings.AppViewModelFactory
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Imports an HDFC `.xls` statement in two states: a setup form, then a review of what the import
 * would do. Nothing is written until the user commits.
 */
class StatementImportActivity : AppCompatActivity() {

    private lateinit var viewModel: StatementImportViewModel
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var suppressAccountCallback = false
    private var groupAdapter: GroupAdapter? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.loadFile(it, ::toast) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement_import)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        viewModel = ViewModelProvider(this, AppViewModelFactory(this))[StatementImportViewModel::class.java]

        findViewById<TextView>(R.id.btnBackImport).setOnClickListener { onBack() }
        findViewById<TextView>(R.id.btnPickFile).setOnClickListener {
            // Many file providers report a bank export as octet-stream, so accept anything and let
            // the reader reject what it cannot open.
            filePicker.launch(arrayOf("application/vnd.ms-excel", "application/octet-stream", "*/*"))
        }
        findViewById<TextView>(R.id.btnProcess).setOnClickListener { viewModel.process(::toast) }
        findViewById<TextView>(R.id.btnFromDate).setOnClickListener { pickDate(true) }
        findViewById<TextView>(R.id.btnToDate).setOnClickListener { pickDate(false) }
        findViewById<TextView>(R.id.btnCommit).setOnClickListener { commit() }

        findViewById<Spinner>(R.id.spinnerAccount).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressAccountCallback) return
                    viewModel.uiState.value?.accounts?.getOrNull(position)?.let {
                        viewModel.selectAccount(it.id)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        findViewById<RecyclerView>(R.id.rvGroups).layoutManager = LinearLayoutManager(this)

        viewModel.uiState.observe(this) { render(it) }
        viewModel.loadAccounts()

        onBackPressedDispatcher.addCallback(this) { onBack() }
    }

    /** From the review state, back returns to setup rather than leaving the screen. */
    private fun onBack() {
        if (viewModel.uiState.value?.plan != null) viewModel.backToSetup() else finish()
    }

    private fun render(state: StatementImportUiState) {
        val inReview = state.plan != null
        findViewById<View>(R.id.panelSetup).visibility = if (inReview) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panelReview).visibility = if (inReview) View.VISIBLE else View.GONE

        renderSetup(state)
        if (inReview) renderReview(state)
    }

    private fun renderSetup(state: StatementImportUiState) {
        findViewById<TextView>(R.id.tvFileSummary).text = when {
            state.busy -> "Reading…"
            state.fileName != null -> "${state.fileName} — ${state.rowCount} rows"
            else -> "No file chosen yet."
        }
        findViewById<TextView>(R.id.btnFromDate).text =
            state.fromEpoch?.let { "From ${dateFmt.format(Date(it))}" } ?: "From —"
        findViewById<TextView>(R.id.btnToDate).text =
            state.toEpoch?.let { "To ${dateFmt.format(Date(it))}" } ?: "To —"

        val process = findViewById<TextView>(R.id.btnProcess)
        process.isEnabled = state.canProcess
        process.alpha = if (state.canProcess) 1f else 0.4f

        val spinner = findViewById<Spinner>(R.id.spinnerAccount)
        val labels = state.accounts.map { it.label }
        val existing = (spinner.adapter as? ArrayAdapter<*>)?.let { adapter ->
            (0 until adapter.count).map { adapter.getItem(it) as String }
        }
        if (existing != labels) {
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
        }
        val selectedIndex = state.accounts.indexOfFirst { it.id == state.selectedAccountId }
        if (selectedIndex >= 0 && spinner.selectedItemPosition != selectedIndex) {
            suppressAccountCallback = true
            spinner.setSelection(selectedIndex)
            suppressAccountCallback = false
        }
    }

    private fun renderReview(state: StatementImportUiState) {
        val plan = state.plan ?: return

        findViewById<TextView>(R.id.tvResolvedCount).text =
            "${plan.resolved.size} UPI ${if (plan.resolved.size == 1) "entry" else "entries"} resolved"

        val skipped = findViewById<TextView>(R.id.tvSkippedCount)
        if (plan.alreadyImported > 0) {
            skipped.visibility = View.VISIBLE
            skipped.text = "${plan.alreadyImported} rows already imported"
        } else {
            skipped.visibility = View.GONE
        }

        val summaryCard = findViewById<CardView>(R.id.cardResolvedSummary)
        summaryCard.isClickable = plan.resolved.isNotEmpty()
        findViewById<View>(R.id.tvResolvedChevron).visibility =
            if (plan.resolved.isEmpty()) View.INVISIBLE else View.VISIBLE
        summaryCard.setOnClickListener {
            if (plan.resolved.isEmpty()) return@setOnClickListener
            startActivity(
                StatementResolvedActivity.createIntent(
                    this,
                    plan.resolved.map { it.transaction.id }.toLongArray()
                )
            )
        }

        findViewById<View>(R.id.tvReviewEmpty).visibility =
            if (plan.unresolved.isEmpty()) View.VISIBLE else View.GONE

        // Rebind rather than re-attach on a selection change: swapping the adapter would throw
        // away the scroll position on every tap.
        val recycler = findViewById<RecyclerView>(R.id.rvGroups)
        val adapter = groupAdapter
        if (adapter == null || adapter.groups !== plan.unresolved) {
            groupAdapter = GroupAdapter(
                groups = plan.unresolved,
                selections = state.selections,
                db = AppDatabase.getInstance(applicationContext),
                dateFmt = dateFmt,
                onToggle = viewModel::toggleSelection,
                onOpen = ::openTransaction
            ).also { recycler.adapter = it }
        } else {
            adapter.updateSelections(state.selections)
        }

        val commit = findViewById<TextView>(R.id.btnCommit)
        val pending = state.pendingCount
        commit.text = when {
            plan.resolved.isEmpty() && pending == 0 -> "Finish"
            pending == 0 -> "Apply ${plan.resolved.size + state.selections.size} updates"
            else -> "Create $pending pending operation${if (pending == 1) "" else "s"}"
        }
        commit.isEnabled = !state.busy
        commit.alpha = if (state.busy) 0.4f else 1f
    }

    private fun commit() {
        viewModel.commit(
            onDone = { result ->
                toast("Imported: ${result.created} pending, ${result.enriched} updated")
                finish()
            },
            onError = ::toast
        )
    }

    private fun openTransaction(transactionId: Long) {
        startActivity(
            Intent(this, TransactionEntryActivity::class.java)
                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
        )
    }

    private fun pickDate(isFrom: Boolean) {
        val state = viewModel.uiState.value ?: return
        val initial = (if (isFrom) state.fromEpoch else state.toEpoch) ?: System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (isFrom) viewModel.setFromEpoch(picked) else viewModel.setToEpoch(picked)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

/**
 * One card per statement row we could not resolve by ref id, with its candidate matches nested
 * inside. Candidates are inflated directly rather than via a nested RecyclerView — a group holds a
 * handful at most.
 */
private class GroupAdapter(
    val groups: List<UnresolvedGroup>,
    private var selections: Map<Int, Long>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onToggle: (Int, Long) -> Unit,
    private val onOpen: (Long) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    fun updateSelections(updated: Map<Int, Long>) {
        if (updated == selections) return
        selections = updated
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val amount: TextView = view.findViewById(R.id.tvGroupAmount)
        val date: TextView = view.findViewById(R.id.tvGroupDate)
        val label: TextView = view.findViewById(R.id.tvGroupLabel)
        val status: TextView = view.findViewById(R.id.tvGroupStatus)
        val candidates: ViewGroup = view.findViewById(R.id.containerCandidates)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_statement_group, parent, false)
    )

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        val row = group.row
        val selectedId = selections[position]

        val sign = if (row.direction == "DEBIT") "-" else "+"
        holder.amount.text = "$sign Rs ${row.amountPaise / 100}"
        holder.amount.setTextColor(
            if (row.direction == "DEBIT") Color.parseColor("#C62828") else Color.parseColor("#2E7D32")
        )
        holder.date.text = dateFmt.format(Date(row.dateEpoch))
        holder.label.text = row.displayLabel

        holder.status.text = when {
            selectedId != null -> "Matched — the ticked transaction will be updated"
            group.candidates.isEmpty() -> "No match found — will create a pending transaction"
            else -> "Tap a match if this is already recorded, or leave it to create a pending transaction"
        }
        holder.status.setTextColor(
            if (selectedId != null) Color.parseColor("#2E7D32") else Color.parseColor("#FF6F00")
        )

        holder.candidates.removeAllViews()
        val inflater = LayoutInflater.from(holder.itemView.context)
        group.candidates.forEach { candidate ->
            holder.candidates.addView(
                buildCandidateView(inflater, holder.candidates, position, candidate, selectedId)
            )
        }
    }

    private fun buildCandidateView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        groupIndex: Int,
        candidate: MatchCandidate,
        selectedId: Long?
    ): View {
        val view = inflater.inflate(R.layout.item_statement_candidate, parent, false)
        val transaction = candidate.transaction
        val isSelected = selectedId == transaction.id

        val name = view.findViewById<TextView>(R.id.tvCandidateName)
        name.text = transaction.payerRawLabel ?: transaction.payeeRawLabel ?: "Unknown"
        // resolvePrimaryDisplay hits the database, so fill the raw label first and refine after.
        CoroutineScope(Dispatchers.Main).launch {
            name.text = withContext(Dispatchers.IO) { transaction.resolvePrimaryDisplay(db) }
        }

        view.findViewById<TextView>(R.id.tvCandidateAmount).text =
            "Rs ${transaction.amountPaise / 100}"
        view.findViewById<TextView>(R.id.tvCandidateDate).text =
            dateFmt.format(Date(transaction.dateEpoch))
        view.findViewById<TextView>(R.id.tvCandidateAliasHint).visibility =
            if (candidate.aliasMatch) View.VISIBLE else View.GONE

        val check = view.findViewById<TextView>(R.id.tvCandidateCheck)
        check.visibility = if (isSelected) View.VISIBLE else View.GONE
        if (isSelected) {
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2E7D32"))
            }
        }

        val card = view.findViewById<CardView>(R.id.cardCandidate)
        card.setCardBackgroundColor(
            if (isSelected) Color.parseColor("#E8F5E9") else Color.parseColor("#FFFFFF")
        )
        card.cardElevation = if (isSelected) dp(view, 4) else dp(view, 1)
        card.setOnClickListener { onToggle(groupIndex, transaction.id) }
        card.setOnLongClickListener {
            onOpen(transaction.id)
            true
        }
        return view
    }

    private fun dp(view: View, value: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        view.resources.displayMetrics
    )
}
