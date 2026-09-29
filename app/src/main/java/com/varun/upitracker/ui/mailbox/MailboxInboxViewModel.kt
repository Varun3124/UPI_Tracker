package com.varun.upitracker.ui.mailbox

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.mailbox.LinkRepository
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.mailbox.MailboxSync
import com.varun.upitracker.data.mailbox.PendingLinkReply
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.MailboxMessageState
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import com.varun.upitracker.domain.parcel.ParcelFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A collected parcel waiting to be looked through. */
data class InboxParcel(
    val messageId: String,
    val friendName: String,
    val transactionCount: Int,
    val sentEpoch: Long
)

/** A link made while collecting, which the user is told about afterwards rather than asked about first. */
data class InboxLink(
    val messageId: String,
    val friendId: Long,
    val friendName: String,
    /** What the other side's Google account calls them: the thing to recognise, or not. */
    val remoteName: String,
    val fingerprint: String
)

/** A message that could not be used, and what to tell the user about it. */
data class InboxNotice(val messageId: String, val text: String)

data class MailboxInboxUiState(
    val status: MailboxStatus? = null,
    val replies: List<PendingLinkReply> = emptyList(),
    val links: List<InboxLink> = emptyList(),
    val parcels: List<InboxParcel> = emptyList(),
    val notices: List<InboxNotice> = emptyList(),
    /** Messages from accounts with no link here, kept a while in case the link turns up. */
    val heldCount: Int = 0,
    val busyMessage: String? = null
) {
    val isBusy: Boolean get() = busyMessage != null
    val isEmpty: Boolean get() = replies.isEmpty() && links.isEmpty() && parcels.isEmpty() && notices.isEmpty()
}

class MailboxInboxViewModel(context: Context) : ViewModel() {

    private companion object {
        const val TAG = "MailboxInboxViewModel"
    }

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val identities = MailboxIdentityRepository(appContext, db)
    private val links = LinkRepository(appContext, db)

    private val _uiState = MutableLiveData(MailboxInboxUiState())
    val uiState: LiveData<MailboxInboxUiState> = _uiState

    private val current: MailboxInboxUiState get() = _uiState.value ?: MailboxInboxUiState()

    fun load() {
        viewModelScope.launch {
            val status = identities.status()
            val replies = links.pendingReplies()
            val next = withContext(Dispatchers.IO) {
                val names = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
                val messages = db.mailboxDao().getMessagesInStates(
                    listOf(MailboxMessageState.NEW, MailboxMessageState.UNREADABLE, MailboxMessageState.HELD)
                )
                current.copy(
                    status = status,
                    replies = replies,
                    links = messages
                        .filter { it.kind == MailboxKind.LINK_CONFIRMED.name && it.state == MailboxMessageState.NEW }
                        .mapNotNull { message ->
                            val friendId = message.friendId ?: return@mapNotNull null
                            val link = db.mailboxDao().getLink(friendId) ?: return@mapNotNull null
                            InboxLink(
                                messageId = message.id,
                                friendId = friendId,
                                friendName = names[friendId] ?: "A friend",
                                remoteName = link.remoteName,
                                fingerprint = link.fingerprint
                            )
                        },
                    parcels = messages
                        .filter { it.kind == MailboxKind.PARCEL.name && it.state == MailboxMessageState.NEW }
                        .map { message ->
                            val parcel = message.body
                                ?.let { ParcelFormat.parse(it, ParcelFormat.MAILBOX_VERSION) } as? ParcelDecodeResult.Ok
                            InboxParcel(
                                messageId = message.id,
                                friendName = message.friendId?.let(names::get) ?: "Someone",
                                transactionCount = parcel?.parcel?.transactions?.size ?: 0,
                                sentEpoch = message.sentEpoch
                            )
                        },
                    notices = messages
                        .filter { it.state == MailboxMessageState.UNREADABLE }
                        .map { message ->
                            val who = message.friendId?.let(names::get) ?: "Someone you have not linked with"
                            InboxNotice(
                                message.id,
                                if (message.kind == MailboxKind.UNSUPPORTED.name) {
                                    "$who sent something this version of DhanMoney cannot read yet. Update the app, " +
                                        "then ask them to send it again."
                                } else {
                                    "$who sent something this phone could not open. Ask them to send it again."
                                }
                            )
                        },
                    heldCount = messages.count { it.state == MailboxMessageState.HELD }
                )
            }
            _uiState.value = next
        }
    }

    /**
     * Collects the inbox. [force] ignores the throttle, for the Check now button; [quiet] swallows
     * failures, for opening the screen while offline -- the list already shown is still right.
     */
    fun collect(force: Boolean, quiet: Boolean, onError: (String) -> Unit) {
        if (current.isBusy) return
        viewModelScope.launch {
            if (identities.status() !is MailboxStatus.On) {
                load()
                return@launch
            }
            _uiState.value = current.copy(busyMessage = "Checking for messages…")
            val failure = try {
                MailboxSync(appContext, db).run(force)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Collecting the inbox failed", error)
                error
            }
            _uiState.value = current.copy(busyMessage = null)
            load()
            if (failure != null && !quiet) onError(failure.message ?: "Could not check for messages.")
        }
    }

    fun confirmReply(reply: PendingLinkReply, onDone: (String) -> Unit, onError: (String) -> Unit) {
        perform("Linking…", onError, { links.confirmReply(reply.messageId) }) {
            onDone("You are linked with ${reply.friendName}.")
        }
    }

    fun declineReply(reply: PendingLinkReply, onDone: (String) -> Unit, onError: (String) -> Unit) {
        perform("Closing the invite…", onError, { links.declineReply(reply.messageId) }) {
            onDone("The invite is closed.")
        }
    }

    /** "That is not them": undoes a link made while collecting, and tells the other side. */
    fun unlink(link: InboxLink, onDone: (String) -> Unit, onError: (String) -> Unit) {
        perform("Unlinking…", onError, {
            links.unlink(link.friendId)
            withContext(Dispatchers.IO) {
                db.mailboxDao().setMessageState(link.messageId, MailboxMessageState.DISMISSED)
            }
        }) {
            onDone("${link.friendName} is unlinked.")
        }
    }

    fun dismissNotice(messageId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.mailboxDao().setMessageState(messageId, MailboxMessageState.DISMISSED) }
            load()
        }
    }

    private fun perform(busyMessage: String, onError: (String) -> Unit, block: suspend () -> Unit, onDone: () -> Unit) {
        if (current.isBusy) return
        _uiState.value = current.copy(busyMessage = busyMessage)
        viewModelScope.launch {
            val failure = try {
                block()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "\"$busyMessage\" failed", error)
                error
            }
            _uiState.value = current.copy(busyMessage = null)
            load()
            if (failure == null) onDone() else onError(failure.message ?: "Something went wrong.")
        }
    }
}
