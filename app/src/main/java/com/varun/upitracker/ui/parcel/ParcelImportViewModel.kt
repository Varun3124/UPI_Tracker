package com.varun.upitracker.ui.parcel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.ParcelImportPlan
import com.varun.upitracker.data.repository.ParcelImportRepository
import com.varun.upitracker.data.repository.ParcelImportResult
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.domain.parcel.ParcelCodec
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ParcelImportUiState(
    val friends: List<Friend> = emptyList(),
    val senderFriendId: Long? = null,
    val pastedText: String = "",
    /** Non-null once the parcel has been read -- the screen switches to its review state. */
    val plan: ParcelImportPlan? = null,
    /** Entry indices the user has marked as already recorded here, and so will not be written. */
    val skipped: Set<Int> = emptySet(),
    /** A name in a split -> the friend the user says it is. Empty unless they said so. */
    val personMappings: Map<String, Long> = emptyMap(),
    val busy: Boolean = false
) {
    val canRead: Boolean get() = senderFriendId != null && pastedText.isNotBlank() && !busy

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

    private val db = AppDatabase.getInstance(context.applicationContext)
    private val repository = ParcelImportRepository(db)

    private val _uiState = MutableLiveData(ParcelImportUiState())
    val uiState: LiveData<ParcelImportUiState> = _uiState

    private val current: ParcelImportUiState get() = _uiState.value ?: ParcelImportUiState()

    fun loadFriends() {
        viewModelScope.launch {
            val friends = withContext(Dispatchers.IO) { db.friendDao().getAllFriendsSync() }
            _uiState.value = current.copy(friends = friends.sortedBy { it.name.lowercase() })
        }
    }

    fun selectSender(friendId: Long) {
        _uiState.value = current.copy(senderFriendId = friendId)
    }

    fun setText(text: String) {
        if (text == current.pastedText) return
        _uiState.value = current.copy(pastedText = text)
    }

    fun readParcel(onError: (String) -> Unit) {
        val state = current
        val senderFriendId = state.senderFriendId ?: return onError("Say who sent this first.")

        when (val decoded = ParcelCodec.decode(state.pastedText)) {
            is ParcelDecodeResult.Failed -> onError(decoded.reason)
            is ParcelDecodeResult.Ok -> {
                if (decoded.parcel.transactions.isEmpty()) {
                    return onError("That parcel is empty.")
                }
                _uiState.value = state.copy(busy = true)
                viewModelScope.launch {
                    val plan = try {
                        withContext(Dispatchers.IO) {
                            repository.buildPlan(decoded.parcel, senderFriendId)
                        }
                    } catch (error: IllegalArgumentException) {
                        _uiState.value = current.copy(busy = false)
                        return@launch onError(error.message ?: "Could not read that parcel.")
                    }
                    _uiState.value = current.copy(
                        plan = plan,
                        busy = false,
                        skipped = emptySet(),
                        personMappings = emptyMap()
                    )
                }
            }
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
        _uiState.value = current.copy(plan = null, skipped = emptySet(), personMappings = emptyMap())
    }

    fun commit(onDone: (ParcelImportResult) -> Unit, onError: (String) -> Unit) {
        val state = current
        val plan = state.plan ?: return
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
