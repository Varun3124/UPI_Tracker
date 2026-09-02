package com.varun.upitracker.ui.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.ImportPlan
import com.varun.upitracker.data.repository.ImportResult
import com.varun.upitracker.data.repository.StatementImportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.statement.StatementRow
import com.varun.upitracker.statement.XlsStatementReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatementImportUiState(
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val fileName: String? = null,
    val rowCount: Int = 0,
    val fromEpoch: Long? = null,
    val toEpoch: Long? = null,
    /** Non-null once the statement has been matched — the screen switches to its review state. */
    val plan: ImportPlan? = null,
    /** Index into [ImportPlan.unresolved] -> id of the existing transaction the user ticked. */
    val selections: Map<Int, Long> = emptyMap(),
    val busy: Boolean = false
) {
    val canProcess: Boolean
        get() = rowCount > 0 && selectedAccountId != null && fromEpoch != null && toEpoch != null && !busy

    /** Unresolved rows with nothing ticked become new pending transactions. */
    val pendingCount: Int
        get() = (plan?.unresolved?.size ?: 0) - selections.size
}

/**
 * Holds the parsed statement and the match plan for [StatementImportActivity].
 *
 * The plan is far too large to hand between activities through an Intent, and there is no
 * app-level singleton to park it in, so the whole flow lives in one Activity backed by this.
 */
class StatementImportViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val repository = StatementImportRepository(db)
    private val accountRepository = AccountRepository(db)

    private val _uiState = MutableLiveData(StatementImportUiState())
    val uiState: LiveData<StatementImportUiState> = _uiState

    private var rows: List<StatementRow> = emptyList()

    private val current: StatementImportUiState
        get() = _uiState.value ?: StatementImportUiState()

    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) {
                db.accountDao().getActiveByTypes(listOf(AccountType.SAVINGS))
            }
            val preferred = withContext(Dispatchers.IO) {
                accountRepository.getDefaultAccountByType(AccountType.SAVINGS)
            }
            _uiState.value = current.copy(
                accounts = accounts,
                selectedAccountId = current.selectedAccountId
                    ?: preferred?.id
                    ?: accounts.firstOrNull()?.id
            )
        }
    }

    fun selectAccount(accountId: String) {
        if (accountId == current.selectedAccountId) return
        _uiState.value = current.copy(selectedAccountId = accountId)
    }

    /**
     * Parses the chosen file and defaults the range to the statement's own span, so the common
     * case is one tap away.
     */
    fun loadFile(uri: Uri, onError: (String) -> Unit) {
        _uiState.value = current.copy(busy = true)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open the selected file.")
                    stream.use { XlsStatementReader.read(it) }
                }
                rows = parsed
                _uiState.value = current.copy(
                    fileName = displayName(uri),
                    rowCount = parsed.size,
                    fromEpoch = parsed.minOf { it.dateEpoch },
                    toEpoch = parsed.maxOf { it.dateEpoch },
                    plan = null,
                    selections = emptyMap(),
                    busy = false
                )
            } catch (error: Exception) {
                rows = emptyList()
                _uiState.value = current.copy(
                    fileName = null,
                    rowCount = 0,
                    fromEpoch = null,
                    toEpoch = null,
                    busy = false
                )
                onError(error.message ?: "Could not read that statement.")
            }
        }
    }

    fun setFromEpoch(epoch: Long) {
        _uiState.value = current.copy(fromEpoch = epoch)
    }

    fun setToEpoch(epoch: Long) {
        _uiState.value = current.copy(toEpoch = epoch)
    }

    fun process(onError: (String) -> Unit) {
        val state = current
        val accountId = state.selectedAccountId ?: return onError("Pick an account first.")
        val from = state.fromEpoch ?: return onError("Pick a date range first.")
        val to = state.toEpoch ?: return onError("Pick a date range first.")
        if (from > to) return onError("The From date must not be after the To date.")

        _uiState.value = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    repository.buildPlan(rows, accountId, from, to)
                }
                _uiState.value = current.copy(plan = plan, selections = emptyMap(), busy = false)
            } catch (error: Exception) {
                _uiState.value = current.copy(busy = false)
                onError(error.message ?: "Could not match this statement.")
            }
        }
    }

    /** Single-select per group: ticking the already-ticked candidate clears it. */
    fun toggleSelection(groupIndex: Int, transactionId: Long) {
        val selections = current.selections.toMutableMap()
        if (selections[groupIndex] == transactionId) {
            selections.remove(groupIndex)
        } else {
            selections[groupIndex] = transactionId
        }
        _uiState.value = current.copy(selections = selections)
    }

    fun backToSetup() {
        _uiState.value = current.copy(plan = null, selections = emptyMap())
    }

    fun commit(onDone: (ImportResult) -> Unit, onError: (String) -> Unit) {
        val state = current
        val plan = state.plan ?: return onError("Nothing to import.")

        _uiState.value = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.commit(plan, state.selections) }
                _uiState.value = current.copy(busy = false)
                onDone(result)
            } catch (error: Exception) {
                _uiState.value = current.copy(busy = false)
                onError(error.message ?: "Could not save the import.")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "Statement"
    }
}
