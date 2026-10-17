package com.varun.upitracker.ui.statistics

import android.content.Context
import com.varun.upitracker.database.entity.Account
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.TrendsRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.domain.statistics.Breakdown
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.DateRange
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.BalanceConfidence
import com.varun.upitracker.domain.statistics.BalancePoints
import com.varun.upitracker.domain.statistics.BalanceTimeline
import com.varun.upitracker.domain.statistics.PerDayRate
import com.varun.upitracker.domain.statistics.parseAccountScope
import com.varun.upitracker.domain.statistics.serialise
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.BackStep
import com.varun.upitracker.ui.PageHistory
import com.varun.upitracker.domain.statistics.StatsAggregator
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.domain.statistics.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which half of the screen is showing.
 *
 * One activity rather than two: the period control, the range and the loaded breakdown are shared,
 * and a second activity would have to duplicate all of it and then keep the two in step.
 */
enum class StatsSection { CATEGORIES, TRENDS }

/**
 * The trends half: the scope's combined balance after every change, for the graph to move around
 * in. Loaded once per scope, apart from the categories half, which most visits never leave: the pie
 * needs one query, this needs the scope's whole history.
 *
 * Where the graph is looking is not here. It belongs to the graph alone, and moving around in it
 * changes nothing the rest of the screen -- or the back history -- knows about.
 */
data class TrendsUiState(
    /** One point per change in the combined balance, oldest first; null until loaded, or with no history. */
    val points: BalancePoints? = null,
    /** False when the scope resolves to nothing, where a flat zero line would be a lie. */
    val hasAccounts: Boolean = true,
    val scope: AccountScope = AccountScope.Liquid,
    /** Every account, for the scope picker -- archived ones included, since one can be named. */
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * When the scope's combined balance stops being a reconstruction; null when it never does.
     *
     * The latest of the in-scope accounts' first snapshots, so a total is only trusted once every
     * part of it has been counted at least once. See [BalanceConfidence].
     */
    val balanceCertainFromEpoch: Long? = null,
    /** When the points were read; the line is carried flat from its last change up to here. */
    val loadedAtEpoch: Long = 0L
) {
    val latestBalancePaise: Long get() = points?.balances?.last() ?: 0L

    val isLatestSpeculative: Boolean
        get() = BalanceConfidence.isSpeculative(loadedAtEpoch, balanceCertainFromEpoch)
}

data class StatisticsUiState(
    val section: StatsSection = StatsSection.CATEGORIES,
    val period: StatsPeriod = StatsPeriod.WEEKLY,
    val anchorEpoch: Long = 0L,
    val range: DateRange = DateRange(0L, 0L),
    val canGoForward: Boolean = false,
    val breakdown: Breakdown = Breakdown(emptyList()),
    /** Non-null while drilled into one category; the screen then shows its counterparties. */
    val drilledCategory: CategorySlice? = null,
    val payeeSlices: List<CategorySlice> = emptyList(),
    val trends: TrendsUiState = TrendsUiState(),
    /**
     * Days of [range] that have happened, which every figure on the Categories half is divided by.
     *
     * Zero or one means there is no rate worth showing -- a single day's rate is the figure itself --
     * and the screen hides all of them rather than printing each one twice.
     */
    val rangeDays: Int = 0,
    val isLoading: Boolean = true
)

class StatisticsViewModel(context: Context) : ViewModel() {

    private val prefs = context.applicationContext
        .getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val repository = AccountRepository(db)
    private val trendsRepository = TrendsRepository(db)

    private val _uiState = MutableLiveData(StatisticsUiState())
    val uiState: LiveData<StatisticsUiState> = _uiState

    private var period = StatsPeriod.WEEKLY
    private var anchor = System.currentTimeMillis()
    private var customFrom = 0L
    private var customTo = 0L
    private var drilled: CategorySlice? = null
    private var section = StatsSection.CATEGORIES
    private var accountScope: AccountScope = parseAccountScope(prefs.getString(PREF_TRENDS_SCOPE, null))
    private var loadJob: Job? = null
    private var trendsJob: Job? = null

    /** What the loaded series covers, so switching back to Trends does not reload it. */
    private var loadedTrendsKey: String? = null

    /**
     * The scope's account ids and oldest epoch, so switching scopes back and forth does not ask
     * again. Neither changes while this screen is open -- nothing here reloads on resume, so an
     * account added elsewhere is already invisible to the rest of it.
     */
    private val resolvedScopes = HashMap<String, Pair<Set<String>, Long?>>()

    /** Read once. An account added elsewhere is already invisible to the rest of this screen. */
    private var loadedAccounts: List<Account>? = null

    /**
     * What back steps through one change at a time: which section, and which period, day or range.
     * The category drilled into and the account scope are filters, and back treats them apart.
     * Where the Trends graph is looking is neither: moving around in it is never a step.
     */
    private data class Snapshot(
        val period: StatsPeriod,
        val anchor: Long,
        val customFrom: Long,
        val customTo: Long,
        val section: StatsSection
    )

    /** The filters the screen opened with: no category, and the scope it last kept. */
    private val openedScope: AccountScope = accountScope

    /**
     * Two snapshots look the same when they show the same span: the moment a period was opened is
     * kept to restore it by, but Weekly picked again within the same week draws the same week.
     */
    private val history = PageHistory<Snapshot>(this) { a, b -> a.shown() == b.shown() }

    private fun Snapshot.shown() = copy(
        anchor = StatisticsPeriods.rangeFor(period, anchor, customFrom, customTo).fromExclusive,
        customFrom = if (period == StatsPeriod.CUSTOM) customFrom else 0L,
        customTo = if (period == StatsPeriod.CUSTOM) customTo else 0L
    )

    private fun snapshot() = Snapshot(period, anchor, customFrom, customTo, section)

    /** After a filter changes: one "filters applied" step while they differ from how it opened. */
    private fun noteFilters() = history.filtersChanged(drilled != null || accountScope != openedScope)

    private fun applyScope(next: AccountScope) {
        accountScope = next
        prefs.edit().putString(PREF_TRENDS_SCOPE, next.serialise()).apply()
    }

    /**
     * Wraps every change a person makes here, so back can return to just before it. Nothing is kept
     * when it changed nothing on screen -- see [PageHistory.record].
     */
    private inline fun changing(apply: () -> Unit) {
        val before = snapshot()
        apply()
        history.record(before, snapshot())
    }

    /**
     * Puts the screen back as it was before the last change -- the weekly summary a bar was tapped
     * from, say -- or, from filtered figures, takes the category and scope back to how it opened.
     * False when there is nothing left to step back through, and back should leave.
     */
    fun goBack(): Boolean {
        when (val step = history.pop(snapshot()) ?: return false) {
            is BackStep.Restore -> {
                val previous = step.snapshot
                period = previous.period
                anchor = previous.anchor
                customFrom = previous.customFrom
                customTo = previous.customTo
                section = previous.section
                _uiState.value = _uiState.value?.copy(section = section)
            }
            BackStep.ClearFilters -> {
                drilled = null
                if (accountScope != openedScope) applyScope(openedScope)
            }
        }
        // Reloads Trends too when that is the section showing.
        load()
        return true
    }

    override fun onCleared() {
        history.release()
    }

    /** Persisted, so a hand-picked set is still there next time rather than silently reset. */
    fun selectScope(next: AccountScope) {
        if (next == accountScope) return
        applyScope(next)
        noteFilters()
        loadTrends()
    }

    /**
     * Swaps the visible section without reloading: both are drawn from the same period and range,
     * so a round trip would only redraw what is already on screen.
     */
    fun selectSection(next: StatsSection) {
        if (next == section) return
        changing { section = next }
        _uiState.value = _uiState.value?.copy(section = next)
        if (next == StatsSection.TRENDS) loadTrends()
    }

    /** Scopes the screen to one category. Survives period changes and stepping. */
    fun drillInto(slice: CategorySlice) {
        if (slice == drilled) return
        drilled = slice
        noteFilters()
        load()
    }

    fun clearDrill() {
        if (drilled == null) return
        drilled = null
        noteFilters()
        load()
    }

    fun selectPeriod(next: StatsPeriod) {
        changing {
            period = next
            anchor = System.currentTimeMillis()
        }
        load()
    }

    /** Opens a single day, used when a bar column is tapped. */
    fun selectDay(dayStartEpoch: Long) {
        changing {
            period = StatsPeriod.DAILY
            anchor = dayStartEpoch
        }
        load()
    }

    fun selectCustomRange(fromEpoch: Long, toEpoch: Long) {
        changing {
            period = StatsPeriod.CUSTOM
            customFrom = fromEpoch
            customTo = toEpoch
        }
        load()
    }

    /** [delta] is -1 for the previous period, +1 for the next. Guarded against the future. */
    fun step(delta: Int) {
        if (!period.isShiftable) return
        if (delta > 0 && !StatisticsPeriods.canShiftForward(period, anchor, System.currentTimeMillis())) return
        changing { anchor = StatisticsPeriods.shift(period, anchor, delta) }
        load()
    }

    fun load() {
        // A fast swipe fires several loads; without this the slowest could land last and leave the
        // charts showing a period the header has already moved off.
        loadJob?.cancel()
        val period = this.period
        val anchor = this.anchor
        val range = StatisticsPeriods.rangeFor(period, anchor, customFrom, customTo)

        val drilled = this.drilled

        loadJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (breakdown, payees, days) = withContext(Dispatchers.IO) {
                val result = loadBreakdown(period, anchor, range)
                val payeeTotals = drilled?.let {
                    repository.getPayeeTotalsForCategory(
                        CategoryKind.EXPENSE, it.categoryId, range.fromExclusive, range.toInclusive
                    )
                }.orEmpty()
                Triple(result, StatsAggregator.toPayeeSlices(payeeTotals), daysIn(range, now))
            }
            _uiState.value = StatisticsUiState(
                section = section,
                period = period,
                anchorEpoch = anchor,
                range = range,
                canGoForward = StatisticsPeriods.canShiftForward(period, anchor, System.currentTimeMillis()),
                breakdown = breakdown,
                // Re-priced against this window, so the header total follows a period change even
                // when the category is absent from the new one.
                drilledCategory = drilled?.copy(paise = payees.sumOf { it.paise }),
                payeeSlices = payees,
                // Carried across rather than reset: drilling into a category reloads this screen
                // but changes nothing the balance line is drawn from, and rebuilding the state
                // wholesale would otherwise strand Trends on a spinner the cache never clears.
                trends = _uiState.value?.trends ?: TrendsUiState(),
                rangeDays = days,
                isLoading = false
            )
            if (section == StatsSection.TRENDS) loadTrends()
        }
    }

    /**
     * How many days the figures on screen are spread over.
     *
     * All time has no lower bound to measure from -- it is [Long.MIN_VALUE], and adding one to that
     * would overflow -- so it is resolved to the oldest transaction on record. Unscoped, matching
     * the category totals it divides: those count rows with no account recorded, so the span must too.
     */
    private suspend fun daysIn(range: DateRange, now: Long): Int {
        val from = if (range.fromExclusive == Long.MIN_VALUE) {
            db.transactionDao().getEarliestDateEpoch() ?: return 0
        } else {
            range.fromExclusive + 1
        }
        return PerDayRate.daysElapsed(from, range.toInclusive, now)
    }

    /**
     * The scope's balance after every change, for the graph.
     *
     * Keyed on the scope alone: the graph covers all of history and moves around in it on its own, so
     * nothing it does -- and no period picked on the Categories side -- needs anything reloaded.
     * Switching between the two sections costs nothing once it is loaded.
     */
    private fun loadTrends() {
        val scope = accountScope
        val key = scope.serialise()
        if (key == loadedTrendsKey) return

        trendsJob?.cancel()
        loadedTrendsKey = null
        _uiState.value = _uiState.value?.let { it.copy(trends = it.trends.copy(isLoading = true)) }

        trendsJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.IO) { buildTrends(scope) }
            loadedTrendsKey = key
            _uiState.value = _uiState.value?.copy(trends = computed)
        }
    }

    /**
     * One load of the scope's whole history and one pass over it: every transaction, transfer and
     * reconciliation, turned into the combined balance just after each. See [BalanceTimeline.points].
     */
    private suspend fun buildTrends(scope: AccountScope): TrendsUiState {
        val accounts = accounts()
        val (ids, earliest) = resolveScope(scope)
        val now = System.currentTimeMillis()
        if (ids.isEmpty() || earliest == null) {
            return TrendsUiState(hasAccounts = ids.isNotEmpty(), scope = scope, accounts = accounts, isLoading = false)
        }
        val inputs = trendsRepository.loadBalanceInputs(earliest, now + 1, ids)
        return TrendsUiState(
            points = BalanceTimeline.points(
                spanFrom = earliest,
                accountIds = ids,
                openingByAccount = inputs.openingByAccount,
                movements = inputs.movements,
                anchorsByAccount = inputs.anchorsByAccount
            ),
            hasAccounts = true,
            scope = scope,
            accounts = accounts,
            isLoading = false,
            balanceCertainFromEpoch = repository.balanceCertainFrom(ids),
            loadedAtEpoch = now
        )
    }

    /**
     * The weekly period queries once per day and folds, rather than querying the week and again
     * per day. The seven day windows tile the week exactly, so the folded total is the same number
     * the single query would have returned -- and being the same number by construction, the pie
     * and the bars beneath it cannot drift apart.
     */
    private suspend fun loadBreakdown(
        period: StatsPeriod,
        anchor: Long,
        range: DateRange
    ): Breakdown {
        if (period != StatsPeriod.WEEKLY) {
            return Breakdown(
                StatsAggregator.toSlices(
                    repository.getTotalsByCategory(
                        CategoryKind.EXPENSE, range.fromExclusive, range.toInclusive
                    )
                )
            )
        }

        val days = StatisticsPeriods.daysOfWeek(anchor)
        val perDay = days.map { dayStart ->
            val dayRange = StatisticsPeriods.dayRange(dayStart)
            repository.getTotalsByCategory(
                CategoryKind.EXPENSE, dayRange.fromExclusive, dayRange.toInclusive
            )
        }
        return StatsAggregator.foldDays(days, days.map(::dayLabel), perDay)
    }

    private suspend fun accounts(): List<Account> =
        loadedAccounts ?: db.accountDao().getAllSync().also { loadedAccounts = it }

    /** The scope's ids and oldest epoch, answered once per scope. */
    private suspend fun resolveScope(scope: AccountScope): Pair<Set<String>, Long?> {
        resolvedScopes["$scope"]?.let { return it }
        val ids = scope.resolve(accounts())
        val resolved = ids to trendsRepository.earliestDataEpoch(ids)
        resolvedScopes["$scope"] = resolved
        return resolved
    }

    private companion object {
        const val PREF_TRENDS_SCOPE = "trends_account_scope"
    }

    private fun dayLabel(dayStartEpoch: Long): String =
        java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            .format(java.util.Date(dayStartEpoch))
}

class StatisticsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            return StatisticsViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
