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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One chapter: who pays whom, where each member stands, and what is in it. */
class ChapterDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER_ID = "chapter_id"
    }

    private lateinit var viewModel: ChapterDetailViewModel
    private var chapterId: Long = -1L
    private var state: ChapterDetailUiState = ChapterDetailUiState()

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

        findViewById<ImageButton>(R.id.btnBackChapterDetail).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnChapterOverflow).setOnClickListener { showOverflow() }
        findViewById<View>(R.id.btnAddChapterTransactions).setOnClickListener {
            startActivity(
                Intent(this, ChapterAddTransactionsActivity::class.java)
                    .putExtra(ChapterAddTransactionsActivity.EXTRA_CHAPTER_ID, chapterId)
            )
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(chapterId)
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

        renderPlan(ui)
        renderBalances(ui)
        renderTransactions(ui)
    }

    private fun renderPlan(ui: ChapterDetailUiState) {
        val container = findViewById<LinearLayout>(R.id.chapterPlanContainer)
        container.removeAllViews()
        findViewById<TextView>(R.id.tvChapterPlanEmpty).visibility =
            if (ui.plan.isEmpty()) View.VISIBLE else View.GONE

        ui.plan.forEach { payment ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
            val label = row.findViewById<TextView>(R.id.tvChapterLineLabel)
            val amount = row.findViewById<TextView>(R.id.tvChapterLineAmount)
            label.text = "${payment.debtorLabel} pays ${payment.creditorLabel}"
            amount.text = formatRupees(payment.amountPaise)
            // The user's own rows are the ones that move their money; the rest are for information.
            val colour = if (payment.involvesMe) ThemeAttr.positive else ThemeAttr.textMuted
            amount.setTextColor(row.themeColor(colour))
            label.setTextColor(row.themeColor(if (payment.involvesMe) ThemeAttr.onSurface else ThemeAttr.textMuted))
            container.addView(row)
        }
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
        val container = findViewById<LinearLayout>(R.id.chapterTransactionsContainer)
        container.removeAllViews()
        findViewById<TextView>(R.id.tvChapterTransactionsEmpty).visibility =
            if (ui.transactions.isEmpty()) View.VISIBLE else View.GONE

        ui.transactions.forEach { tx ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
            val label = row.findViewById<TextView>(R.id.tvChapterLineLabel)
            val pendingMark = if (tx.isPending) " · pending" else ""
            label.text = "${dateFormat.format(Date(tx.dateEpoch))}$pendingMark\n${tx.reason.orEmpty()}".trim()
            row.findViewById<TextView>(R.id.tvChapterLineAmount).text = formatRupees(tx.amountPaise)
            row.setOnClickListener { openTransaction(tx) }
            row.setOnLongClickListener {
                showTransactionActions(tx)
                true
            }
            container.addView(row)
        }
    }

    private fun openTransaction(tx: Transaction) {
        startActivity(
            Intent(this, TransactionEntryActivity::class.java)
                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, tx.id)
        )
    }

    private fun showTransactionActions(tx: Transaction) {
        if (state.chapter?.state == ChapterState.CLOSED) {
            showError("Chapter is closed. Reopen it to change what is in it.")
            return
        }
        AlertDialog.Builder(this)
            .setItems(arrayOf("Open", "Remove from this chapter")) { _, which ->
                if (which == 0) openTransaction(tx) else viewModel.untag(chapterId, tx.id, ::showError)
            }
            .show()
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
        val count = state.transactions.size
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
