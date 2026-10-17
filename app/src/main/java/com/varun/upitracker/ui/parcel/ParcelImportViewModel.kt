package com.varun.upitracker.ui.parcel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.chapter.CopyPreview
import com.varun.upitracker.data.chapter.SharedChapterException
import com.varun.upitracker.data.chapter.SharedChapterRepository
import com.varun.upitracker.data.mailbox.LinkException
import com.varun.upitracker.data.mailbox.LinkRepository
import com.varun.upitracker.data.mailbox.MailboxException
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.repository.ParcelImportPlan
import com.varun.upitracker.data.repository.ParcelImportRepository
import com.varun.upitracker.data.repository.ParcelImportResult
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.domain.chapter.ChapterPasteCodec
import com.varun.upitracker.domain.chapter.ChapterPasteResult
import com.varun.upitracker.domain.mailbox.InviteCode
import com.varun.upitracker.domain.mailbox.InviteDecodeResult
import com.varun.upitracker.domain.mailbox.LinkInviteCode
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelCodec
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import com.varun.upitracker.domain.parcel.SenderChoice
import com.varun.upitracker.util.initialsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ParcelImportUiState(
    val friends: List<Friend> = emptyList(),
    val pastedText: String = "",
    /** The pasted parcel as read, kept so a different answer to who sent it can plan it again. */
    val parcel: Parcel? = null,
    /** Non-null once the parcel has been read -- the screen switches to its review state. */
    val plan: ParcelImportPlan? = null,
    /** Entry indices the user has marked as already recorded here, and so will not be written. */
    val skipped: Set<Int> = emptySet(),
    /** A name in a split -> the friend the user says it is. Empty unless they said so. */
    val personMappings: Map<String, Long> = emptyMap(),
    val busy: Boolean = false,
    /**
     * Set when the screen was opened on a parcel collected from the mailbox. There is nothing to
     * paste and no sender to ask about: the verified sender is the friend they linked with.
     */
    val mailboxMessageId: String? = null
) {
    val canRead: Boolean get() = pastedText.isNotBlank() && !busy

    val willCreate: Int
        get() = plan?.entries?.filterIndexed { index, entry ->
            index !in skipped && entry.certainDuplicateId == null
        }?.size ?: 0
}

/**
 * Holds the pasted parcel and its plan for [ParcelImportActivity].
 *
 * One Activity backed by one ViewModel, for the same reason the statement importer is: the plan is
 * too big to hand between activities through an Intent, and there is no app-level singleton to
 * park it in.
 */
class ParcelImportViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val repository = ParcelImportRepository(db, appContext)
    private val links = LinkRepository(appContext, db)
    private val identities = MailboxIdentityRepository(appContext, db)
    private val sharedChapters = SharedChapterRepository(appContext, db)

    private val _uiState = MutableLiveData(ParcelImportUiState())
    val uiState: LiveData<ParcelImportUiState> = _uiState

    private val current: ParcelImportUiState get() = _uiState.value ?: ParcelImportUiState()

    fun loadFriends() {
        viewModelScope.launch {
            val friends = withContext(Dispatchers.IO) { db.friendDao().getAllFriendsSync() }
            _uiState.value = current.copy(friends = friends.sortedBy { it.name.lowercase() })
        }
    }

    fun setText(text: String) {
        if (text == current.pastedText) return
        _uiState.value = current.copy(pastedText = text)
    }

    /**
     * Reads what was pasted. An invite to link goes to [onInvite], and a copy of a friend's chapter
     * to [onChapterCopy]: neither says by itself which friend it is from, so the screen asks before
     * going on. A parcel names its writer, so it is planned straight away and the screen moves on to
     * reviewing it.
     */
    fun read(
        onInvite: (LinkInviteCode) -> Unit,
        onChapterCopy: (ChapterPasteResult.Ok) -> Unit,
        onError: (String) -> Unit
    ) {
        val state = current

        if (InviteCode.looksLikeInvite(state.pastedText)) {
            when (val decoded = InviteCode.decode(state.pastedText)) {
                is InviteDecodeResult.Failed -> onError(decoded.reason)
                is InviteDecodeResult.Ok -> onInvite(decoded.invite)
            }
            return
        }

        if (ChapterPasteCodec.looksLikeCopy(state.pastedText)) {
            when (val decoded = ChapterPasteCodec.decode(state.pastedText)) {
                is ChapterPasteResult.Failed -> onError(decoded.reason)
                is ChapterPasteResult.Ok -> onChapterCopy(decoded)
            }
            return
        }

        when (val decoded = ParcelCodec.decode(state.pastedText)) {
            is ParcelDecodeResult.Failed -> onError(decoded.reason)
            is ParcelDecodeResult.Ok -> {
                if (decoded.parcel.transactions.isEmpty()) {
                    return onError("That parcel is empty.")
                }
                _uiState.value = state.copy(busy = true)
                viewModelScope.launch {
                    val plan = try {
                        withContext(Dispatchers.IO) { repository.buildPlan(decoded.parcel) }
                    } catch (error: IllegalArgumentException) {
                        _uiState.value = current.copy(busy = false)
                        return@launch onError(error.message ?: "Could not read that parcel.")
                    }
                    _uiState.value = current.copy(
                        parcel = decoded.parcel,
                        plan = plan,
                        busy = false,
                        skipped = emptySet(),
                        personMappings = emptyMap()
                    )
                }
            }
        }
    }

    /**
     * The reader's own answer to who sent a pasted parcel. Planned again rather than patched, since
     * the preview's balances and its "you may already have this" matches both turn on who it is.
     * What was ticked and mapped stays: the rows are the same rows.
     */
    fun chooseSender(choice: SenderChoice, onError: (String) -> Unit) {
        val state = current
        val parcel = state.parcel ?: return
        if (state.busy) return
        _uiState.value = state.copy(busy = true)
        viewModelScope.launch {
            val plan = try {
                withContext(Dispatchers.IO) { repository.buildPlan(parcel, choice) }
            } catch (error: IllegalArgumentException) {
                _uiState.value = current.copy(busy = false)
                return@launch onError(error.message ?: "Could not read that parcel.")
            }
            _uiState.value = current.copy(plan = plan, busy = false)
        }
    }

    /** Previews a chapter copy as [senderFriendId]'s, once the reader has said it is theirs. */
    fun previewCopy(
        senderFriendId: Long,
        decoded: ChapterPasteResult.Ok,
        onChapterCopy: (CopyPreview) -> Unit,
        onError: (String) -> Unit
    ) {
        _uiState.value = current.copy(busy = true)
        viewModelScope.launch {
            val preview = try {
                sharedChapters.previewCopy(senderFriendId, decoded.snapshot, decoded.body)
            } catch (error: SharedChapterException) {
                _uiState.value = current.copy(busy = false)
                return@launch onError(error.message ?: "Could not read that copy.")
            }
            _uiState.value = current.copy(busy = false)
            onChapterCopy(preview)
        }
    }

    /** Keeps a copy the user has looked at. [onKept] gets the chapter it now is. */
    fun keepCopy(preview: CopyPreview, onKept: (Long) -> Unit, onError: (String) -> Unit) {
        if (current.busy) return
        _uiState.value = current.copy(busy = true)
        viewModelScope.launch {
            val chapterId = try {
                sharedChapters.keepCopy(preview)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = current.copy(busy = false)
                return@launch onError(error.message ?: "Could not keep that copy.")
            }
            _uiState.value = current.copy(busy = false)
            if (chapterId == null) onError("Nothing changed: you already have this copy.") else onKept(chapterId)
        }
    }

    /**
     * Answers [invite] as coming from [friendId], or from a new person called [newName] when the
     * reader said it is someone not in their list yet. That person is only added once the mailbox is
     * known to be on, and taken out again if answering fails, so a refused invite leaves nobody
     * behind. [onMailboxOff] is the common first-time case: answering needs the mailbox on, and the
     * screen can offer to open its settings.
     */
    fun acceptInvite(
        invite: LinkInviteCode,
        friendId: Long?,
        newName: String?,
        onDone: (String) -> Unit,
        onMailboxOff: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (current.busy) return
        _uiState.value = current.copy(busy = true)
        viewModelScope.launch {
            var added: Friend? = null
            val outcome: Result<String?> = try {
                if (identities.status() !is MailboxStatus.On) {
                    Result.success(null)
                } else {
                    val target = friendId ?: withContext(Dispatchers.IO) {
                        val name = requireNotNull(newName) { "Say who this invite is from." }
                        val id = db.friendDao().insertFriend(
                            Friend(name = name, avatarInitials = initialsOf(name, fallback = "F"), addedEpoch = System.currentTimeMillis())
                        )
                        db.friendDao().getFriendById(id)?.also { added = it }?.id ?: id
                    }
                    Result.success(links.acceptInvite(invite, target))
                }
            } catch (error: LinkException) {
                Result.failure(error)
            } catch (error: MailboxException) {
                Result.failure(error)
            }
            if (outcome.isFailure) {
                added?.let { friend -> withContext(Dispatchers.IO) { db.friendDao().deleteFriend(friend) } }
            }
            if (outcome.isSuccess && added != null) loadFriends()
            _uiState.value = current.copy(busy = false)
            outcome.fold(
                onSuccess = { friendName ->
                    if (friendName == null) {
                        onMailboxOff()
                    } else {
                        onDone("Invite accepted. You and $friendName are linked as soon as they confirm it.")
                    }
                },
                onFailure = { onError(it.message ?: "Could not accept that invite.") }
            )
        }
    }

    /** Opens a parcel collected from the mailbox straight into review. Safe to call again on rotation. */
    fun loadMailboxParcel(messageId: String, onError: (String) -> Unit) {
        val state = current
        if (state.mailboxMessageId == messageId && (state.plan != null || state.busy)) return
        _uiState.value = state.copy(mailboxMessageId = messageId, busy = true)
        viewModelScope.launch {
            val plan = try {
                withContext(Dispatchers.IO) { repository.buildMailboxPlan(messageId) }
            } catch (error: IllegalArgumentException) {
                _uiState.value = current.copy(busy = false)
                return@launch onError(error.message ?: "Could not open that parcel.")
            }
            _uiState.value = current.copy(
                plan = plan,
                busy = false,
                skipped = emptySet(),
                personMappings = emptyMap()
            )
        }
    }

    fun dismissMailboxParcel(onDone: () -> Unit) {
        val messageId = current.mailboxMessageId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.dismissMailboxParcel(messageId) }
            onDone()
        }
    }

    fun toggleSkipped(index: Int) {
        val state = current
        _uiState.value = state.copy(
            skipped = if (index in state.skipped) state.skipped - index else state.skipped + index
        )
    }

    /** Passing a null [friendId] takes the mapping back off, returning the name to unidentified. */
    fun mapPerson(name: String, friendId: Long?) {
        val state = current
        _uiState.value = state.copy(
            personMappings = if (friendId == null) {
                state.personMappings - name
            } else {
                state.personMappings + (name to friendId)
            }
        )
    }

    fun backToSetup() {
        _uiState.value = current.copy(parcel = null, plan = null, skipped = emptySet(), personMappings = emptyMap())
    }

    fun commit(onDone: (ParcelImportResult) -> Unit, onError: (String) -> Unit) {
        val state = current
        val plan = state.plan ?: return
        if (!plan.canSave) return onError("Say who sent this first.")
        _uiState.value = state.copy(busy = true)
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    repository.commit(plan, state.skipped, state.personMappings)
                }
            } catch (error: Exception) {
                _uiState.value = current.copy(busy = false)
                return@launch onError(error.message ?: "Could not save these transactions.")
            }
            _uiState.value = current.copy(busy = false)
            onDone(result)
        }
    }
}
