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
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val dailySpendPaise: Long = 0L,
    val weeklySpendPaise: Long = 0L,
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
                    dailySpendPaise = accountRepository.getSpendSince(spendFrom(StatsPeriod.DAILY, now)),
                    weeklySpendPaise = accountRepository.getSpendSince(spendFrom(StatsPeriod.WEEKLY, now)),
                    monthlySpendPaise = accountRepository.getSpendSince(spendFrom(StatsPeriod.MONTHLY, now)),
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

    /**
     * The lower bound [AccountRepository.getSpendSince] wants, which is **exclusive**.
     *
     * Passing a start-of-period epoch straight in drops anything dated exactly at midnight, and
     * `XlsStatementReader.parseDate` gives every statement-imported row exactly midnight -- so a
     * row imported today was never counted in Today, and one dated the 1st never counted in This
     * Month. [StatisticsPeriods] already returns the corrected bound, and using it here is also
     * what keeps these figures agreeing with the statistics screen they open.
     */
    private fun spendFrom(period: StatsPeriod, now: Long): Long =
        StatisticsPeriods.rangeFor(period, now).fromExclusive
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
