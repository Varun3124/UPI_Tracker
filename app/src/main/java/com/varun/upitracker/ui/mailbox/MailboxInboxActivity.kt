package com.varun.upitracker.ui.mailbox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.varun.upitracker.R
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.mailbox.PendingLinkReply
import com.varun.upitracker.domain.mailbox.KeyFingerprint
import com.varun.upitracker.ui.parcel.ParcelImportActivity
import com.varun.upitracker.ui.settings.AppViewModelFactory
import com.varun.upitracker.ui.theme.padRootForSystemBars
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What friends sent: parcels to look through, links just made, replies that still need answering by
 * hand, and anything that could not be opened.
 *
 * Nothing here saves a transaction. A parcel opens in [ParcelImportActivity], the same review screen
 * a pasted one uses, and is saved from there as pending.
 */
class MailboxInboxActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    private lateinit var viewModel: MailboxInboxViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mailbox_inbox)
        padRootForSystemBars(R.id.main)

        viewModel = ViewModelProvider(this, AppViewModelFactory(this))[MailboxInboxViewModel::class.java]

        findViewById<ImageButton>(R.id.btnBackInbox).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCheckNow).setOnClickListener {
            viewModel.collect(force = true, quiet = false, onError = ::toast)
        }
        findViewById<View>(R.id.btnInboxTurnOn).setOnClickListener {
            startActivity(Intent(this, MailboxActivity::class.java))
        }

        viewModel.uiState.observe(this, ::render)
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
        // Throttled and quiet: opening the screen offline should not complain about it.
        viewModel.collect(force = false, quiet = true, onError = {})
    }

    private fun render(state: MailboxInboxUiState) {
        val on = state.status is MailboxStatus.On
        findViewById<View>(R.id.panelInboxOff).visibility =
            if (state.status != null && !on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnCheckNow).isEnabled = on && !state.isBusy

        renderSection(R.id.tvRepliesHeader, R.id.containerReplies, state.replies) { container, reply ->
            row(
                container,
                title = "${reply.responderName} answered your invite",
                detail = "You sent the invite to ${reply.friendName}. Link only if this is them.\n" +
                    "Their security key: ${KeyFingerprint.display(reply.fingerprint)}",
                primary = "Link" to { viewModel.confirmReply(reply, ::toast, ::toast) },
                secondary = "Not them" to { confirmDecline(reply) }
            )
        }

        renderSection(R.id.tvLinksHeader, R.id.containerLinks, state.links) { container, link ->
            row(
                container,
                title = "You are now linked with ${link.friendName}",
                detail = "Their account is called ${link.remoteName}.\n" +
                    "Their security key: ${KeyFingerprint.display(link.fingerprint)}\n" +
                    "If that is not ${link.friendName}, unlink and send a new invite.",
                primary = "OK" to { viewModel.dismissNotice(link.messageId) },
                secondary = "Not them" to { confirmUnlink(link) }
            )
        }

        renderSection(R.id.tvParcelsHeader, R.id.containerParcels, state.parcels) { container, parcel ->
            val count = parcel.transactionCount
            row(
                container,
                title = "${parcel.friendName} sent ${if (count == 1) "a transaction" else "$count transactions"}",
                detail = "Sent ${dateFmt.format(Date(parcel.sentEpoch))}. Tap to look through " +
                    "${if (count == 1) "it" else "them"} before anything is saved.",
                onClick = {
                    startActivity(
                        Intent(this, ParcelImportActivity::class.java)
                            .putExtra(ParcelImportActivity.EXTRA_MESSAGE_ID, parcel.messageId)
                    )
                }
            )
        }

        renderSection(R.id.tvNoticesHeader, R.id.containerNotices, state.notices) { container, notice ->
            row(
                container,
                title = notice.text,
                detail = null,
                secondary = "Dismiss" to { viewModel.dismissNotice(notice.messageId) }
            )
        }

        findViewById<View>(R.id.tvInboxEmpty).visibility =
            if (on && state.isEmpty && !state.isBusy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvInboxHeld).apply {
            visibility = if (state.heldCount > 0) View.VISIBLE else View.GONE
            text = if (state.heldCount == 1) {
                "1 message from someone you have not linked with is kept for 30 days, in case you link with them."
            } else {
                "${state.heldCount} messages from people you have not linked with are kept for 30 days, in case you link with them."
            }
        }

        findViewById<View>(R.id.busyBar).visibility = if (state.isBusy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvBusy).text = state.busyMessage.orEmpty()
    }

    /**
     * Rebuilt on every render rather than recycled: each section holds a handful of rows at most,
     * the same trade the parcel review makes for its candidates.
     */
    private fun <T> renderSection(headerId: Int, containerId: Int, items: List<T>, bind: (ViewGroup, T) -> View) {
        findViewById<View>(headerId).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val container = findViewById<ViewGroup>(containerId)
        container.removeAllViews()
        items.forEach { container.addView(bind(container, it)) }
    }

    private fun row(
        container: ViewGroup,
        title: String,
        detail: String?,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        onClick: (() -> Unit)? = null
    ): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_mailbox_row, container, false)
        view.findViewById<TextView>(R.id.tvMailboxRowTitle).text = title
        view.findViewById<TextView>(R.id.tvMailboxRowDetail).apply {
            visibility = if (detail == null) View.GONE else View.VISIBLE
            text = detail
        }
        view.findViewById<View>(R.id.rowMailboxActions).visibility =
            if (primary == null && secondary == null) View.GONE else View.VISIBLE
        view.findViewById<Button>(R.id.btnMailboxRowPrimary).apply {
            visibility = if (primary == null) View.GONE else View.VISIBLE
            text = primary?.first
            setOnClickListener { primary?.second?.invoke() }
        }
        view.findViewById<Button>(R.id.btnMailboxRowSecondary).apply {
            visibility = if (secondary == null) View.GONE else View.VISIBLE
            text = secondary?.first
            setOnClickListener { secondary?.second?.invoke() }
        }
        if (onClick != null) view.setOnClickListener { onClick() }
        return view
    }

    private fun confirmUnlink(link: InboxLink) {
        AlertDialog.Builder(this)
            .setTitle("Not ${link.friendName}?")
            .setMessage(
                "Then the invite reached someone else. ${link.remoteName} is unlinked and told, and stops " +
                    "being able to send you anything. To link with ${link.friendName}, send them a new " +
                    "invite from their page."
            )
            .setPositiveButton("Unlink") { _, _ -> viewModel.unlink(link, ::toast, ::toast) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDecline(reply: PendingLinkReply) {
        AlertDialog.Builder(this)
            .setTitle("Not ${reply.friendName}?")
            .setMessage(
                "Then the invite reached someone other than ${reply.friendName}. It is closed, and " +
                    "${reply.responderName} is told. To link with ${reply.friendName}, send them a new " +
                    "invite from their page."
            )
            .setPositiveButton("Close invite") { _, _ -> viewModel.declineReply(reply, ::toast, ::toast) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
