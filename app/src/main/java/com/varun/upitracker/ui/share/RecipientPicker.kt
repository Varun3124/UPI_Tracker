package com.varun.upitracker.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxSendRepository
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.mailbox.RecipientOption
import com.varun.upitracker.data.mailbox.SendOutcome
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLinkState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Who a selection of transactions goes to, and sending it there.
 *
 * Lists everyone the selection involves -- at either end or in a split -- then every other friend.
 * Linked friends in the selection are sent to through the mailbox, each their own sealed copy, and
 * start ticked unless they already had it. Anyone else, in it or not, can be handed a parcel to
 * paste, one person at a time, the way sharing always worked.
 *
 * Used from a friend's page and from any transaction's long-press, so the rules for who may receive
 * what live in [MailboxSendRepository] and [com.varun.upitracker.domain.parcel.ParcelEligibility],
 * not here.
 */
class RecipientPicker(
    private val activity: AppCompatActivity,
    /** Runs after a send, so the screen underneath can show what changed. */
    private val onSent: () -> Unit = {}
) {

    private val appContext = activity.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val sending = MailboxSendRepository(appContext, db)
    private val identities = MailboxIdentityRepository(appContext, db)
    private val export = ParcelExportRepository(db, appContext)
    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    fun show(transactionIds: Set<Long>) {
        if (transactionIds.isEmpty()) return
        if (transactionIds.size > ParcelExportRepository.MAX_TRANSACTIONS) {
            toast("That is too much to send at once. Send up to ${ParcelExportRepository.MAX_TRANSACTIONS} at a time.")
            return
        }
        activity.lifecycleScope.launch {
            val options = sending.recipientOptions(transactionIds)
            val mailboxOn = identities.status() is MailboxStatus.On
            val notes = withContext(Dispatchers.IO) {
                transactionIds.mapNotNull { db.transactionDao().getTransactionById(it)?.reason?.takeIf(String::isNotBlank) }
                    .distinct()
            }
            if (options.none { it.pasteTransactionIds.isNotEmpty() }) {
                toast("None of these can be shared yet.")
                return@launch
            }
            showDialog(transactionIds, options, mailboxOn, notes)
        }
    }

    private fun showDialog(ids: Set<Long>, options: List<RecipientOption>, mailboxOn: Boolean, notes: List<String>) {
        val inflater = LayoutInflater.from(activity)
        val content = inflater.inflate(R.layout.dialog_recipient_picker, null)
        val list = content.findViewById<LinearLayout>(R.id.containerRecipients)
        val checked = mutableSetOf<Long>()
        lateinit var dialog: AlertDialog

        content.findViewById<TextView>(R.id.tvRecipientPrivacy).text = privacyNote(notes)
        content.findViewById<View>(R.id.tvRecipientMailboxOff).visibility = if (mailboxOn) View.GONE else View.VISIBLE

        var headedEveryoneElse = false
        options.forEach { option ->
            if (!option.involved && !headedEveryoneElse) {
                headedEveryoneElse = true
                list.addView(sectionHeader(list, "Everyone else"))
            }
            val row = inflater.inflate(R.layout.item_recipient_option, list, false)
            val sendable = mailboxOn && option.isLinked && option.eligibleTransactionIds.isNotEmpty()
            // What they would actually get: the mailbox set when it can reach them, else the paste set.
            val count = if (sendable) option.eligibleTransactionIds.size else option.pasteTransactionIds.size

            row.findViewById<TextView>(R.id.tvRecipientName).text =
                if (ids.size == 1) option.name else "${option.name} · $count of ${ids.size}"
            row.findViewById<TextView>(R.id.tvRecipientDetail).text = when {
                count == 0 -> "Nothing you picked can be sent yet"
                !option.involved -> "Not in these. Send them a parcel to paste"
                option.linkState == FriendLinkState.AWAITING_CONFIRMATION -> "Link not confirmed yet"
                option.linkState == FriendLinkState.KEY_CHANGED -> "Their security key changed. Link again from their page"
                option.linkState == null -> "Not linked. Send them a parcel to paste instead"
                !mailboxOn -> "Linked, but your friends mailbox is off"
                option.lastSentEpoch != null -> "Linked · already sent on ${dateFmt.format(Date(option.lastSentEpoch))}"
                else -> "Linked"
            }

            row.findViewById<CheckBox>(R.id.cbRecipient).apply {
                isEnabled = sendable
                // Someone who already got these starts unticked: sending twice is harmless, but noise.
                isChecked = sendable && option.lastSentEpoch == null
                if (isChecked) checked += option.friendId
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) checked += option.friendId else checked -= option.friendId
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.isNotEmpty()
                }
            }

            row.findViewById<TextView>(R.id.btnRecipientPaste).apply {
                visibility = if (count > 0 && !sendable) View.VISIBLE else View.GONE
                setOnClickListener {
                    dialog.dismiss()
                    shareParcel(option)
                }
            }
            row.alpha = if (count == 0) 0.5f else 1f
            list.addView(row)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(if (ids.size == 1) "Send this transaction" else "Send ${ids.size} transactions")
            .setView(content)
            .setPositiveButton("Send", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = checked.isNotEmpty()
                setOnClickListener {
                    dialog.dismiss()
                    send(ids, checked.toSet())
                }
            }
        }
        dialog.show()
    }

    private fun sectionHeader(parent: LinearLayout, text: String) = TextView(activity).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_UPI_Caption)
        setPadding(0, parent.resources.getDimensionPixelSize(R.dimen.space_xs) * 3, 0, 0)
    }

    /** The notes on these rows were written for the user, not for whoever is about to read them. */
    private fun privacyNote(notes: List<String>): String = buildString {
        append("Each person sees the amounts, the dates and who was involved")
        if (notes.isEmpty()) {
            append(".")
        } else {
            append(", and your notes:\n")
            append(notes.take(3).joinToString("\n") { "• $it" })
            if (notes.size > 3) append("\n• …and ${notes.size - 3} more")
        }
        append("\n\nPeople you send this to together can recognise each other's DhanMoney accounts in it.")
    }

    private fun send(ids: Set<Long>, recipients: Set<Long>) {
        val progress = AlertDialog.Builder(activity)
            .setMessage("Sealing and sending…")
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val outcome = try {
                sending.send(ids, recipients)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SendOutcome(emptyList(), listOf(error.message ?: "Could not send."))
            }
            progress.dismiss()
            onSent()
            if (outcome.problems.isEmpty()) {
                toast("Sent to ${outcome.deliveredTo.joinToString(", ")}.")
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(
                        if (outcome.deliveredTo.isEmpty()) "Nothing was sent"
                        else "Sent to ${outcome.deliveredTo.joinToString(", ")}"
                    )
                    .setMessage(outcome.problems.joinToString("\n\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** The old way, for someone not linked: a parcel to paste, written from their point of view. */
    private fun shareParcel(option: RecipientOption) {
        activity.lifecycleScope.launch {
            val parcel = withContext(Dispatchers.IO) { export.buildParcel(option.friendId, option.pasteTransactionIds) }
            AlertDialog.Builder(activity)
                .setTitle("A parcel for ${option.name}")
                .setMessage(
                    "${option.name} is not reachable through the mailbox, so this goes as a parcel to paste. " +
                        "Send it through a chat you already use with them; they paste it in Settings and pick " +
                        "you as the sender."
                )
                .setPositiveButton("Send") { _, _ ->
                    activity.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, parcel),
                            "Send to ${option.name}"
                        )
                    )
                }
                .setNeutralButton("Copy") { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Shared transactions", parcel))
                    // Android 13 shows its own copy confirmation; a toast on top of it just repeats it.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        toast("Copied. Paste it to ${option.name}.")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
