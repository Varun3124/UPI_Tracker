package com.varun.upitracker.ui.chapter

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.TransactionListAdapter
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.share.RecipientPicker
import com.varun.upitracker.ui.stableId
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** One chapter: what is in it, and where each member stands. */
class ChapterDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER_ID = "chapter_id"
    }

    /** Which of the two sub-pages is showing. */
    private enum class Page { TRANSACTIONS, BALANCES }

    private lateinit var viewModel: ChapterDetailViewModel
    private lateinit var adapter: TransactionListAdapter
    private var chapterId: Long = -1L
    private var state: ChapterDetailUiState = ChapterDetailUiState()
    private var page = Page.TRANSACTIONS

    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter_detail)

        chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (chapterId == -1L) {
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            ChapterViewModelFactory(applicationContext)
        )[ChapterDetailViewModel::class.java]
        padRootForSystemBars(R.id.main)

        findViewById<ImageButton>(R.id.btnBackChapterDetail).setOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this) { onBack() }
        findViewById<ImageButton>(R.id.btnChapterOverflow).setOnClickListener { showOverflow() }
        findViewById<View>(R.id.btnAddChapterTransactions).setOnClickListener {
            startActivity(
                Intent(this, ChapterAddTransactionsActivity::class.java)
                    .putExtra(ChapterAddTransactionsActivity.EXTRA_CHAPTER_ID, chapterId)
            )
        }

        findViewById<View>(R.id.btnChapterTransactions).setOnClickListener { selectPage(Page.TRANSACTIONS) }
        findViewById<View>(R.id.btnChapterBalances).setOnClickListener { selectPage(Page.BALANCES) }
        findViewById<View>(R.id.btnChapterFriendFilter).setOnClickListener { showFriendFilter() }
        findViewById<View>(R.id.btnChapterMerchantFilter).setOnClickListener { showMerchantFilter() }
        wireSelectionBar()

        adapter = TransactionListAdapter(
            dateFmt = dateFormat,
            onTap = { entry ->
                if (state.selectionMode) viewModel.toggleSelection(entry.stableId()) else openEntry(entry)
            },
            onLongPress = { entry -> startSelection(entry) }
        )
        findViewById<RecyclerView>(R.id.rvChapterTransactions).apply {
            layoutManager = LinearLayoutManager(this@ChapterDetailActivity)
            adapter = this@ChapterDetailActivity.adapter
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(chapterId)
    }

    /** Leaving a selection is a step back, not a step off the screen. */
    private fun onBack() {
        if (state.selectionMode) viewModel.exitSelection() else finish()
    }

    private fun selectPage(next: Page) {
        if (page == next) return
        page = next
        // A selection belongs to the list; carrying it onto the balances page would strand the bar.
        viewModel.exitSelection()
        render(state)
    }

    private fun render(ui: ChapterDetailUiState) {
        state = ui
        val chapter = ui.chapter ?: return

        findViewById<TextView>(R.id.tvChapterDetailTitle).text = chapter.name

        val stateLabel = when {
            chapter.state == ChapterState.CLOSED -> "Closed"
            ui.settled -> "Settled"
            else -> "Open"
        }
        val members = if (ui.members.size == 1) "1 member" else "${ui.members.size} members"
        val pending = if (ui.pendingCount > 0) " · ${ui.pendingCount} awaiting review" else ""
        findViewById<TextView>(R.id.tvChapterDetailState).text = "$stateLabel · $members$pending"

        val activeToggle = findViewById<CheckBox>(R.id.switchChapterActive)
        activeToggle.visibility = if (chapter.state == ChapterState.OPEN) View.VISIBLE else View.GONE
        // Set before the listener, so restoring the state does not look like a tap.
        activeToggle.setOnCheckedChangeListener(null)
        activeToggle.isChecked = chapter.isActive
        activeToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setActive(chapterId, isChecked, ::showError)
        }

        renderPage()
        renderBalances(ui)
        renderTransactions(ui)
        renderSelectionBar(ui)
    }

    /** Whichever page is chosen takes the whole weight. */
    private fun renderPage() {
        val onTransactions = page == Page.TRANSACTIONS
        findViewById<View>(R.id.btnChapterTransactions).isSelected = onTransactions
        findViewById<View>(R.id.btnChapterBalances).isSelected = !onTransactions
        findViewById<View>(R.id.chapterTransactionsPage).visibility =
            if (onTransactions) View.VISIBLE else View.GONE
        findViewById<View>(R.id.chapterBalancesPage).visibility =
            if (onTransactions) View.GONE else View.VISIBLE
    }

    private fun renderBalances(ui: ChapterDetailUiState) {
        val container = findViewById<LinearLayout>(R.id.chapterBalancesContainer)
        container.removeAllViews()
        if (ui.balances.isEmpty()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
            row.findViewById<TextView>(R.id.tvChapterLineLabel).text = "Everyone is even."
            row.findViewById<TextView>(R.id.tvChapterLineAmount).text = ""
            container.addView(row)
            return
        }
        ui.balances.forEach { balance ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
            row.findViewById<TextView>(R.id.tvChapterLineLabel).text = balance.label
            val amount = row.findViewById<TextView>(R.id.tvChapterLineAmount)
            if (balance.amountPaise >= 0L) {
                amount.text = "+${formatRupees(balance.amountPaise)}"
                amount.setTextColor(row.themeColor(ThemeAttr.positive))
            } else {
                amount.text = "-${formatRupees(-balance.amountPaise)}"
                amount.setTextColor(row.themeColor(ThemeAttr.negative))
            }
            container.addView(row)
        }
    }

    private fun renderTransactions(ui: ChapterDetailUiState) {
        adapter.submit(ui.entries, ui.rows)
        adapter.updateSelection(ui.selectionMode, ui.selected, ui.shareable)

        findViewById<TextView>(R.id.btnChapterFriendFilter).apply {
            text = ui.friendOptions.firstOrNull { it.id == ui.friendFilterId }?.name ?: "Anyone"
            isSelected = ui.friendFilterId != null
            isEnabled = ui.friendOptions.isNotEmpty()
        }
        findViewById<TextView>(R.id.btnChapterMerchantFilter).apply {
            text = ui.merchantOptions.firstOrNull { it.id == ui.merchantFilterId }?.name ?: "Anywhere"
            isSelected = ui.merchantFilterId != null
            isEnabled = ui.merchantOptions.isNotEmpty()
        }
        findViewById<TextView>(R.id.tvChapterFilterHint).text = if (ui.isFiltered) {
            "${ui.entries.size} of ${ui.totalCount}"
        } else {
            ""
        }
        // Sits on the list's own filter row, so it comes and goes with the Transactions page.
        findViewById<View>(R.id.btnAddChapterTransactions).visibility =
            if (ui.chapter?.state == ChapterState.OPEN) View.VISIBLE else View.GONE

        val empty = findViewById<TextView>(R.id.tvChapterTransactionsEmpty)
        empty.visibility = if (ui.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (ui.isFiltered) "Nothing here matches those." else "Nothing tagged here yet."
    }

    // --- filters ----------------------------------------------------------------------------------

    /**
     * A list of who is actually in this chapter's transactions, not a search box.
     *
     * A chapter is a small, known set of people and places -- that is what makes it a chapter -- so
     * being shown the choices beats being asked to remember them. Each row carries its count, which
     * is the quickest way to see who most of the spending involves.
     */
    private fun showFriendFilter() {
        showOptionPicker("Who", state.friendOptions, state.friendFilterId, "Anyone", viewModel::setFriendFilter)
    }

    private fun showMerchantFilter() {
        showOptionPicker("Where", state.merchantOptions, state.merchantFilterId, "Anywhere", viewModel::setMerchantFilter)
    }

    private fun showOptionPicker(
        title: String,
        options: List<ChapterFilterOption>,
        current: Long?,
        clearLabel: String,
        onPicked: (Long?) -> Unit
    ) {
        if (options.isEmpty()) return
        val labels = listOf(clearLabel) + options.map { option ->
            val tick = if (option.id == current) "✓ " else ""
            "$tick${option.name} (${option.count})"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                onPicked(if (which == 0) null else options[which - 1].id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- selection --------------------------------------------------------------------------------

    /**
     * Sharing is what a selection here is for. Taking rows out of the chapter is on the bar too,
     * because long-press used to be the only way to reach it and it deletes nothing.
     */
    private fun wireSelectionBar() {
        findViewById<View>(R.id.btnSelectAllRows).setOnClickListener { viewModel.selectAllShareable() }
        findViewById<TextView>(R.id.btnSelectionPrimary).apply {
            text = "Share…"
            setOnClickListener { shareSelection() }
        }
        findViewById<TextView>(R.id.btnSelectionSecondary).apply {
            text = "Remove"
            setOnClickListener { confirmRemoveSelection() }
        }
    }

    private fun startSelection(entry: LedgerEntry) {
        if (state.shareable.isEmpty() && state.chapter?.state == ChapterState.CLOSED) {
            showError("Chapter is closed, and none of this can be shared.")
            return
        }
        viewModel.enterSelection(entry.stableId())
    }

    private fun renderSelectionBar(ui: ChapterDetailUiState) {
        val bar = findViewById<View>(R.id.barSelectionActions)
        bar.visibility = if (ui.selectionMode && page == Page.TRANSACTIONS) View.VISIBLE else View.GONE
        if (!ui.selectionMode) return

        findViewById<TextView>(R.id.tvSelectionCount).text = when (ui.selected.size) {
            0 -> "Pick what to share"
            1 -> "1 selected"
            else -> "${ui.selected.size} selected"
        }
        findViewById<View>(R.id.btnSelectionPrimary).isEnabled = ui.selectedTransactionIds.isNotEmpty()
        // R8: a closed chapter is frozen, so nothing can leave it until it is reopened.
        findViewById<View>(R.id.btnSelectionSecondary).isEnabled =
            ui.selected.isNotEmpty() && ui.chapter?.state == ChapterState.OPEN
        findViewById<View>(R.id.btnSelectAllRows).isEnabled = ui.shareable.isNotEmpty()
    }

    private fun shareSelection() {
        val ids = state.selectedTransactionIds
        if (ids.isEmpty()) return
        RecipientPicker(this) { viewModel.load(chapterId) }.show(ids)
        viewModel.exitSelection()
    }

    private fun confirmRemoveSelection() {
        val ids = state.entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in state.selected }
            .map { it.transaction.id }
        if (ids.isEmpty()) return
        val what = if (ids.size == 1) "1 transaction" else "${ids.size} transactions"
        AlertDialog.Builder(this)
            .setTitle("Take $what out of ${state.chapter?.name}?")
            .setMessage("They go back to your ordinary balances. None of them is deleted.")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.untagAll(chapterId, ids.toSet()) { failures -> reportFailures(failures) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One dialog for the whole run: several rows can be refused at once, for different reasons. */
    private fun reportFailures(failures: List<String>) {
        if (failures.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (failures.size == 1) "One was kept" else "${failures.size} were kept")
            .setMessage(failures.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openEntry(entry: LedgerEntry) {
        val tx = (entry as? LedgerEntry.Tx)?.transaction ?: return
        startActivity(
            Intent(this, TransactionEntryActivity::class.java)
                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, tx.id)
        )
    }

    // --- overflow ---------------------------------------------------------------------------------

    private fun showOverflow() {
        val chapter = state.chapter ?: return
        val closeLabel = if (chapter.state == ChapterState.OPEN) "Close chapter" else "Reopen chapter"
        val actions = listOf("Rename", "Members", closeLabel, "Delete chapter")
        AlertDialog.Builder(this)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showRenameDialog()
                    1 -> showMembersDialog()
                    2 -> if (chapter.state == ChapterState.OPEN) confirmClose() else viewModel.reopen(chapterId, ::showError)
                    3 -> confirmDelete()
                }
            }
            .show()
    }

    private fun showRenameDialog() {
        val chapter = state.chapter ?: return
        val input = EditText(this).apply {
            setText(chapter.name)
            setSingleLine()
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename chapter")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    showError("Give it a name")
                } else {
                    viewModel.rename(chapterId, name, chapter.notes, ::showError)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMembersDialog() {
        val members = state.members
        val labels = members.map { it.name } + "Add someone…"
        AlertDialog.Builder(this)
            .setTitle("Members")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == members.size) showAddMembersDialog() else confirmRemoveMember(members[which].id, members[which].name)
            }
            .show()
    }

    private fun showAddMembersDialog() {
        lifecycleScope.launch {
            val addable = viewModel.addableFriends(chapterId)
            if (addable.isEmpty()) {
                showError("Everyone is already in this chapter")
                return@launch
            }
            val checked = BooleanArray(addable.size)
            AlertDialog.Builder(this@ChapterDetailActivity)
                .setTitle("Add members")
                .setMultiChoiceItems(addable.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Add") { _, _ ->
                    val ids = addable.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                    if (ids.isNotEmpty()) viewModel.addMembers(chapterId, ids, ::showError)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmRemoveMember(friendId: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("They stay in every transaction they are already in. This only takes them out of the chapter.")
            .setPositiveButton("Remove") { _, _ -> viewModel.removeMember(chapterId, friendId, ::showError) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Closing an unsettled chapter is allowed, but not without saying who is still out of pocket. */
    private fun confirmClose() {
        if (state.settled) {
            viewModel.close(chapterId, ::showError)
            return
        }
        val outstanding = state.outstanding.joinToString("\n") { "• $it" }
        val pending = if (state.pendingCount > 0) {
            "\n\n${state.pendingCount} transaction(s) here are still awaiting review and count for nothing yet."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Close anyway?")
            .setMessage("This chapter is not settled:\n\n$outstanding$pending\n\nIt keeps counting towards everyone's balance once closed. You can reopen it later.")
            .setPositiveButton("Close chapter") { _, _ -> viewModel.close(chapterId, ::showError) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        val count = state.totalCount
        val fate = if (count == 0) {
            "Nothing is tagged here."
        } else {
            "The $count transaction(s) in it go back to your ordinary balances. None of them is deleted."
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${state.chapter?.name}?")
            .setMessage("$fate\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.delete(chapterId, onDeleted = { finish() }, onError = ::showError)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun padded(view: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = resources.getDimensionPixelSize(R.dimen.space_m)
        setPadding(pad, pad, pad, 0)
        addView(view)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
