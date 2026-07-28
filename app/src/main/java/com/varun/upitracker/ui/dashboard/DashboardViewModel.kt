package com.varun.upitracker.ui.dashboard

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.sms.SmsBacklogScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class DashboardUiState(
    val dailySpendPaise: Long = 0L,
    val monthlySpendPaise: Long = 0L,
    val recentTransactions: List<Transaction> = emptyList(),
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
                DashboardUiState(
                    dailySpendPaise = db.transactionDao().getTotalDebitSince(startOfDay(now)) ?: 0L,
                    monthlySpendPaise = db.transactionDao().getTotalDebitSince(startOfMonth(now)) ?: 0L,
                    recentTransactions = db.transactionDao().getRecentTransactions(5),
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
