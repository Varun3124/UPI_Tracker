package com.varun.upitracker.ui.declaration

import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.data.declaration.DeclarationException
import com.varun.upitracker.data.declaration.DeclarationRepository
import com.varun.upitracker.data.declaration.ProposalPreview
import com.varun.upitracker.data.declaration.SendResult
import com.varun.upitracker.data.mailbox.MailboxException
import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.declaration.DeclarationMessages
import com.varun.upitracker.util.AmountFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How agreements read, in one place so the friend page, the inbox and the entry screen agree. */
object AgreementText {

    fun day(epoch: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epoch))

    fun moment(epoch: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(epoch))

    /** "Bob owes you ₹1,200", "you owe Bob ₹1,200" or "you and Bob are even". Positive means they owe you. */
    fun balance(amountPaise: Long, name: String): String = when {
        amountPaise > 0L -> "$name owes you ${AmountFormat.rupeesExact(amountPaise)}"
        amountPaise < 0L -> "you owe $name ${AmountFormat.rupeesExact(-amountPaise)}"
        else -> "you and $name are even"
    }

    fun sentence(text: String): String = text.replaceFirstChar { it.uppercase() }

    /** A proposal in one line, from this phone's seat. */
    fun proposal(proposal: IouDeclaration, name: String): String = when (proposal.kind) {
        DeclarationKind.DECLARE ->
            "As of ${moment(requireNotNull(proposal.asOfEpoch))}, ${balance(requireNotNull(proposal.amountPaise), name)}"
        DeclarationKind.AMEND ->
            "Change the agreement of ${day(requireNotNull(proposal.asOfEpoch))} to: ${balance(requireNotNull(proposal.amountPaise), name)}"
        DeclarationKind.REVOKE -> "Remove your agreed balance"
        else -> ""
    }
}

/**
 * Proposing, answering and withdrawing agreed balances, the same way from every screen that offers
 * it. Each action runs on [activity]'s lifecycle scope, reports how it went in a toast, and then calls
 * [onChanged] so the screen can reload what it shows.
 *
 * See docs/declarations-design.md D1, D2, D10 and D13 for what each dialog is asking.
 */
class AgreementDialogs(
    private val activity: AppCompatActivity,
    private val repository: DeclarationRepository,
    private val onChanged: () -> Unit
) {

    private companion object {
        const val TAG = "AgreementDialogs"
    }

    /** D1: asks [friendName] to agree on a balance as of now, starting from what this book says. */
    fun propose(friendId: Long, friendName: String, balancePaise: Long) {
        activity.lifecycleScope.launch {
            val pending = repository.pendingUntilNow(friendId)
            val amount = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(AmountFormat.forInput(balancePaise))
                hint = "Amount"
            }
            val theyOwe = RadioButton(activity).apply { id = View.generateViewId(); text = "$friendName owes me" }
            val iOwe = RadioButton(activity).apply { id = View.generateViewId(); text = "I owe $friendName" }
            val direction = RadioGroup(activity).apply {
                orientation = RadioGroup.VERTICAL
                addView(theyOwe)
                addView(iOwe)
                check(if (balancePaise < 0L) iOwe.id else theyOwe.id)
            }
            val note = noteField("Note for $friendName (optional)")

            val message = buildString {
                append("$friendName is asked to agree that, as of now, this is the whole balance between you, ")
                append("chapters included. Nothing changes on either phone until they accept.")
                append("\n\nYour book says ${AgreementText.balance(balancePaise, friendName)}.")
                if (pending > 0) {
                    val what = if (pending == 1) "1 transaction" else "$pending transactions"
                    append("\n\n$what with $friendName still awaiting review will not change your balance once this ")
                    append("is agreed. Review them first to count them.")
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("Agree on a balance with $friendName")
                .setMessage(message)
                .setView(padded(amount, direction, note))
                .setPositiveButton("Send") { _, _ ->
                    val paise = AmountFormat.paiseOrNull(amount.text.toString())
                    if (paise == null) {
                        toast("Enter an amount")
                        return@setPositiveButton
                    }
                    val signed = if (direction.checkedRadioButtonId == iOwe.id) -paise else paise
                    perform { reportSent(repository.propose(friendId, signed, note.text.toString()), friendName) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** D13: shows what a proposal asks, and what accepting it would do, before answering it. */
    fun review(proposalId: String) {
        activity.lifecycleScope.launch {
            val preview = try {
                repository.preview(proposalId)
            } catch (error: DeclarationException) {
                toast(error.message ?: "That proposal is gone.")
                onChanged()
                return@launch
            }
            val name = preview.friendName
            val builder = AlertDialog.Builder(activity)
                .setTitle(if (preview.proposal.auto) "$name linked with you" else "$name asks you to agree")
                .setMessage(reviewMessage(preview))
                .setNeutralButton("Not now", null)
                .setNegativeButton("Deny") { _, _ -> askDenyNote(preview) }
            if (preview.cannotAccept == null) {
                builder.setPositiveButton("Accept") { _, _ ->
                    perform {
                        repository.accept(proposalId)
                        toast("Agreed with $name.")
                    }
                }
            }
            builder.show()
        }
    }

    /** Takes back this phone's own waiting proposal. */
    fun withdraw(proposal: IouDeclaration, friendName: String) {
        val waiting = if (proposal.sentEpoch == null) "It has not been sent yet, so $friendName never sees it." else
            "If $friendName has already accepted it, it stays agreed."
        AlertDialog.Builder(activity)
            .setTitle("Withdraw your proposal?")
            .setMessage("${AgreementText.sentence(AgreementText.proposal(proposal, friendName))}.\n\n$waiting")
            .setPositiveButton("Withdraw") { _, _ ->
                perform {
                    repository.withdraw(proposal.id)
                    toast("Withdrawn.")
                }
            }
            .setNegativeButton("Keep it", null)
            .show()
    }

    /** What can be done about an agreement in force: a fresh one as of now, or removing it. */
    fun change(friendId: Long, friendName: String, balancePaise: Long) {
        val actions = arrayOf("Agree on today's balance", "Remove this agreement")
        AlertDialog.Builder(activity)
            .setTitle("Agreed balance with $friendName")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> propose(friendId, friendName, balancePaise)
                    1 -> confirmRemove(friendId, friendName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * D10: asks [friendName] to fold a row dated before the agreement into it. [deltaPaise] is what the
     * row does to the balance with them; [what] names the row in the note that goes with it. [onDone]
     * runs once the question is answered either way, and after the proposal is sent if it is.
     */
    fun askToAdd(
        friendId: Long,
        friendName: String,
        checkpoint: IouDeclaration,
        deltaPaise: Long,
        what: String,
        onDone: () -> Unit = {}
    ) {
        if (deltaPaise == 0L) {
            toast("It does not change anything between you and $friendName.")
            onDone()
            return
        }
        val after = requireNotNull(checkpoint.amountPaise) + deltaPaise
        var sending = false
        AlertDialog.Builder(activity)
            .setTitle("Ask $friendName to add it?")
            .setMessage(
                "It is dated before your agreement of ${AgreementText.day(requireNotNull(checkpoint.asOfEpoch))}, so " +
                    "it has not changed what you owe each other.\n\nIf $friendName agrees, that agreement becomes: " +
                    "${AgreementText.balance(after, friendName)}."
            )
            .setPositiveButton("Ask") { _, _ ->
                sending = true
                perform(then = onDone) {
                    reportSent(repository.proposeAmend(friendId, deltaPaise, "Adds $what"), friendName)
                }
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { if (!sending) onDone() }
            .show()
    }

    // --- the parts --------------------------------------------------------------------------------

    private fun reviewMessage(preview: ProposalPreview): String = buildString {
        val name = preview.friendName
        val proposal = preview.proposal
        when (proposal.kind) {
            DeclarationKind.DECLARE -> {
                append("${AgreementText.sentence(AgreementText.proposal(proposal, name))}, chapters included.")
                val shares = preview.listedShares
                if (shares.isNotEmpty()) {
                    append("\n\nThat is ${AgreementText.balance(preview.directPaise ?: 0L, name)} directly")
                    shares.forEach { share ->
                        append(", ${AmountFormat.rupeesExact(share.amountPaise)} in ${share.chapterName ?: "a chapter not on this phone yet"}")
                    }
                    append(".")
                }
            }
            DeclarationKind.AMEND -> {
                val delta = proposal.deltaPaise ?: 0L
                append("$name wants to add ${AmountFormat.rupeesExact(kotlin.math.abs(delta))} ")
                append(if (delta >= 0L) "that $name owes you" else "that you owe $name")
                preview.target?.let { target ->
                    append(" to your agreement of ${AgreementText.day(requireNotNull(target.asOfEpoch))}")
                    append(", making it: ${AgreementText.balance(requireNotNull(proposal.amountPaise), name)}")
                }
                append(".")
            }
            DeclarationKind.REVOKE -> {
                append("$name wants to remove your agreed balance")
                preview.target?.let { append(" of ${AgreementText.day(requireNotNull(it.asOfEpoch))}") }
                append(". Your balance would go back to what your book and any earlier agreement say.")
            }
        }
        proposal.note?.let { append("\n\n$name says: \"$it\"") }
        append("\n\nYour book says ${AgreementText.balance(preview.myBalancePaise, name)} now.")
        if (preview.cannotAccept == null && preview.afterAcceptingPaise != preview.myBalancePaise) {
            append(" If you accept: ${AgreementText.balance(preview.afterAcceptingPaise, name)}.")
        }
        if (preview.pendingCovered > 0) {
            val what = if (preview.pendingCovered == 1) "1 transaction" else "${preview.pendingCovered} transactions"
            append("\n\n$what with $name still awaiting review would be covered by this, and would not change ")
            append("your balance once reviewed.")
        }
        preview.cannotAccept?.let { append("\n\n$it") }
    }

    private fun askDenyNote(preview: ProposalPreview) {
        val note = noteField("Tell ${preview.friendName} why (optional)")
        AlertDialog.Builder(activity)
            .setTitle("Deny?")
            .setMessage("Nothing changes on either phone. ${preview.friendName} is told you did not agree.")
            .setView(padded(note))
            .setPositiveButton("Deny") { _, _ ->
                perform {
                    repository.deny(preview.proposal.id, note.text.toString())
                    toast("Denied.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(friendId: Long, friendName: String) {
        AlertDialog.Builder(activity)
            .setTitle("Ask to remove the agreement?")
            .setMessage(
                "If $friendName accepts, your balance goes back to what your book and any earlier agreement " +
                    "say. Nothing changes until then."
            )
            .setPositiveButton("Ask") { _, _ ->
                perform { reportSent(repository.proposeRevoke(friendId), friendName) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reportSent(result: SendResult, friendName: String) = toast(
        when (result) {
            SendResult.SENT -> "Sent to $friendName. Nothing changes until they accept."
            SendResult.QUEUED -> "Saved. It goes to $friendName the next time the app reaches the mailbox."
            SendResult.REFUSED -> "$friendName is not accepting messages from you. Link with them again."
        }
    )

    private fun perform(then: () -> Unit = {}, block: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeclarationException) {
                toast(error.message ?: "That did not work.")
            } catch (error: MailboxException) {
                toast(error.message ?: "Could not reach the mailbox. Try again.")
            } catch (error: Exception) {
                Log.w(TAG, "An agreement action failed", error)
                toast("That did not work.")
            }
            onChanged()
            then()
        }
    }

    private fun noteField(hint: String) = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(InputFilter.LengthFilter(DeclarationMessages.MAX_NOTE_CHARS))
    }

    private fun padded(vararg views: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = activity.resources.getDimensionPixelSize(R.dimen.space_m)
        setPadding(pad * 2, pad, pad * 2, 0)
        views.forEach(::addView)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
