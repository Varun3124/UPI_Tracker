package com.varun.upitracker.ui.dashboard

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class DashboardUiState(
    val dailySpendPaise: Long = 0L,
    val monthlySpendPaise: Long = 0L,
    val recentEntries: List<LedgerEntry> = emptyList(),
    val accountLabels: Map<String, String> = emptyMap(),
    val iouSummaries: List<com.varun.upitracker.data.repository.FriendLedgerSummary> = emptyList()
)

class DashboardViewModel(private val context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _uiState = MutableLiveData(DashboardUiState())
    val uiState: LiveData<DashboardUiState> = _uiState

    fun loadData() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val state = withContext(Dispatchers.IO) {
                val accountRepository = AccountRepository(db)
                // Taking 5 of each is exact: the overall newest 5 can only come from these.
                val transactions = db.transactionDao().getRecentTransactions(5).map(LedgerEntry::Tx)
                val transfers = db.accountTransferDao().getRecentTransfers(5).map(LedgerEntry::Transfer)
                DashboardUiState(
                    dailySpendPaise = accountRepository.getSpendSince(startOfDay(now)),
                    monthlySpendPaise = accountRepository.getSpendSince(startOfMonth(now)),
                    recentEntries = (transactions + transfers)
                        .sortedByDescending { it.dateEpoch }
                        .take(5),
                    accountLabels = db.accountDao().getAllSync().associate { it.id to it.label },
                    iouSummaries = LedgerRepository(db).getAllSummaries()
                )
            }
            _uiState.value = state
        }
    }

    fun scanSmsBacklog() {
        viewModelScope.launch(Dispatchers.IO) { SmsBacklogScanner(context.applicationContext).scan() }
    }

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfMonth(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

class DashboardViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            return DashboardViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
