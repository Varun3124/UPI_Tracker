package com.varun.upitracker.ui.chapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
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
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterShareMode
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.TransactionListAdapter
import com.varun.upitracker.ui.colorAttr
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.share.RecipientPicker
import com.varun.upitracker.ui.stableId
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One chapter: what is in it, and where each member stands.
 *
 * A friend's chapter opens here too, read-only: their plan and their rows as they last sent them, each
 * row marked with whether the user holds it as well (docs/declarations-design.md S3-S7).
 */
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
    private val shortDate = SimpleDateFormat("d MMM", Locale.getDefault())

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
                when {
                    state.copy != null -> openCopyRow(entry)
                    state.selectionMode -> viewModel.toggleSelection(entry.stableId())
                    else -> openEntry(entry)
                }
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

    /** Whatever changed here reaches the chapter's members once the user is done with it (S2). */
    override fun onPause() {
        super.onPause()
        viewModel.publishIfShared()
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
        val copy = ui.copy

        findViewById<TextView>(R.id.tvChapterDetailTitle).text = chapter.name

        val stateLabel = when {
            chapter.state == ChapterState.CLOSED -> "Closed"
            ui.settled -> "Settled"
            else -> "Open"
        }
        val members = if (ui.memberCount == 1) "1 member" else "${ui.memberCount} members"
        val pending = if (ui.pendingCount > 0) " · ${ui.pendingCount} awaiting review" else ""
        val shared = if (chapter.isOwn && chapter.mode == ChapterShareMode.SHARED) " · Shared" else ""
        findViewById<TextView>(R.id.tvChapterDetailState).text = buildString {
            append("$stateLabel · $members$pending$shared")
            if (copy != null) append("\n").append(copyLine(copy))
        }

        // S10: a friend's chapter is never where new transactions go.
        val activeToggle = findViewById<CheckBox>(R.id.switchChapterActive)
        activeToggle.visibility = if (chapter.isOwn && chapter.state == ChapterState.OPEN) View.VISIBLE else View.GONE
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

    /** Whose it is, how it stands and how old it is: a friend's chapter is only ever as new as their last send. */
    private fun copyLine(copy: CopyInfo): String {
        val asOf = shortDate.format(Date(copy.asOfEpoch))
        val status = when (copy.mode) {
            ChapterShareMode.REPLICA -> "${copy.ownerName}'s chapter, kept up to date by them · as of $asOf"
            ChapterShareMode.FROZEN -> "${copy.ownerName}'s chapter · stopped updating on $asOf. It still counts."
            else -> "${copy.ownerName}'s chapter · a copy from $asOf. It never updates."
        }
        val omitted = when (copy.omittedRows) {
            0 -> ""
            1 -> " 1 older transaction was left out; the plan still counts it."
            else -> " ${copy.omittedRows} older transactions were left out; the plan still counts them."
        }
        return status + omitted
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
            addLine(container, "Everyone is even.", "", ThemeAttr.amountNeutral)
        }
        ui.balances.forEach { balance ->
            if (balance.amountPaise >= 0L) {
                addLine(container, balance.label, "+${formatRupees(balance.amountPaise)}", ThemeAttr.positive)
            } else {
                addLine(container, balance.label, "-${formatRupees(-balance.amountPaise)}", ThemeAttr.negative)
            }
        }
        val copy = ui.copy ?: return

        // The owner's plan, never one worked out here: it is what everyone's copy counts (S3).
        if (copy.plan.isNotEmpty()) {
            container.addView(sectionHeader("${copy.ownerName}'s plan"))
            copy.plan.forEach { line ->
                addLine(container, line.text, formatRupees(line.amountPaise), line.direction.colorAttr())
            }
        }
        if (copy.unplaced.isNotEmpty()) {
            container.addView(sectionHeader("Not counted yet"))
            copy.unplaced.forEach { line ->
                addLine(container, line.text, "Say who", ThemeAttr.primary) {
                    copy.people.firstOrNull { it.key == line.personKey }?.let(::choosePerson)
                }
            }
        }
        if (copy.people.isNotEmpty()) {
            container.addView(sectionHeader("Who's who"))
            copy.people.forEach { person ->
                val here = when {
                    person.byLink -> "${person.friendName} · linked"
                    person.friendName != null -> person.friendName
                    else -> "Say who"
                }
                val attr = if (person.friendId == null) ThemeAttr.primary else ThemeAttr.textMuted
                addLine(container, person.name, here, attr) { choosePerson(person) }
            }
        }
    }

    private fun addLine(container: LinearLayout, label: String, amount: String, amountAttr: Int, onTap: (() -> Unit)? = null) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
        row.findViewById<TextView>(R.id.tvChapterLineLabel).text = label
        row.findViewById<TextView>(R.id.tvChapterLineAmount).apply {
            text = amount
            setTextColor(row.themeColor(amountAttr))
        }
        if (onTap != null) {
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener { onTap() }
        }
        container.addView(row)
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_UPI_SectionHeader)
        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_m), 0, resources.getDimensionPixelSize(R.dimen.space_xs))
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
        // Sits on the list's own filter row, so it comes and goes with the Transactions page. What is
        // in a friend's chapter is theirs to say (S6).
        val chapter = ui.chapter
        findViewById<View>(R.id.btnAddChapterTransactions).visibility =
            if (chapter?.isOwn == true && chapter.state == ChapterState.OPEN) View.VISIBLE else View.GONE

        val empty = findViewById<TextView>(R.id.tvChapterTransactionsEmpty)
        empty.visibility = if (ui.entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            ui.isFiltered -> "Nothing here matches those."
            ui.copy != null -> "${ui.copy.ownerName} has not sent any transactions in it."
            else -> "Nothing tagged here yet."
        }
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
        state.copy?.let { copy ->
            showError("Only ${copy.ownerName} can change what is in it. Tap one of your own to open it.")
            return
        }
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
        openTransaction(tx.id)
    }

    /** A row of a friend's chapter opens the user's own copy of it, when there is one (S6). */
    private fun openCopyRow(entry: LedgerEntry) {
        val copy = state.copy ?: return
        val localId = copy.localIds[entry.stableId()]
        if (localId == null) {
            showError("Only ${copy.ownerName} has this one. It counts through ${copy.ownerName}'s plan.")
            return
        }
        openTransaction(localId)
    }

    private fun openTransaction(transactionId: Long) {
        startActivity(
            Intent(this, TransactionEntryActivity::class.java)
                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
        )
    }

    // --- overflow ---------------------------------------------------------------------------------

    private fun showOverflow() {
        val chapter = state.chapter ?: return
        val actions = if (chapter.isOwn) ownActions(chapter) else copyActions()
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun ownActions(chapter: Chapter): List<Pair<String, () -> Unit>> = buildList {
        add("Rename" to ::showRenameDialog)
        add("Members" to ::showMembersDialog)
        if (chapter.mode == ChapterShareMode.SHARED) {
            add("Members' copies" to ::showMembersCopies)
        } else {
            add("Share with members" to { confirmShare(chapter) })
        }
        add("Send a copy to paste…" to ::pickCopyRecipient)
        if (chapter.state == ChapterState.OPEN) {
            add("Close chapter" to ::confirmClose)
        } else {
            add("Reopen chapter" to { viewModel.reopen(chapterId, ::showError) })
        }
        add("Delete chapter" to ::confirmDelete)
    }

    private fun copyActions(): List<Pair<String, () -> Unit>> = listOf(
        "Who's who" to ::showPeople,
        "Remove copy" to ::confirmRemoveCopy
    )

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
        val copies = if (state.chapter?.mode == ChapterShareMode.SHARED) {
            "\n\nMembers' copies are removed too, for everyone the mailbox can reach."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${state.chapter?.name}?")
            .setMessage("$fate$copies\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.delete(chapterId, onDeleted = { finish() }, onError = ::showError)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- sharing: the owner's side ------------------------------------------------------------------

    /** S1: an explicit act, and it says what goes out before anything does. */
    private fun confirmShare(chapter: Chapter) {
        AlertDialog.Builder(this)
            .setTitle("Share ${chapter.name} with its members?")
            .setMessage(
                "Members linked with you get a copy that follows your changes: where everyone stands, the plan, " +
                    "and its transactions with your notes on them -- except ones only you are in. Only you can " +
                    "change it.\n\nAnyone not linked can be sent a copy to paste instead. That one doesn't update."
            )
            .setPositiveButton("Share") { _, _ -> viewModel.share(chapterId, onDone = ::showMessage, onError = ::showError) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Who has the chapter as it stands, who gets it at the next mailbox check, and who can only be pasted one. */
    private fun showMembersCopies() {
        lifecycleScope.launch {
            val recipients = viewModel.recipients(chapterId)
            val labels = recipients.map { recipient ->
                val status = when {
                    recipient.linked && recipient.upToDate -> "has it as it stands"
                    recipient.linked -> "gets it at the next mailbox check"
                    else -> "not linked · tap to send a copy"
                }
                "${recipient.name} · $status"
            }
            val dialog = AlertDialog.Builder(this@ChapterDetailActivity)
                .setTitle("Members' copies")
                .setNeutralButton("Stop sharing") { _, _ -> confirmStopSharing() }
                .setPositiveButton("Close", null)
            if (labels.isEmpty()) {
                dialog.setMessage("Nobody else is in it yet.")
            } else {
                dialog.setItems(labels.toTypedArray()) { _, which ->
                    val recipient = recipients[which]
                    if (!recipient.linked) sendCopy(recipient.friendId, recipient.name)
                }
            }
            dialog.show()
        }
    }

    private fun confirmStopSharing() {
        val name = state.chapter?.name ?: return
        AlertDialog.Builder(this)
            .setTitle("Stop sharing $name?")
            .setMessage("Members keep their copies as they stand now, and those keep counting, but they stop updating.")
            .setPositiveButton("Stop sharing") { _, _ -> viewModel.stopSharing(chapterId, ::showError) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCopyRecipient() {
        lifecycleScope.launch {
            val recipients = viewModel.recipients(chapterId)
            if (recipients.isEmpty()) {
                showError("Add someone to the chapter first.")
                return@launch
            }
            val live = state.chapter?.mode == ChapterShareMode.SHARED
            val labels = recipients.map { recipient ->
                if (live && recipient.linked) "${recipient.name} · already gets it as it changes" else recipient.name
            }
            AlertDialog.Builder(this@ChapterDetailActivity)
                .setTitle("Send a copy to")
                .setItems(labels.toTypedArray()) { _, which ->
                    sendCopy(recipients[which].friendId, recipients[which].name)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** S2: a static copy, written for [name], that they paste and that never updates. */
    private fun sendCopy(friendId: Long, name: String) {
        val chapterName = state.chapter?.name ?: return
        lifecycleScope.launch {
            val text = try {
                viewModel.pastedCopyFor(chapterId, friendId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message ?: "Could not make a copy.")
                return@launch
            }
            AlertDialog.Builder(this@ChapterDetailActivity)
                .setTitle("A copy for $name")
                .setMessage(
                    "$chapterName as it stands now, written for $name. It doesn't update: send another after " +
                        "changes. $name pastes it in Settings and picks you as the sender.\n\nIt shows the amounts, " +
                        "the dates, who was involved and your notes on those transactions."
                )
                .setPositiveButton("Send") { _, _ ->
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Send to $name"
                        )
                    )
                }
                .setNeutralButton("Copy") { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Copy of $chapterName", text))
                    // Android 13 shows its own copy confirmation; a toast on top of it just repeats it.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showMessage("Copied. Paste it to $name.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // --- a friend's chapter --------------------------------------------------------------------------

    private fun showPeople() {
        val copy = state.copy ?: return
        if (copy.people.isEmpty()) {
            showError("Only you and ${copy.ownerName} are in it.")
            return
        }
        val labels = copy.people.map { person ->
            when {
                person.byLink -> "${person.name} · linked as ${person.friendName}"
                person.friendName != null -> "${person.name} · is ${person.friendName}"
                else -> "${person.name} · not placed yet"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Who's who")
            .setItems(labels.toTypedArray()) { _, which -> choosePerson(copy.people[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * S4: who one of the owner's people is here. Always the user's own choice, and always reversible:
     * a matching name is never taken as enough, because placing someone moves money onto them.
     */
    private fun choosePerson(person: CopyPerson) {
        val copy = state.copy ?: return
        if (person.byLink) {
            showError("${person.name} is linked with you, so they count as ${person.friendName}.")
            return
        }
        lifecycleScope.launch {
            val friends = viewModel.friendsForMapping(copy.ownerFriendId)
            val options = listOf("Nobody here") + friends.map { if (it.id == person.friendId) "✓ ${it.name}" else it.name }
            AlertDialog.Builder(this@ChapterDetailActivity)
                .setTitle("Who is ${person.name}?")
                .setItems(options.toTypedArray()) { _, which ->
                    viewModel.mapPerson(chapterId, person.key, friends.getOrNull(which - 1)?.id, ::showError)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmRemoveCopy() {
        val copy = state.copy ?: return
        val name = state.chapter?.name ?: return
        if (!copy.canRemove) {
            showError("${copy.ownerName} keeps it up to date, so it stays while it is shared with you. It can be removed once it stops updating.")
            return
        }
        val yours = when (copy.claimedCount) {
            0 -> ""
            1 -> " The 1 transaction of yours in it goes back to your ordinary balances. It is not deleted."
            else -> " The ${copy.claimedCount} transactions of yours in it go back to your ordinary balances. None is deleted."
        }
        AlertDialog.Builder(this)
            .setTitle("Remove your copy of $name?")
            .setMessage("What it counts between you and ${copy.ownerName}, and anyone else in it, goes with it.$yours")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.removeCopy(chapterId, onRemoved = { finish() }, onError = ::showError)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- plumbing ------------------------------------------------------------------------------

    private fun padded(view: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = resources.getDimensionPixelSize(R.dimen.space_m)
        setPadding(pad, pad, pad, 0)
        addView(view)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
