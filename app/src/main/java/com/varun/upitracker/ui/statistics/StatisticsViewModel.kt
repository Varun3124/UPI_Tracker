package com.varun.upitracker.ui.statistics

import android.content.Context
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
import com.varun.upitracker.domain.statistics.BalanceTimeline
import com.varun.upitracker.domain.statistics.StatsAggregator
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.domain.statistics.TrendBucket
import com.varun.upitracker.domain.statistics.TrendsBuckets
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
 * The trends half, loaded separately from the categories half.
 *
 * Separate because it is far more expensive and most visits never open it: the pie needs one query,
 * this needs a range of them plus an opening balance per account.
 */
data class TrendsUiState(
    val bucket: TrendBucket = TrendBucket.DAY,
    /** Ascending, parallel to [balanceSeries]. */
    val bucketStarts: List<Long> = emptyList(),
    /** The combined balance at the end of each bucket. */
    val balanceSeries: List<Long> = emptyList(),
    /**
     * The combined balance immediately *before* the first bucket.
     *
     * The window's change is measured from here, not from the first point: that one already
     * carries its own bucket's movement, so the first day of the window would go uncounted.
     */
    val openingPaise: Long = 0L,
    /** False when the scope resolves to nothing, where a flat zero line would be a lie. */
    val hasAccounts: Boolean = true,
    val isLoading: Boolean = true
) {
    val latestBalancePaise: Long get() = balanceSeries.lastOrNull() ?: 0L
    val changePaise: Long get() = latestBalancePaise - openingPaise
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
    val isLoading: Boolean = true
)

class StatisticsViewModel(context: Context) : ViewModel() {

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
    private var accountScope: AccountScope = AccountScope.Liquid
    private var loadJob: Job? = null
    private var trendsJob: Job? = null

    /** What the loaded series covers, so switching back to Trends does not reload it. */
    private var loadedTrendsKey: String? = null

    /**
     * Swaps the visible section without reloading: both are drawn from the same period and range,
     * so a round trip would only redraw what is already on screen.
     */
    fun selectSection(next: StatsSection) {
        if (next == section) return
        section = next
        _uiState.value = _uiState.value?.copy(section = next)
        if (next == StatsSection.TRENDS) loadTrends()
    }

    /** Scopes the screen to one category. Survives period changes and stepping. */
    fun drillInto(slice: CategorySlice) {
        drilled = slice
        load()
    }

    fun clearDrill() {
        if (drilled == null) return
        drilled = null
        load()
    }

    fun selectPeriod(next: StatsPeriod) {
        period = next
        anchor = System.currentTimeMillis()
        load()
    }

    /** Opens a single day, used when a bar column is tapped. */
    fun selectDay(dayStartEpoch: Long) {
        period = StatsPeriod.DAILY
        anchor = dayStartEpoch
        load()
    }

    fun selectCustomRange(fromEpoch: Long, toEpoch: Long) {
        period = StatsPeriod.CUSTOM
        customFrom = fromEpoch
        customTo = toEpoch
        load()
    }

    /** [delta] is -1 for the previous period, +1 for the next. Guarded against the future. */
    fun step(delta: Int) {
        if (!period.isShiftable) return
        if (delta > 0 && !StatisticsPeriods.canShiftForward(period, anchor, System.currentTimeMillis())) return
        anchor = StatisticsPeriods.shift(period, anchor, delta)
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
            val (breakdown, payees) = withContext(Dispatchers.IO) {
                val result = loadBreakdown(period, anchor, range)
                val payeeTotals = drilled?.let {
                    repository.getPayeeTotalsForCategory(
                        CategoryKind.EXPENSE, it.categoryId, range.fromExclusive, range.toInclusive
                    )
                }.orEmpty()
                result to StatsAggregator.toPayeeSlices(payeeTotals)
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
                isLoading = false
            )
            if (section == StatsSection.TRENDS) loadTrends()
        }
    }

    /**
     * The balance line, for whatever window the current period puts on screen.
     *
     * Skipped when the window and scope are the ones already drawn, so toggling between the two
     * sections costs nothing and does not flash an empty chart on the way back.
     */
    private fun loadTrends() {
        val period = this.period
        val anchor = this.anchor
        val scope = this.accountScope
        val key = "$period/$anchor/$customFrom/$customTo/$scope"
        if (key == loadedTrendsKey) return

        trendsJob?.cancel()
        loadedTrendsKey = null
        _uiState.value = _uiState.value?.copy(trends = TrendsUiState(isLoading = true))

        trendsJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.IO) { buildTrends(period, anchor, scope) }
            loadedTrendsKey = key
            _uiState.value = _uiState.value?.copy(trends = computed)
        }
    }

    private suspend fun buildTrends(
        period: StatsPeriod,
        anchor: Long,
        scope: AccountScope
    ): TrendsUiState {
        val ids = scope.resolve(db.accountDao().getAllSync())
        val earliest = trendsRepository.earliestDataEpoch(ids)
        val window = TrendsBuckets.visibleWindow(
            period, anchor, customFrom, customTo, earliest, System.currentTimeMillis()
        )
        val bucket = TrendsBuckets.bucketFor(period, window)
        val starts = TrendsBuckets.bucketStarts(bucket, window)
        if (starts.isEmpty() || ids.isEmpty()) {
            return TrendsUiState(bucket = bucket, hasAccounts = ids.isNotEmpty(), isLoading = false)
        }

        // The span starts at the first BUCKET, not at the window: a week bucket snaps back before
        // the window's own start whenever a quarter does not begin on a Monday, which is almost
        // always. Loading from the window would then leave the opening balance a few days late and
        // silently drop the movements in between.
        val inputs = trendsRepository.loadBalanceInputs(starts.first(), window.endExclusive, ids)
        return TrendsUiState(
            bucket = bucket,
            bucketStarts = starts,
            openingPaise = inputs.openingByAccount.values.sum(),
            balanceSeries = BalanceTimeline.build(
                bucketStarts = starts,
                windowEndExclusive = window.endExclusive,
                accountIds = ids,
                openingByAccount = inputs.openingByAccount,
                movements = inputs.movements,
                anchorsByAccount = inputs.anchorsByAccount
            ),
            hasAccounts = true,
            isLoading = false
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
