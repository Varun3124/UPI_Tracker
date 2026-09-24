package com.varun.upitracker.ui.mailbox

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.backup.DriveException
import com.varun.upitracker.data.mailbox.LinkException
import com.varun.upitracker.data.mailbox.MailboxException
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.mailbox.TurnOnResult
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.maintenance.MailboxSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class MailboxUiState(
    val status: MailboxStatus? = null,
    val busyMessage: String? = null
) {
    val isBusy: Boolean get() = busyMessage != null
}

/**
 * The friends mailbox settings screen's state and its slow work.
 *
 * Handles no Google UI, like [com.varun.upitracker.ui.backup.BackupViewModel]: [MailboxActivity] does
 * the sign-in sheet and the Drive consent, and hands the tokens in.
 */
class MailboxViewModel(context: Context) : ViewModel() {

    private companion object {
        const val TAG = "MailboxViewModel"
    }

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val identities = MailboxIdentityRepository(appContext, db)

    private val _uiState = MutableLiveData(MailboxUiState())
    val uiState: LiveData<MailboxUiState> = _uiState

    private val current: MailboxUiState get() = _uiState.value ?: MailboxUiState()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = current.copy(status = identities.status())
            // Turning the mailbox on or off here is what starts and stops the background check.
            MailboxSchedule.applyTo(appContext)
        }
    }

    fun turnOn(
        googleIdToken: String,
        email: String,
        displayName: String?,
        driveToken: String,
        onDone: (TurnOnResult) -> Unit,
        onError: (String) -> Unit
    ) {
        perform(
            "Turning the friends mailbox on…",
            onError,
            { identities.turnOn(googleIdToken, email, displayName, driveToken) }
        ) { result ->
            refresh()
            onDone(result)
        }
    }

    fun turnOff(onError: (String) -> Unit) {
        perform("Turning the friends mailbox off…", onError, { identities.signOut() }) { refresh() }
    }

    fun deleteAccount(
        googleIdToken: String,
        email: String,
        displayName: String?,
        driveToken: String,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        perform(
            "Deleting your mailbox account…",
            onError,
            { identities.deleteAccount(googleIdToken, email, displayName, driveToken) }
        ) {
            refresh()
            onDone()
        }
    }

    /**
     * Shows the spinner while [block] runs, then reports how it went. Busy is cleared before the
     * result is reported, for the reason [com.varun.upitracker.ui.backup.BackupViewModel] explains:
     * a result often starts the next step, and that step must not find the screen still busy.
     */
    private fun <T> perform(
        busyMessage: String,
        onError: (String) -> Unit,
        block: suspend () -> T,
        onResult: (T) -> Unit
    ) {
        if (current.isBusy) {
            Log.w(TAG, "Ignored \"$busyMessage\": still busy with \"${current.busyMessage}\"")
            return
        }
        _uiState.value = current.copy(busyMessage = busyMessage)

        viewModelScope.launch {
            val outcome: Result<T> = try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "\"$busyMessage\" failed", error)
                Result.failure(error)
            }

            _uiState.value = current.copy(busyMessage = null)
            outcome.fold(onSuccess = onResult, onFailure = { onError(messageFor(it)) })
        }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is MailboxException -> error.message ?: "The friends mailbox could not be reached."
        is DriveException -> error.message ?: "Google Drive could not be reached."
        is LinkException -> error.message ?: "Something went wrong."
        else -> error.message ?: "Something went wrong."
    }
}
