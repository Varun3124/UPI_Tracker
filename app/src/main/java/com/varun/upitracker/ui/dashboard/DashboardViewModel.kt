package com.varun.upitracker.ui.dashboard

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.MailboxMessageState
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.maintenance.MailboxCollection
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.LedgerEntry
import kotlinx.coroutines.Dispatchers
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.domain.chapter.ChapterParty
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val dailySpendPaise: Long = 0L,
    val weeklySpendPaise: Long = 0L,
    val monthlySpendPaise: Long = 0L,
    val recentEntries: List<LedgerEntry> = emptyList(),
    val accountLabels: Map<String, String> = emptyMap(),
    val iouSummaries: List<com.varun.upitracker.data.repository.FriendLedgerSummary> = emptyList(),
    /** Parcels and invite replies from friends, collected and not yet looked at. */
    val mailboxWaiting: Int = 0,
    /** Whether this phone is signed in to the friends mailbox, which is what puts the bell there. */
    val mailboxOn: Boolean = false,
    /** Open chapters, so the dashboard can show where group money stands without opening one. */
    val chapters: List<DashboardChapter> = emptyList()
)

/** One open chapter, reduced to what a dashboard row needs. */
data class DashboardChapter(
    val chapterId: Long,
    val name: String,
    val myNetPaise: Long,
    val settled: Boolean,
    val isActive: Boolean
)

class DashboardViewModel(private val context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val identities = MailboxIdentityRepository(context.applicationContext, db)
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
                    iouSummaries = LedgerRepository(db).getAllSummaries(),
                    mailboxWaiting = db.mailboxDao().countInState(MailboxMessageState.NEW),
                    mailboxOn = identities.isSignedIn(),
                    chapters = ChapterRepository(db).let { chapters ->
                        db.chapterDao().getOpen().map { chapter ->
                            val result = chapters.resultFor(chapter.id)
                            DashboardChapter(
                                chapterId = chapter.id,
                                name = chapter.name,
                                myNetPaise = result.nets[ChapterParty.Me] ?: 0L,
                                settled = result.settled,
                                isActive = chapter.isActive
                            )
                        }
                    }
                )
            }
            _uiState.value = state
        }
    }

    fun scanSmsBacklog() {
        viewModelScope.launch(Dispatchers.IO) { SmsBacklogScanner(context.applicationContext).scan() }
    }

    /**
     * Collects the friends mailbox as the dashboard comes back. Throttled inside [MailboxCollection],
     * so flicking between screens costs nothing, and the count is only reloaded when a collection
     * actually ran.
     */
    fun collectMailbox() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { MailboxCollection(context.applicationContext).run() }
            if (report != null) loadData()
        }
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
