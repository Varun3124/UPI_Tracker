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
import com.varun.upitracker.domain.statistics.PerDayRate
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.maintenance.MailboxCollection
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.LedgerEntry
import kotlinx.coroutines.Dispatchers
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.domain.chapter.ChapterPlanLabels
import com.varun.upitracker.domain.chapter.ChapterPlanRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val dailySpendPaise: Long = 0L,
    val weeklySpendPaise: Long = 0L,
    val monthlySpendPaise: Long = 0L,
    /**
     * Days of this week and this month that have happened, so the figures above can be read as a
     * rate. Today is deliberately absent: a day's rate is the day's figure.
     */
    val weeklyDays: Int = 0,
    val monthlyDays: Int = 0,
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

/**
 * One open chapter, reduced to what the IOU slider needs: a heading, and the payments that settle it.
 *
 * The plan rather than the user's net, because the plan is the thing there is something to do about
 * -- and it is the whole of the chapter, including the payments between two friends that the user's
 * own net says nothing about.
 */
data class DashboardChapter(
    val chapterId: Long,
    val name: String,
    val plan: List<ChapterPlanRow>
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
                val weekly = StatisticsPeriods.rangeFor(StatsPeriod.WEEKLY, now)
                val monthly = StatisticsPeriods.rangeFor(StatsPeriod.MONTHLY, now)
                DashboardUiState(
                    dailySpendPaise = accountRepository.getSpendSince(spendFrom(StatsPeriod.DAILY, now)),
                    weeklySpendPaise = accountRepository.getSpendSince(weekly.fromExclusive),
                    monthlySpendPaise = accountRepository.getSpendSince(monthly.fromExclusive),
                    // fromExclusive is one millisecond before the first day; add it back to get the
                    // inclusive start the day count is measured from.
                    weeklyDays = PerDayRate.daysElapsed(weekly.fromExclusive + 1, weekly.toInclusive, now),
                    monthlyDays = PerDayRate.daysElapsed(monthly.fromExclusive + 1, monthly.toInclusive, now),
                    recentEntries = (transactions + transfers)
                        .sortedByDescending { it.dateEpoch }
                        .take(5),
                    accountLabels = db.accountDao().getAllSync().associate { it.id to it.label },
                    iouSummaries = LedgerRepository(db).getAllSummaries(),
                    mailboxWaiting = db.mailboxDao().countInState(MailboxMessageState.NEW),
                    mailboxOn = identities.isSignedIn(),
                    chapters = loadOpenChapters(db)
                )
            }
            _uiState.value = state
        }
    }

    /**
     * Every open chapter's plan, with the names already resolved.
     *
     * The friend names are read once for the whole set rather than per payment: a chapter's plan
     * names members by id, and asking the database per row would be one query per person per
     * chapter on every dashboard load.
     *
     * A closed chapter is left out. It still counts towards every balance, but there is nothing left
     * to do about it, so it stays on the chapters screen.
     */
    private suspend fun loadOpenChapters(db: AppDatabase): List<DashboardChapter> {
        val open = db.chapterDao().getOpen()
        if (open.isEmpty()) return emptyList()
        val names = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val chapters = ChapterRepository(db)
        return open.map { chapter ->
            DashboardChapter(
                chapterId = chapter.id,
                name = chapter.name,
                plan = chapters.resultFor(chapter.id).plan.map { ChapterPlanLabels.rowFor(it, names) }
            )
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
