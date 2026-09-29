package com.varun.upitracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.data.declaration.DeclarationRepository
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.domain.mailbox.InviteCode
import com.varun.upitracker.domain.mailbox.KeyFingerprint
import com.varun.upitracker.ui.chapter.ChapterDetailActivity
import com.varun.upitracker.ui.declaration.AgreementDialogs
import com.varun.upitracker.ui.declaration.AgreementText
import com.varun.upitracker.ui.mailbox.MailboxActivity
import com.varun.upitracker.ui.share.RecipientPicker
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import com.varun.upitracker.util.AmountFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FriendDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRIEND_ID = "friend_id"
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val shortDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private lateinit var viewModel: FriendDetailViewModel
    private lateinit var adapter: TransactionListAdapter
    private lateinit var monthControl: MonthRangeControl
    private lateinit var etSearch: EditText
    private var friendId: Long = -1L
    private var friendName: String = "your friend"
    private var searchWatcher: TextWatcher? = null
    private lateinit var agreementDialogs: AgreementDialogs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend_detail)

        padRootForSystemBars(R.id.main)

        friendId = intent.getLongExtra(EXTRA_FRIEND_ID, -1L)
        if (friendId == -1L) {
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[FriendDetailViewModel::class.java]

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this) { onBack() }
        findViewById<View>(R.id.rowLinkStatus).setOnClickListener { onLinkTapped() }
        findViewById<View>(R.id.btnLinkAction).setOnClickListener { onLinkTapped() }
        agreementDialogs = AgreementDialogs(
            this,
            DeclarationRepository(applicationContext, AppDatabase.getInstance(applicationContext))
        ) { viewModel.load(friendId) }
        findViewById<View>(R.id.rowAgreement).setOnClickListener { onAgreementTapped() }
        findViewById<View>(R.id.btnAgreementAction).setOnClickListener { onAgreementTapped() }

        monthControl = MonthRangeControl(this, findViewById(R.id.btnPickMonth), viewModel::setWindow)
        monthControl.attach()
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
        findViewById<RecyclerView>(R.id.rvFriendTransactions).apply {
            layoutManager = LinearLayoutManager(this@FriendDetailActivity)
            adapter = this@FriendDetailActivity.adapter
        }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.load(friendId)
    }

    override fun onResume() {
        super.onResume()
        // A link can change while this screen is in the background: a reply confirmed in the inbox,
        // or an unlink collected at launch. Only the link is refreshed, so the list keeps its place.
        if (::viewModel.isInitialized) viewModel.refreshLink(friendId)
    }

    /** Leaving a selection is a step back, not a step off the screen. */
    private fun onBack() {
        if (viewModel.uiState.value?.selectionMode == true) viewModel.exitSelection() else finish()
    }

    private fun render(state: FriendDetailUiState) {
        if (state.isLoading) return

        val friend = state.friend ?: run {
            Toast.makeText(this, "Friend not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        friendName = friend.name

        findViewById<TextView>(R.id.tvFriendDetailName).text = friend.name
        findViewById<TextView>(R.id.tvFriendDetailBalance).apply {
            val net = state.summary?.netBalancePaise ?: 0L
            text = when {
                net > 0 -> "+" + AmountFormat.rupees(net)
                net < 0 -> "-" + AmountFormat.rupees(-net)
                else -> "Settled"
            }
            setTextColor(
                themeColor(
                    when {
                        net > 0 -> ThemeAttr.positive
                        net < 0 -> ThemeAttr.negative
                        else -> ThemeAttr.amountNeutral
                    }
                )
            )
        }

        monthControl.render(state.window)
        renderChapters(state)
        renderLink(state)
        renderAgreement(state)
        renderList(state)
        renderSelectionBar(state)
    }

    /**
     * The chapter half of the headline balance.
     *
     * A member who comes out even still gets a row: the point is that the chapter is there and has
     * been accounted for, which "even" says and an absent row does not.
     */
    private fun renderChapters(state: FriendDetailUiState) {
        val container = findViewById<LinearLayout>(R.id.friendChapterContainer)
        val personal = findViewById<TextView>(R.id.tvFriendPersonalBalance)
        container.removeAllViews()

        val summary = state.summary
        val chapters = summary?.chapterBalances.orEmpty()
        if (summary == null || chapters.isEmpty()) {
            personal.visibility = View.GONE
            return
        }

        // Only worth splitting out once a chapter is actually moving the figure above.
        personal.visibility = View.VISIBLE
        personal.text = when {
            summary.personalBalancePaise > 0L ->
                "Directly between you: +${AmountFormat.rupees(summary.personalBalancePaise)}"
            summary.personalBalancePaise < 0L ->
                "Directly between you: -${AmountFormat.rupees(-summary.personalBalancePaise)}"
            else -> "Directly between you: settled"
        }

        chapters.forEach { chapter ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_chapter_line, container, false)
            row.findViewById<TextView>(R.id.tvChapterLineLabel).text = chapter.name
            val amount = row.findViewById<TextView>(R.id.tvChapterLineAmount)
            when {
                chapter.amountPaise > 0L -> {
                    amount.text = "owes you ${AmountFormat.rupees(chapter.amountPaise)}"
                    amount.setTextColor(themeColor(ThemeAttr.positive))
                }
                chapter.amountPaise < 0L -> {
                    amount.text = "you owe ${AmountFormat.rupees(-chapter.amountPaise)}"
                    amount.setTextColor(themeColor(ThemeAttr.negative))
                }
                else -> {
                    amount.text = "even"
                    amount.setTextColor(themeColor(ThemeAttr.textMuted))
                }
            }
            row.setOnClickListener {
                startActivity(
                    Intent(this, ChapterDetailActivity::class.java)
                        .putExtra(ChapterDetailActivity.EXTRA_CHAPTER_ID, chapter.chapterId)
                )
            }
            container.addView(row)
        }
    }

    private fun renderLink(state: FriendDetailUiState) {
        val link = state.link
        val invite = state.openInviteExpiresEpoch
        val now = System.currentTimeMillis()
        val (status, action) = when {
            link != null && link.state == FriendLinkState.LINKED ->
                "Linked with ${link.remoteName}'s DhanMoney" to "Manage"
            link != null && link.state == FriendLinkState.AWAITING_CONFIRMATION ->
                "Waiting for ${link.remoteName} to confirm the link" to "Manage"
            link != null && link.state == FriendLinkState.KEY_CHANGED ->
                "${link.remoteName}'s security key changed" to "Fix"
            !state.mailboxOn -> "Not linked · the friends mailbox is off" to "Set up"
            invite != null && invite > now ->
                "Link invite sent · open until ${shortDateFmt.format(Date(invite))}" to "Manage"
            invite != null -> "Your link invite ran out" to "Send new"
            else -> "Not linked with DhanMoney" to "Link"
        }
        findViewById<TextView>(R.id.tvLinkStatus).apply {
            text = status
            setTextColor(
                themeColor(
                    if (link?.state == FriendLinkState.KEY_CHANGED) ThemeAttr.warning else ThemeAttr.onSurfaceVariant
                )
            )
        }
        findViewById<TextView>(R.id.btnLinkAction).text = action
    }

    /**
     * Where agreeing on a balance with them stands. Only shown once there is something to say: a
     * checkpoint, a proposal either way, or a link that makes one possible.
     */
    private fun renderAgreement(state: FriendDetailUiState) {
        val row = findViewById<View>(R.id.rowAgreement)
        val agreement = state.agreement
        val checkpoint = agreement?.checkpoint
        val outgoing = agreement?.outgoing
        val (status, action) = when {
            agreement == null -> null to null
            agreement.incoming.isNotEmpty() ->
                "$friendName asks you to agree on your balance" to "Review"
            outgoing != null && outgoing.sentEpoch == null ->
                "Your proposal waits to be sent: ${AgreementText.proposal(outgoing, friendName)}" to "Withdraw"
            outgoing != null ->
                "Waiting for $friendName: ${AgreementText.proposal(outgoing, friendName)}" to "Withdraw"
            checkpoint != null -> {
                val agreed = "Agreed ${AgreementText.day(requireNotNull(checkpoint.asOfEpoch))}: " +
                    AgreementText.balance(requireNotNull(checkpoint.amountPaise), friendName)
                when {
                    checkpoint.archived -> "$agreed (while linked)" to (if (agreement.linked) "Agree" else null)
                    agreement.linked -> agreed to "Change"
                    else -> agreed to null
                }
            }
            agreement.linked -> "No agreed balance with $friendName yet" to "Agree"
            else -> null to null
        }
        row.visibility = if (status == null) View.GONE else View.VISIBLE
        row.isClickable = action != null
        findViewById<TextView>(R.id.tvAgreementStatus).text = status.orEmpty()
        findViewById<TextView>(R.id.btnAgreementAction).apply {
            text = action.orEmpty()
            visibility = if (action == null) View.GONE else View.VISIBLE
        }
    }

    private fun onAgreementTapped() {
        val state = viewModel.uiState.value ?: return
        val agreement = state.agreement ?: return
        val balance = state.summary?.netBalancePaise ?: 0L
        val checkpoint = agreement.checkpoint
        val outgoing = agreement.outgoing
        when {
            agreement.incoming.isNotEmpty() -> agreementDialogs.review(agreement.incoming.first().id)
            outgoing != null -> agreementDialogs.withdraw(outgoing, friendName)
            !agreement.linked -> Unit
            checkpoint != null && !checkpoint.archived -> agreementDialogs.change(friendId, friendName, balance)
            else -> agreementDialogs.propose(friendId, friendName, balance)
        }
    }

    private fun renderList(state: FriendDetailUiState) {
        adapter.submit(state.entries, state.rows)
        adapter.updateSelection(state.selectionMode, state.selected, state.shareable)

        renderSearchBox(state.query)
        findViewById<TextView>(R.id.tvFilterHint).text = if (state.isFiltered) {
            "${state.entries.size} of ${state.totalCount}"
        } else {
            ""
        }
    }

    // --- search -----------------------------------------------------------------------------------

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

    /** Writes to the box only when it genuinely disagrees, or the cursor jumps mid-word. */
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

    // --- selection --------------------------------------------------------------------------------

    private fun wireSelectionBar() {
        findViewById<View>(R.id.btnSelectAllRows).setOnClickListener { viewModel.selectAllShareable() }
        findViewById<TextView>(R.id.btnSelectionPrimary).apply {
            text = "Share\u2026"
            setOnClickListener { sendSelection() }
        }
        findViewById<TextView>(R.id.btnSelectionSecondary).apply {
            text = "Delete"
            setOnClickListener { confirmDeleteSelection() }
        }
    }

    private fun renderSelectionBar(state: FriendDetailUiState) {
        findViewById<View>(R.id.barSelectionActions).visibility =
            if (state.selectionMode) View.VISIBLE else View.GONE
        if (!state.selectionMode) return

        findViewById<TextView>(R.id.tvSelectionCount).text = when (state.selected.size) {
            0 -> "Pick what to act on"
            1 -> "1 selected"
            else -> "${state.selected.size} selected"
        }
        findViewById<View>(R.id.btnSelectionPrimary).isEnabled = state.canExport
        findViewById<View>(R.id.btnSelectionSecondary).isEnabled = state.selected.isNotEmpty()
        findViewById<View>(R.id.btnSelectAllRows).isEnabled = state.shareable.isNotEmpty()
    }

    /** Anyone, not just this friend: see [RecipientPicker]. */
    private fun sendSelection() {
        val ids = viewModel.uiState.value?.selectedShareableIds.orEmpty()
        if (ids.isEmpty()) return
        RecipientPicker(this) { viewModel.load(friendId) }.show(ids)
        viewModel.exitSelection()
    }

    private fun confirmDeleteSelection() {
        val count = viewModel.uiState.value?.selected?.size ?: return
        if (count == 0) return
        val what = if (count == 1) "1 transaction" else "$count transactions"
        AlertDialog.Builder(this)
            .setTitle("Delete $what?")
            .setMessage("This deletes them and their shares. It cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteSelected { failures -> reportFailures(failures) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One dialog for the whole run: a bulk delete can be blocked on several rows at once. */
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

    // --- linking ------------------------------------------------------------------------------

    private fun onLinkTapped() {
        val state = viewModel.uiState.value ?: return
        val link = state.link
        when {
            link != null && link.state == FriendLinkState.LINKED -> AlertDialog.Builder(this)
                .setTitle("Linked with ${link.remoteName}")
                .setMessage(
                    "Transactions you send $friendName go straight to their app, sealed so only they can " +
                        "open them.\n\nTheir security key: ${KeyFingerprint.display(link.fingerprint)}\n" +
                        "To be sure, compare it with the key their app shows on its Friends mailbox screen."
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("Unlink") { _, _ -> confirmUnlink(link) }
                .show()

            link != null && link.state == FriendLinkState.AWAITING_CONFIRMATION -> AlertDialog.Builder(this)
                .setTitle("Waiting for ${link.remoteName}")
                .setMessage(
                    "You accepted their invite. The link is complete once they confirm it on their phone, and " +
                        "nothing can be sent until then."
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel the link") { _, _ -> viewModel.unlink(friendId, ::toast) }
                .show()

            link != null && link.state == FriendLinkState.KEY_CHANGED -> AlertDialog.Builder(this)
                .setTitle("${link.remoteName}'s key changed")
                .setMessage(
                    "Their app now uses a different security key from the one you linked with. That usually " +
                        "means they reinstalled without their key, but it is also what it would look like if " +
                        "someone else were answering for their account. Nothing is sent to them, or trusted " +
                        "from them, until you link again.\n\n" +
                        "Send $friendName a new invite through a chat you know is really them."
                )
                .setPositiveButton("Send new invite") { _, _ -> shareInvite(fresh = true) }
                .setNegativeButton("Unlink") { _, _ -> confirmUnlink(link) }
                .show()

            !state.mailboxOn -> startActivity(Intent(this, MailboxActivity::class.java))

            state.openInviteExpiresEpoch != null && state.openInviteExpiresEpoch > System.currentTimeMillis() ->
                AlertDialog.Builder(this)
                    .setTitle("Invite sent to $friendName")
                    .setMessage(
                        "It links the two of you as soon as $friendName accepts it, and this phone next " +
                            "checks the mailbox. It can be used once."
                    )
                    .setPositiveButton("Send it again") { _, _ -> shareInvite(fresh = false) }
                    .setNegativeButton("Withdraw") { _, _ -> viewModel.withdrawInvites(friendId) }
                    .setNeutralButton("Close", null)
                    .show()

            else -> shareInvite(fresh = true)
        }
    }

    private fun shareInvite(fresh: Boolean) {
        viewModel.inviteCode(friendId, fresh, onReady = { code ->
            val message = inviteMessage(code)
            AlertDialog.Builder(this)
                .setTitle("Send this invite to $friendName")
                .setMessage(
                    "Send it through a chat you already use with $friendName, the same way you would send a " +
                        "parcel. Tapping the link opens it in their app; if it opens a page instead, the page " +
                        "hands them the code to paste. You are linked as soon as they accept. It works once, " +
                        "and runs out in 7 days."
                )
                .setPositiveButton("Send") { _, _ ->
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message),
                            "Send to $friendName"
                        )
                    )
                }
                .setNeutralButton("Copy") { _, _ -> copyText(message, "Link invite") }
                .setNegativeButton("Cancel", null)
                .show()
        }, onError = ::toast)
    }

    /**
     * What actually goes into the chat: a line of explanation and a link, because a chat app makes a
     * link tappable and a bare code only copyable. Builds without the link when this version has no
     * host to point at, since the code alone still works by pasting.
     */
    private fun inviteMessage(code: String): String {
        val host = getString(R.string.mailbox_link_host)
        if (host.isBlank()) return code
        return "Link with me on DhanMoney, so the transactions we share arrive in each other's app:\n" +
            InviteCode.link(host, code)
    }

    private fun confirmUnlink(link: FriendLink) {
        AlertDialog.Builder(this)
            .setTitle("Unlink $friendName?")
            .setMessage(
                "${link.remoteName} will no longer be able to send you anything through the mailbox, and you " +
                    "will not be able to send to them. Transactions you already have stay as they are."
            )
            .setPositiveButton("Unlink") { _, _ -> viewModel.unlink(friendId, ::toast) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- clipboard --------------------------------------------------------------------------------

    private fun copyText(text: String, label: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 shows its own copy confirmation; a toast on top of it just repeats itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied. Paste it to $friendName.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
