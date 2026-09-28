package com.varun.upitracker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.statistics.ListWindow
import com.varun.upitracker.domain.statistics.PayeeRef
import com.varun.upitracker.ui.share.RecipientPicker
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import com.varun.upitracker.util.AmountFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class AllTransactionsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_WINDOW_START = "window_start"
        private const val EXTRA_WINDOW_END = "window_end_exclusive"
        private const val EXTRA_WINDOW_CUSTOM = "window_custom"
        private const val EXTRA_WINDOW_ALL_TIME = "window_all_time"
        private const val EXTRA_MERCHANT_ID = "merchant_id"
        private const val EXTRA_FRIEND_ID = "friend_id"
        private const val EXTRA_PAYEE_NAME = "payee_name"

        /**
         * The list narrowed to one payee over [window]: what tapping a merchant on Statistics opens.
         *
         * A payee never saved as a merchant or a friend has no id to filter on, so it goes into the
         * search box by name instead, which finds the same rows by their raw label.
         */
        fun forPayee(context: Context, payee: PayeeRef, name: String, window: ListWindow): Intent =
            Intent(context, AllTransactionsActivity::class.java).apply {
                putExtra(EXTRA_WINDOW_START, window.startEpoch)
                putExtra(EXTRA_WINDOW_END, window.endExclusiveEpoch)
                putExtra(EXTRA_WINDOW_CUSTOM, window.isCustom)
                putExtra(EXTRA_WINDOW_ALL_TIME, window.isAllTime)
                when (payee) {
                    is PayeeRef.Merchant -> putExtra(EXTRA_MERCHANT_ID, payee.merchantId)
                    is PayeeRef.Friend -> putExtra(EXTRA_FRIEND_ID, payee.friendId)
                    PayeeRef.Unmapped -> Unit
                }
                putExtra(EXTRA_PAYEE_NAME, name)
            }
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private lateinit var viewModel: AllTransactionsViewModel
    private lateinit var monthControl: MonthRangeControl
    private lateinit var btnPendingOnly: TextView
    private lateinit var btnAccountFilter: TextView
    private lateinit var btnAmountRange: TextView
    private lateinit var btnThirdPartyOnly: TextView
    private lateinit var btnPayeeFilter: TextView
    private lateinit var cbShowBalance: CheckBox
    private lateinit var etSearch: EditText
    private lateinit var adapter: TransactionListAdapter

    /** Held so the state can fill the box back in without the watcher treating it as typing. */
    private var searchWatcher: TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_transactions)

        padRootForSystemBars(R.id.main)

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[AllTransactionsViewModel::class.java]
        // Once only: after a rotation the ViewModel already holds this, plus whatever changed since.
        if (savedInstanceState == null) applyLaunchExtras()

        findViewById<ImageButton>(R.id.btnBackAll).setOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this) { onBack() }

        monthControl = MonthRangeControl(this, findViewById(R.id.btnPickMonth), viewModel::load)
        monthControl.attach()

        btnPendingOnly = findViewById(R.id.btnPendingOnly)
        btnPendingOnly.setOnClickListener {
            viewModel.setPendingOnly(viewModel.uiState.value?.pendingOnly != true)
        }

        btnThirdPartyOnly = findViewById(R.id.btnThirdPartyOnly)
        btnThirdPartyOnly.setOnClickListener {
            viewModel.setThirdPartyOnly(viewModel.uiState.value?.thirdPartyOnly != true)
        }

        btnAccountFilter = findViewById(R.id.btnAccountFilter)
        btnAccountFilter.setOnClickListener { showScopePicker() }

        btnAmountRange = findViewById(R.id.btnAmountRange)
        btnAmountRange.setOnClickListener { showAmountRangeDialog() }

        btnPayeeFilter = findViewById(R.id.btnPayeeFilter)
        btnPayeeFilter.setOnClickListener { viewModel.clearPayee() }

        cbShowBalance = findViewById(R.id.cbShowBalance)
        cbShowBalance.setOnCheckedChangeListener { _, checked -> viewModel.setShowBalance(checked) }

        wireSearchBox()
        wireSelectionBar()

        adapter = TransactionListAdapter(
            dateFmt = dateFmt,
            onTap = { entry ->
                if (viewModel.uiState.value?.selectionMode == true) {
                    viewModel.toggleSelection(entry.stableId())
                } else {
                    openEntry(entry)
                }
            },
            onLongPress = { entry -> viewModel.enterSelection(entry.stableId()) }
        )
        findViewById<RecyclerView>(R.id.rvAllTransactions).apply {
            layoutManager = LinearLayoutManager(this@AllTransactionsActivity)
            adapter = this@AllTransactionsActivity.adapter
        }

        viewModel.uiState.observe(this) { render(it) }
        viewModel.reload()
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) viewModel.reload()
    }

    /** Leaving a selection is a step back, not a step off the screen. */
    private fun onBack() {
        if (viewModel.uiState.value?.selectionMode == true) viewModel.exitSelection() else finish()
    }

    /** What [forPayee] packed. An ordinary launch carries none of it and opens on this month. */
    private fun applyLaunchExtras() {
        val name = intent.getStringExtra(EXTRA_PAYEE_NAME) ?: return
        val window = ListWindow(
            startEpoch = intent.getLongExtra(EXTRA_WINDOW_START, 0L),
            endExclusiveEpoch = intent.getLongExtra(EXTRA_WINDOW_END, Long.MAX_VALUE),
            isCustom = intent.getBooleanExtra(EXTRA_WINDOW_CUSTOM, false),
            isAllTime = intent.getBooleanExtra(EXTRA_WINDOW_ALL_TIME, false)
        )
        val ref = when {
            intent.hasExtra(EXTRA_MERCHANT_ID) -> PayeeRef.Merchant(intent.getLongExtra(EXTRA_MERCHANT_ID, 0L))
            intent.hasExtra(EXTRA_FRIEND_ID) -> PayeeRef.Friend(intent.getLongExtra(EXTRA_FRIEND_ID, 0L))
            else -> null
        }
        viewModel.openOn(
            window = window,
            payee = ref?.let { PayeeFilter(it, name) },
            query = if (ref == null) name else "",
            scope = AccountScope.Total
        )
    }

    private fun render(state: AllTransactionsUiState) {
        adapter.submit(state.entries, state.rows)
        adapter.updateSelection(state.selectionMode, state.selected)
        monthControl.render(state.window)
        renderFilterBar(state)
        renderSelectionBar(state)
    }

    // --- search ---------------------------------------------------------------------------------

    private fun wireSearchBox() {
        etSearch = findViewById(R.id.etTxSearch)
        findViewById<View>(R.id.btnClearSearch).setOnClickListener {
            etSearch.setText("")
            etSearch.clearFocus()
        }
        searchWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setQuery(s?.toString().orEmpty())
            }
        }.also { etSearch.addTextChangedListener(it) }
    }

    /**
     * Only ever writes to the box when it genuinely disagrees, and detaches the watcher while doing
     * so -- the same guard the Balance checkbox needs below. Writing on every emission would put the
     * cursor back to the start of the word being typed.
     */
    private fun renderSearchBox(query: String) {
        if (etSearch.text.toString() != query) {
            val watcher = searchWatcher
            etSearch.removeTextChangedListener(watcher)
            etSearch.setText(query)
            etSearch.setSelection(query.length)
            etSearch.addTextChangedListener(watcher)
        }
        findViewById<View>(R.id.btnClearSearch).visibility =
            if (query.isEmpty()) View.GONE else View.VISIBLE
    }

    // --- filters --------------------------------------------------------------------------------

    private fun showScopePicker() {
        val state = viewModel.uiState.value ?: return
        AccountScopePicker(this, title = "Show accounts")
            .show(state.accounts, state.scope, viewModel::setScope)
    }

    /**
     * Min and max in whole rupees, either optional.
     *
     * Applied from the button rather than as the fields change: a half-typed bound would filter the
     * list down to nothing on the way to the number the user meant.
     */
    private fun showAmountRangeDialog() {
        val state = viewModel.uiState.value ?: return
        val minInput = amountField("Least", state.minPaise)
        val maxInput = amountField("Most", state.maxPaise)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_l)
            setPadding(pad, pad, pad, 0)
            addView(minInput)
            addView(maxInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Amount between")
            .setView(container)
            .setPositiveButton("Apply", null)
            .setNeutralButton("Clear") { _, _ -> viewModel.setAmountRange(null, null) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val min = AmountFormat.paiseOrNull(minInput.text.toString())
                val max = AmountFormat.paiseOrNull(maxInput.text.toString())
                if (min != null && max != null && min > max) {
                    // Left open, so the number that has to change is still in front of the user.
                    Toast.makeText(this, "The least is more than the most", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.setAmountRange(min, max)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun amountField(hint: String, paise: Long?): EditText = EditText(this).apply {
        this.hint = hint
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine()
        paise?.let { setText(AmountFormat.forInput(it)) }
    }

    /** Whichever bounds are set, phrased the way they were asked for. */
    private fun amountRangeLabel(state: AllTransactionsUiState): String {
        val min = state.minPaise
        val max = state.maxPaise
        return when {
            min != null && max != null -> "${AmountFormat.rupees(min)} - ${AmountFormat.rupees(max)}"
            min != null -> "≥ ${AmountFormat.rupees(min)}"
            max != null -> "≤ ${AmountFormat.rupees(max)}"
            else -> "Amount"
        }
    }

    private fun renderFilterBar(state: AllTransactionsUiState) {
        btnPendingOnly.isSelected = state.pendingOnly
        btnThirdPartyOnly.isSelected = state.thirdPartyOnly

        // Never offered as a choice here -- only Statistics narrows to a payee -- so once cleared
        // it is gone rather than left behind as an empty pill.
        val payee = state.payee
        btnPayeeFilter.visibility = if (payee == null) View.GONE else View.VISIBLE
        btnPayeeFilter.isSelected = payee != null
        btnPayeeFilter.text = payee?.let { "${it.name}  ✕" }.orEmpty()

        val scopeName = scopeLabel(state.scope, state.accounts)
        btnAccountFilter.text = scopeName
        // Liquid is the default, so it is not a filter the user has to be reminded of.
        btnAccountFilter.isSelected = state.scope != AccountScope.Liquid

        btnAmountRange.text = amountRangeLabel(state)
        btnAmountRange.isSelected = state.minPaise != null || state.maxPaise != null

        renderSearchBox(state.query)
        findViewById<TextView>(R.id.tvFilterHint).text = if (state.isFiltered) {
            "${state.entries.size} of ${state.totalEntryCount}"
        } else {
            ""
        }

        renderBalanceRow(state, scopeName)

        val empty = findViewById<TextView>(R.id.tvAllTransactionsEmpty)
        empty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = emptyMessage(state, scopeName)
    }

    /** Says what is being hidden, so an empty list is never a mystery. */
    private fun emptyMessage(state: AllTransactionsUiState, scopeName: String): String {
        val span = if (state.window.isAllTime) "at all" else "in ${monthControl.label(state.window)}"
        if (!state.isFiltered) return "No transactions $span."
        val reasons = buildList {
            state.payee?.let { add("with ${it.name}") }
            if (state.pendingOnly) add("pending review")
            if (state.thirdPartyOnly) add("with neither side me")
            if (state.scope != AccountScope.Liquid) add("in $scopeName")
            if (state.minPaise != null || state.maxPaise != null) add(amountRangeLabel(state))
            if (state.query.isNotBlank()) add("matching \"${state.query.trim()}\"")
        }
        return "Nothing ${reasons.joinToString(", ")} $span."
    }

    private fun renderBalanceRow(state: AllTransactionsUiState, scopeName: String) {
        findViewById<TextView>(R.id.tvBalanceScope).text =
            if (state.hasBalance) scopeName else "No account in this scope"
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
            cbShowBalance.setOnCheckedChangeListener { _, checked -> viewModel.setShowBalance(checked) }
        }
    }

    private fun speculationColor(speculative: Boolean): Int =
        themeColor(if (speculative) ThemeAttr.speculative else ThemeAttr.onSurfaceVariant)

    // --- selection ------------------------------------------------------------------------------

    private fun wireSelectionBar() {
        findViewById<View>(R.id.btnSelectAllRows).setOnClickListener { viewModel.selectAllVisible() }
        findViewById<TextView>(R.id.btnSelectionPrimary).apply {
            text = "Share…"
            setOnClickListener { shareSelection() }
        }
        findViewById<TextView>(R.id.btnSelectionSecondary).apply {
            text = "Delete"
            setOnClickListener { confirmDeleteSelection() }
        }
        findViewById<TextView>(R.id.btnSelectionTertiary).apply {
            text = "Chapter…"
            visibility = View.VISIBLE
            setOnClickListener { pickChapterForSelection() }
        }
    }

    private fun renderSelectionBar(state: AllTransactionsUiState) {
        findViewById<View>(R.id.barSelectionActions).visibility =
            if (state.selectionMode) View.VISIBLE else View.GONE
        if (!state.selectionMode) return

        findViewById<TextView>(R.id.tvSelectionCount).text = when (state.selected.size) {
            0 -> "Pick what to act on"
            1 -> "1 selected"
            else -> "${state.selected.size} selected"
        }
        findViewById<View>(R.id.btnSelectionSecondary).isEnabled = state.selected.isNotEmpty()
        // A transfer between your own accounts is nobody else's business, so a selection holding
        // nothing but transfers has nothing to share -- and nothing a chapter could hold either.
        findViewById<View>(R.id.btnSelectionPrimary).isEnabled = state.selectedTransactionIds.isNotEmpty()
        findViewById<View>(R.id.btnSelectionTertiary).isEnabled = state.selectedTransactionIds.isNotEmpty()
        findViewById<View>(R.id.btnSelectAllRows).isEnabled = state.entries.isNotEmpty()
    }

    private fun shareSelection() {
        val ids = viewModel.uiState.value?.selectedTransactionIds.orEmpty()
        if (ids.isEmpty()) return
        // RecipientPicker owns who may receive what, and the cap on how many at a time.
        RecipientPicker(this) { viewModel.reload() }.show(ids)
        viewModel.exitSelection()
    }

    /**
     * Which chapter the selection goes into: an open one, or a new one named here. Straight to
     * naming when nothing is open, since a list holding only "New chapter…" is one tap for nothing.
     */
    private fun pickChapterForSelection() {
        val count = viewModel.uiState.value?.selectedTransactionIds?.size ?: 0
        if (count == 0) return
        lifecycleScope.launch {
            val chapters = viewModel.openChapters()
            if (chapters.isEmpty()) return@launch askNewChapterName()
            val labels = chapters.map { if (it.isActive) "${it.name} (active)" else it.name } + "New chapter…"
            AlertDialog.Builder(this@AllTransactionsActivity)
                .setTitle("Add ${plural(count, "transaction")} to")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == chapters.size) {
                        askNewChapterName()
                    } else {
                        viewModel.addSelectedToChapter(chapters[which].id, ::reportChapterOutcome)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Only a name: tagging brings in whoever the transactions involve, so there is nobody to pick. */
    private fun askNewChapterName() {
        val input = EditText(this).apply {
            hint = "Name"
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_l)
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("New chapter")
            .setMessage("Everyone these transactions involve joins it.")
            .setView(container)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    // Left open, as the amount dialog is, so the field is still there to fill in.
                    Toast.makeText(this, "Give it a name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.addSelectedToNewChapter(name, ::reportChapterOutcome)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * What went in, and who joined to make it work (R5), as a toast; a dialog only when something was
     * refused, because each refusal carries a reason worth reading -- the same split delete makes.
     */
    private fun reportChapterOutcome(outcome: ChapterTagOutcome) {
        val summary = buildList {
            if (outcome.taggedCount > 0) {
                add("Added ${plural(outcome.taggedCount, "transaction")} to ${outcome.chapterName}")
            }
            if (outcome.addedMembers.isNotEmpty()) add("${outcome.addedMembers.joinToString(", ")} joined it")
        }
        if (summary.isNotEmpty()) Toast.makeText(this, summary.joinToString(". "), Toast.LENGTH_LONG).show()
        if (outcome.failedCount == 0) return
        AlertDialog.Builder(this)
            .setTitle(if (outcome.failedCount == 1) "One was not added" else "${outcome.failedCount} were not added")
            .setMessage(outcome.failureReasons.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDeleteSelection() {
        val state = viewModel.uiState.value ?: return
        val selected = state.entries.filter { it.stableId() in state.selected }
        if (selected.isEmpty()) return
        val transactions = selected.count { it is LedgerEntry.Tx }
        val transfers = selected.size - transactions
        val what = listOfNotNull(
            transactions.takeIf { it > 0 }?.let { plural(it, "transaction") },
            transfers.takeIf { it > 0 }?.let { plural(it, "transfer") }
        ).joinToString(" and ")

        AlertDialog.Builder(this)
            .setTitle("Delete $what?")
            .setMessage("This deletes them and their shares. It cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteSelected { failures -> reportFailures(failures) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * One dialog for the whole run rather than a toast per row: a bulk delete can be blocked on
     * several rows at once, and a queue of toasts would show the last reason only.
     */
    private fun reportFailures(failures: List<String>) {
        if (failures.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (failures.size == 1) "One was kept" else "${failures.size} were kept")
            .setMessage(failures.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun plural(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"

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
}
