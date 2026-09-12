package com.varun.upitracker.ui.backup

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.backup.BackupException
import com.varun.upitracker.data.backup.BackupRepository
import com.varun.upitracker.data.backup.DriveException
import com.varun.upitracker.data.backup.GoogleAccountRepository
import com.varun.upitracker.data.backup.LocalSnapshot
import com.varun.upitracker.data.backup.RestoreReport
import com.varun.upitracker.data.backup.UploadSummary
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.backup.BackupDecision
import com.varun.upitracker.domain.backup.LocalBackupState
import com.varun.upitracker.domain.backup.RemoteBackupInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val accountEmail: String? = null,
    val lastBackupEpoch: Long? = null,
    val local: LocalBackupState? = null,
    /** The newest pre-restore copy still on the device, if any. */
    val snapshot: LocalSnapshot? = null,
    val busyMessage: String? = null
) {
    val isSignedIn: Boolean get() = accountEmail != null
    val isBusy: Boolean get() = busyMessage != null
}

/** What "Back up now" came to. */
sealed interface BackUpOutcome {
    data class Done(val summary: UploadSummary) : BackUpOutcome
    /** Uploading would destroy something, or there is nothing to send. A person has to decide. */
    data class NeedsDecision(val decision: BackupDecision) : BackUpOutcome
}

/**
 * Holds the backup screen's state and does the slow parts.
 *
 * Deliberately handles no Google UI. Signing in and getting a Drive token both need an Activity and
 * both may put a sheet on screen, so [BackupActivity] owns those and passes a token in. That keeps
 * the consent dance in one place and leaves this class as plain work.
 *
 * Errors arrive through `onError` callbacks rather than state, matching the rest of this app --
 * see `StatementImportViewModel`.
 */
class BackupViewModel(context: Context) : ViewModel() {

    private companion object {
        private const val TAG = "BackupViewModel"
    }

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val repository = BackupRepository(appContext, db)
    private val accounts = GoogleAccountRepository(appContext)

    private val _uiState = MutableLiveData(BackupUiState())
    val uiState: LiveData<BackupUiState> = _uiState

    private val current: BackupUiState get() = _uiState.value ?: BackupUiState()

    fun refresh() {
        viewModelScope.launch {
            val local = repository.localState()
            val snapshot = withContext(Dispatchers.IO) { repository.snapshots().firstOrNull() }
            _uiState.value = current.copy(
                accountEmail = accounts.signedInAccount(),
                lastBackupEpoch = repository.lastSuccessEpoch(),
                local = local,
                snapshot = snapshot
            )
        }
    }

    fun signOut() {
        accounts.signOut()
        refresh()
    }

    /**
     * Checks whether uploading is safe and, when it is, uploads -- in one busy span.
     *
     * The safe case needs no human, so it never goes back to the Activity between the check and the
     * upload. It used to, and that round trip was a bug: the upload was requested while the check was
     * still marked busy, the busy guard rejected it, and the button silently did nothing. Anything
     * that is not [BackupDecision.SafeUpload] is handed back undecided -- this never resolves a
     * conflict on the user's behalf.
     */
    fun backUpNow(accessToken: String, onOutcome: (BackUpOutcome) -> Unit, onError: (String) -> Unit) {
        perform(
            busyMessage = "Backing up…",
            onError = onError,
            block = {
                when (val decision = repository.planUpload(accessToken)) {
                    is BackupDecision.SafeUpload -> BackUpOutcome.Done(repository.upload(accessToken))
                    else -> BackUpOutcome.NeedsDecision(decision)
                }
            },
            onResult = { outcome ->
                if (outcome is BackUpOutcome.Done) refresh()
                onOutcome(outcome)
            }
        )
    }

    /** Uploads unconditionally. Only for after the user has explicitly chosen to replace a backup. */
    fun upload(accessToken: String, onDone: (UploadSummary) -> Unit, onError: (String) -> Unit) {
        perform("Backing up…", onError, { repository.upload(accessToken) }) { summary ->
            refresh()
            onDone(summary)
        }
    }

    fun planRestore(accessToken: String, onDecision: (BackupDecision) -> Unit, onError: (String) -> Unit) {
        perform("Looking for a backup…", onError, { repository.planRestore(accessToken) }, onDecision)
    }

    fun restore(
        accessToken: String,
        remote: RemoteBackupInfo,
        onDone: (RestoreReport) -> Unit,
        onError: (String) -> Unit
    ) {
        // "Saving a copy first" is said out loud because it is the reason this is safe to try.
        perform(
            "Saving a copy of this phone, then restoring…",
            onError,
            { repository.restore(accessToken, remote) },
            onDone
        )
    }

    fun undoLastRestore(onDone: (RestoreReport?) -> Unit, onError: (String) -> Unit) {
        perform("Putting this phone back…", onError, { repository.undoLastRestore() }, onDone)
    }

    /**
     * Shows the spinner while [block] runs, then reports how it went.
     *
     * **Busy is cleared before [onResult] or [onError] runs, never after.** A result often starts the
     * next step -- a decision leads to an upload, a confirmation to a restore -- and that next step
     * goes through this same guard. Clearing afterwards meant the follow-up saw the screen still busy
     * and was dropped without a word, which is exactly how "Back up now" came to do nothing.
     *
     * A call that arrives while genuinely busy is still refused, and now says so in the log rather
     * than disappearing.
     *
     * Cancellation is rethrown, not reported: it means this ViewModel is being torn down, and there
     * is no screen left to tell.
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

    /**
     * Drive failures are translated by kind rather than passed through raw: a transient one must read
     * as "try again later" so the user does not conclude their backup is broken.
     */
    private fun messageFor(error: Throwable): String = when (error) {
        is DriveException -> error.message ?: "Google Drive could not be reached."
        is BackupException -> error.message ?: "That backup could not be read."
        else -> error.message ?: "Something went wrong."
    }
}
