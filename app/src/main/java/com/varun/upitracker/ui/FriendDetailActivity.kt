package com.varun.upitracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.varun.upitracker.ui.chapter.ChapterDetailActivity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.mailbox.InviteCode
import com.varun.upitracker.domain.mailbox.KeyFingerprint
import com.varun.upitracker.ui.mailbox.MailboxActivity
import com.varun.upitracker.ui.share.RecipientPicker
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import android.widget.ImageButton
import com.varun.upitracker.ui.theme.padRootForSystemBars

class FriendDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRIEND_ID = "friend_id"
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val shortDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private lateinit var viewModel: FriendDetailViewModel
    private var friendId: Long = -1L
    private var friendName: String = "your friend"

    /**
     * Held rather than rebuilt on every emission. Selection changes republish the whole state, and
     * a fresh adapter each time would jump the list back to the top mid-tick.
     */
    private var adapter: FriendTransactionAdapter? = null

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

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBack() }
        findViewById<ImageButton>(R.id.btnShareParcel).setOnClickListener {
            if (viewModel.uiState.value?.selectionMode == true) {
                viewModel.exitSelection()
            } else {
                startSelection(null)
            }
        }
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { viewModel.selectAllExportable() }
        findViewById<Button>(R.id.btnCopyParcel).setOnClickListener { withParcel(::copyParcel) }
        findViewById<Button>(R.id.btnSendParcel).setOnClickListener { sendSelection() }
        findViewById<View>(R.id.rowLinkStatus).setOnClickListener { onLinkTapped() }
        findViewById<View>(R.id.btnLinkAction).setOnClickListener { onLinkTapped() }

        onBackPressedDispatcher.addCallback(this) { onBack() }

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[FriendDetailViewModel::class.java]
        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.load(friendId)
    }

    override fun onResume() {
        super.onResume()
        // A link can change while this screen is in the background: a reply confirmed in the inbox,
        // or an unlink collected at launch. Only the link is refreshed, so the list keeps its place.
        if (::viewModel.isInitialized) viewModel.refreshLink(friendId)
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

        renderChapters(state)
        renderLink(state)
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

    private fun renderList(state: FriendDetailUiState) {
        val recycler = findViewById<RecyclerView>(R.id.rvFriendTransactions)
        val existing = adapter
        if (existing == null || existing.transactions !== state.transactions) {
            adapter = FriendTransactionAdapter(
                transactions = state.transactions,
                friendId = friendId,
                db = AppDatabase.getInstance(applicationContext),
                dateFmt = dateFmt,
                onTap = { txId ->
                    if (viewModel.uiState.value?.selectionMode == true) {
                        viewModel.toggleSelection(txId)
                    } else {
                        startActivity(
                            Intent(this, TransactionEntryActivity::class.java)
                                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, txId)
                        )
                    }
                },
                onLongPress = { txId ->
                    if (viewModel.uiState.value?.selectionMode == true) {
                        viewModel.toggleSelection(txId)
                    } else {
                        showEntryActions(txId)
                    }
                }
            ).also {
                recycler.layoutManager = LinearLayoutManager(this)
                recycler.adapter = it
            }
        }
        adapter?.updateSelection(state.selectionMode, state.selected, state.exportable, state.blockedReasons)
    }

    private fun renderSelectionBar(state: FriendDetailUiState) {
        findViewById<View>(R.id.barExportActions).visibility =
            if (state.selectionMode) View.VISIBLE else View.GONE
        if (!state.selectionMode) return

        findViewById<TextView>(R.id.tvSelectionCount).text = when {
            state.selected.isEmpty() -> "Pick what to share"
            state.selected.size == 1 -> "1 transaction selected"
            else -> "${state.selected.size} transactions selected"
        }
        findViewById<Button>(R.id.btnCopyParcel).isEnabled = state.canExport
        findViewById<Button>(R.id.btnSendParcel).isEnabled = state.canExport
        findViewById<Button>(R.id.btnSelectAll).isEnabled = state.exportable.isNotEmpty()
    }

    private fun startSelection(transactionId: Long?) {
        val state = viewModel.uiState.value ?: return
        if (state.exportable.isEmpty()) {
            Toast.makeText(this, "Nothing here can be shared yet.", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.enterSelection(transactionId)
    }

    /** Leaving a selection is a step back, not a step off the screen. */
    private fun onBack() {
        if (viewModel.uiState.value?.selectionMode == true) viewModel.exitSelection() else finish()
    }

    private fun showEntryActions(transactionId: Long) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Share…", "Delete")) { _, index ->
                if (index == 0) {
                    RecipientPicker(this) { viewModel.load(friendId) }.show(setOf(transactionId))
                } else {
                    showDeleteDialog(transactionId)
                }
            }
            .show()
    }

    private fun showDeleteDialog(transactionId: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete transaction?")
            .setMessage("This will delete the transaction and its shares.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteTransaction(friendId, transactionId) { error ->
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Anyone, not just this friend: see [RecipientPicker]. */
    private fun sendSelection() {
        val selected = viewModel.uiState.value?.selected.orEmpty()
        if (selected.isEmpty()) return
        RecipientPicker(this) { viewModel.load(friendId) }.show(selected)
        viewModel.exitSelection()
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

    // --- pasting ------------------------------------------------------------------------------

    /**
     * Shows what is about to leave the device before it does. The notes on these rows are the
     * user's own words, and they wrote them for themselves, not for whoever is about to read them.
     */
    private fun withParcel(send: (String) -> Unit) {
        val state = viewModel.uiState.value ?: return
        val count = state.selected.size
        if (count > ParcelExportRepository.MAX_TRANSACTIONS) {
            Toast.makeText(
                this,
                "That is too much for one message. Send up to ${ParcelExportRepository.MAX_TRANSACTIONS} at a time.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val notes = state.transactions
            .filter { it.id in state.selected }
            .mapNotNull { it.reason?.takeIf(String::isNotBlank) }
            .distinct()

        AlertDialog.Builder(this)
            .setTitle(if (count == 1) "Share 1 transaction?" else "Share $count transactions?")
            .setMessage(
                buildString {
                    append("$friendName will see the amounts, the dates, who was involved")
                    if (notes.isEmpty()) append(" — and nothing else.") else {
                        append(", and your notes:\n\n")
                        append(notes.take(5).joinToString("\n") { "• $it" })
                        if (notes.size > 5) append("\n• …and ${notes.size - 5} more")
                    }
                }
            )
            .setPositiveButton("Share") { _, _ ->
                viewModel.buildParcel(
                    friendId = friendId,
                    onReady = { parcel ->
                        send(parcel)
                        viewModel.exitSelection()
                    },
                    onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyParcel(parcel: String) {
        copyText(parcel, "Shared transactions")
    }

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

class FriendTransactionAdapter(
    val transactions: List<Transaction>,
    private val friendId: Long,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit,
    private val onLongPress: (Long) -> Unit
) : RecyclerView.Adapter<FriendTransactionAdapter.VH>() {

    private var selectionMode = false
    private var selected: Set<Long> = emptySet()
    private var exportable: Set<Long> = emptySet()
    private var blockedReasons: Map<Long, String> = emptyMap()

    fun updateSelection(
        selectionMode: Boolean,
        selected: Set<Long>,
        exportable: Set<Long>,
        blockedReasons: Map<Long, String>
    ) {
        if (this.selectionMode == selectionMode && this.selected == selected &&
            this.exportable == exportable && this.blockedReasons == blockedReasons
        ) {
            return
        }
        this.selectionMode = selectionMode
        this.selected = selected
        this.exportable = exportable
        this.blockedReasons = blockedReasons
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbFriendTxSelect)
        val tvPayee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate: TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvIouNote: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIouAmount: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = transactions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = transactions[position]
        val shareable = tx.id in exportable

        // Set on every bind, never inside a branch: a recycled holder would otherwise keep the
        // tick of whatever row it showed last.
        holder.cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.cbSelect.isChecked = tx.id in selected
        holder.cbSelect.isEnabled = shareable
        holder.itemView.alpha = if (selectionMode && !shareable) 0.4f else 1f

        CoroutineScope(Dispatchers.Main).launch {
            holder.tvPayee.text = withContext(Dispatchers.IO) { tx.resolvePrimaryDisplay(db) }
        }
        holder.tvDate.text = dateFmt.format(Date(tx.dateEpoch))
        holder.tvAmount.text = tx.formatPerspectiveAmount()
        holder.tvAmount.setTextColor(tx.perspectiveColor(holder.tvAmount.context))

        // In selection mode the note line explains why a row cannot be sent, which is more use
        // than its IOU state while the user is choosing what to share.
        val blockedReason = blockedReasons[tx.id]
        if (selectionMode && blockedReason != null) {
            holder.tvIouNote.text = blockedReason
            holder.tvIouNote.setTextColor(holder.tvIouNote.themeColor(ThemeAttr.textMuted))
            holder.tvIouAmount.text = ""
        } else {
            holder.tvIouNote.setTextColor(holder.tvIouNote.themeColor(ThemeAttr.secondary))
            bindIouLine(holder, tx)
        }

        holder.itemView.setOnClickListener { onTap(tx.id) }
        holder.itemView.setOnLongClickListener {
            onLongPress(tx.id)
            true
        }
    }

    private fun bindIouLine(holder: VH, tx: Transaction) {
        CoroutineScope(Dispatchers.Main).launch {
            val entries = withContext(Dispatchers.IO) {
                db.iouDao().getEntriesForTransaction(tx.id).filter { it.friendId == friendId }
            }
            val share = withContext(Dispatchers.IO) {
                db.transactionShareDao().getSharesForTransaction(tx.id)
                    .firstOrNull { it.participantType == ActorType.FRIEND && it.friendId == friendId }
            }
            when {
                entries.isNotEmpty() -> {
                    val iouAmt = entries.sumOf { it.amountPaise }
                    val settled = entries.all { it.isSettled }
                    holder.tvIouNote.text = if (settled) "IOU settled" else "IOU pending"
                    holder.tvIouAmount.text = when {
                        iouAmt > 0 -> "owes " + AmountFormat.rupees(iouAmt)
                        iouAmt < 0 -> "you owe " + AmountFormat.rupees(-iouAmt)
                        else -> ""
                    }
                    holder.tvIouAmount.setTextColor(
                        holder.tvIouAmount.themeColor(
                            if (iouAmt > 0) ThemeAttr.positive else ThemeAttr.negative
                        )
                    )
                }
                share != null -> {
                    holder.tvIouNote.text = "Friend share"
                    holder.tvIouAmount.text = AmountFormat.rupees(share.amountPaise)
                    holder.tvIouAmount.setTextColor(holder.tvIouAmount.themeColor(ThemeAttr.secondary))
                }
                else -> {
                    holder.tvIouNote.text = ""
                    holder.tvIouAmount.text = ""
                }
            }
        }
    }
}
