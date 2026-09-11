package com.varun.upitracker.ui.statistics

import android.content.Context
import com.varun.upitracker.database.entity.Account
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.BalanceSeriesInputs
import com.varun.upitracker.data.repository.TrendsRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.model.FlowTotal
import com.varun.upitracker.domain.statistics.Breakdown
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.DateRange
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.BalanceConfidence
import com.varun.upitracker.domain.statistics.BalanceTimeline
import com.varun.upitracker.domain.TransferDeltaInput
import com.varun.upitracker.domain.statistics.PanMath
import com.varun.upitracker.domain.statistics.TransferFlow
import com.varun.upitracker.domain.statistics.parseAccountScope
import com.varun.upitracker.domain.statistics.serialise
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.domain.statistics.StatsAggregator
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.domain.statistics.TrendBucket
import com.varun.upitracker.domain.statistics.TrendWindow
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
    /** What came in per bucket. Parallel to [bucketStarts], as [expenseSeries] is. */
    val incomeSeries: List<Long> = emptyList(),
    val expenseSeries: List<Long> = emptyList(),
    /**
     * The combined balance immediately before the window opens.
     *
     * The change is measured from here, not from the first plotted point: that one already carries
     * its own bucket's movement, so the window's first day would go uncounted.
     *
     * [changePaise] is then the same money [incomeSeries] and [expenseSeries] describe crossing the
     * edge of the scope -- **unless a reconciliation snapshot falls inside the window**, which
     * re-anchors the balance to a counted figure rather than a moved one. The two legitimately
     * disagree by exactly that correction.
     */
    val openingPaise: Long = 0L,
    /** False when the scope resolves to nothing, where a flat zero line would be a lie. */
    val hasAccounts: Boolean = true,
    /** The span on screen, which panning slides and the range label names. */
    val windowStart: Long = 0L,
    val windowEndExclusive: Long = 0L,
    /** False for the periods that already show their whole range. */
    val canPan: Boolean = false,
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
    val balanceCertainFromEpoch: Long? = null
) {
    val latestBalancePaise: Long get() = balanceSeries.lastOrNull() ?: 0L
    val changePaise: Long get() = latestBalancePaise - openingPaise

    /** Leading points of [balanceSeries] the app worked out rather than was told. */
    val speculativePointCount: Int
        get() = BalanceConfidence.speculativePointCount(
            bucketStarts, windowEndExclusive, balanceCertainFromEpoch
        )

    /** Where across the plot to mark the first reconciliation, or null when it says nothing. */
    val checkpointFraction: Float?
        get() = BalanceConfidence.checkpointFraction(
            bucketStarts, windowEndExclusive, balanceCertainFromEpoch
        )

    val isLatestSpeculative: Boolean
        get() = BalanceConfidence.isSpeculative(windowEndExclusive - 1, balanceCertainFromEpoch)
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
     * One bucket's flow, keyed by window.
     *
     * Bucket boundaries are deterministic, so a window slid back over ground already covered reuses
     * what was fetched rather than asking again -- which is what keeps a drag to a couple of new
     * queries per step. Cleared when the scope changes, since the totals are scoped.
     */
    private val bucketFlows = HashMap<String, FlowTotal>()

    /**
     * Where the panned window starts, or null while it sits on the period's own range.
     *
     * Null is not the same as "at the period's start": the period's window is the exact range the
     * pie covers, edge buckets clipped, while a panned one is always a whole number of buckets.
     * Resetting to null on any period change is what puts the two sections back in step.
     */
    private var panWindowStart: Long? = null

    /** Captured when a drag begins, so the window is always computed from where it stood. */
    private var panDragOrigin: Long? = null

    /**
     * The scope's account ids and oldest epoch, which four queries would otherwise re-answer on
     * every step of a drag. Neither changes while this screen is open -- nothing here reloads on
     * resume, so an account added elsewhere is already invisible to the rest of it.
     */
    private val resolvedScopes = HashMap<String, Pair<Set<String>, Long?>>()

    /** Read once. An account added elsewhere is already invisible to the rest of this screen. */
    private var loadedAccounts: List<Account>? = null

    /** Persisted, so a hand-picked set is still there next time rather than silently reset. */
    fun selectScope(next: AccountScope) {
        if (next == accountScope) return
        accountScope = next
        prefs.edit().putString(PREF_TRENDS_SCOPE, next.serialise()).apply()
        // The flow totals are scoped, so what is cached describes the old scope.
        bucketFlows.clear()
        loadTrends()
    }

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
        panWindowStart = null
        anchor = System.currentTimeMillis()
        load()
    }

    /** Opens a single day, used when a bar column is tapped. */
    fun selectDay(dayStartEpoch: Long) {
        period = StatsPeriod.DAILY
        panWindowStart = null
        anchor = dayStartEpoch
        load()
    }

    fun selectCustomRange(fromEpoch: Long, toEpoch: Long) {
        period = StatsPeriod.CUSTOM
        panWindowStart = null
        customFrom = fromEpoch
        customTo = toEpoch
        load()
    }

    /** The finger has taken the horizontal axis. */
    fun beginPan() {
        panDragOrigin = _uiState.value?.trends?.windowStart ?: return
    }

    /**
     * @param bucketDelta buckets moved since [beginPan], not since the last call.
     *
     * Recomputed from the drag's origin every time, so running into the end of the data and coming
     * back moves again at once rather than first unwinding the steps the clamp refused.
     */
    fun panBy(bucketDelta: Int) {
        val origin = panDragOrigin ?: return
        val trends = _uiState.value?.trends ?: return
        if (!trends.canPan || trends.bucketStarts.isEmpty()) return

        val next = PanMath.clampStart(
            proposedStart = TrendsBuckets.addBuckets(trends.bucket, origin, bucketDelta),
            bucket = trends.bucket,
            visibleBuckets = trends.bucketStarts.size,
            earliestEpoch = earliestForScope() ?: origin,
            nowEpoch = System.currentTimeMillis()
        )
        if (next == trends.windowStart) return
        panWindowStart = next
        loadTrends()
    }

    fun endPan() {
        panDragOrigin = null
    }

    /** [delta] is -1 for the previous period, +1 for the next. Guarded against the future. */
    fun step(delta: Int) {
        if (!period.isShiftable) return
        if (delta > 0 && !StatisticsPeriods.canShiftForward(period, anchor, System.currentTimeMillis())) return
        panWindowStart = null
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
        val key = "$period/$anchor/$customFrom/$customTo/$scope/$panWindowStart"
        if (key == loadedTrendsKey) return

        trendsJob?.cancel()
        loadedTrendsKey = null
        // Marked loading rather than emptied. A drag reloads on every bucket it crosses, and
        // clearing the series each time would strobe the charts between drawn and blank.
        _uiState.value = _uiState.value?.let { it.copy(trends = it.trends.copy(isLoading = true)) }

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
        val accounts = accounts()
        val (ids, earliest) = resolveScope(scope)
        val baseWindow = TrendsBuckets.visibleWindow(
            period, anchor, customFrom, customTo, earliest, System.currentTimeMillis()
        )
        val bucket = TrendsBuckets.bucketFor(period, baseWindow)
        val baseStarts = TrendsBuckets.bucketStarts(bucket, baseWindow)
        if (baseStarts.isEmpty() || ids.isEmpty()) {
            return TrendsUiState(
                bucket = bucket,
                hasAccounts = ids.isNotEmpty(),
                scope = scope,
                accounts = accounts,
                isLoading = false
            )
        }

        // The period fixes how much is on screen; panning only moves where that sits. A panned
        // window is whole buckets, while the period's own is the exact range the pie covers.
        val panned = panWindowStart
        val window = if (panned == null) {
            baseWindow
        } else {
            PanMath.windowFrom(panned, bucket, baseStarts.size)
        }
        val starts = TrendsBuckets.bucketStarts(bucket, window)
        val canPan = period.isShiftable && PanMath.canPan(
            startEpoch = window.startInclusive,
            bucket = bucket,
            visibleBuckets = starts.size,
            earliestEpoch = earliest ?: window.startInclusive,
            nowEpoch = System.currentTimeMillis()
        )

        // The span starts at the first BUCKET, not at the window: a week bucket snaps back before
        // the window's own start whenever a quarter does not begin on a Monday, which is almost
        // always. Loading from the window would then leave the opening balance a few days late and
        // silently drop the movements in between.
        val inputs = trendsRepository.loadBalanceInputs(starts.first(), window.endExclusive, ids)

        // Clipped to the window: the first and last buckets otherwise reach outside the period, so
        // a quarter's opening week would count days from the month before it.
        val windows = starts.map { TrendsBuckets.bucketWindow(bucket, it).within(window) }
        val flows = windows.map { bucketWindow ->
            val fromTransactions = bucketFlow(bucketWindow, ids)
            // Transfers are bucketed in memory from the span already loaded for the timeline,
            // rather than queried per bucket: telling an internal move from one that crossed the
            // edge of the scope needs both legs at once, which a per-account query cannot see.
            val fromTransfers = TransferFlow.sum(
                inputs.transfers
                    .filter {
                        it.dateEpoch >= bucketWindow.startInclusive &&
                            it.dateEpoch < bucketWindow.endExclusive
                    }
                    .map {
                        TransferDeltaInput(
                            fromAccountId = it.fromAccountId,
                            toAccountId = it.toAccountId,
                            amountFromPaise = it.amountFromPaise,
                            amountToPaise = it.amountToPaise
                        )
                    },
                ids
            )
            FlowTotal(
                inPaise = fromTransactions.inPaise + fromTransfers.inPaise,
                outPaise = fromTransactions.outPaise + fromTransfers.outPaise
            )
        }
        return TrendsUiState(
            bucket = bucket,
            bucketStarts = starts,
            incomeSeries = flows.map { it.inPaise },
            expenseSeries = flows.map { it.outPaise },
            openingPaise = openingAtWindowStart(window, starts, ids, inputs),
            windowStart = window.startInclusive,
            windowEndExclusive = window.endExclusive,
            canPan = canPan,
            scope = scope,
            accounts = accounts,
            balanceSeries = BalanceTimeline.build(
                bucketStarts = starts,
                windowEndExclusive = window.endExclusive,
                accountIds = ids,
                openingByAccount = inputs.openingByAccount,
                movements = inputs.movements,
                anchorsByAccount = inputs.anchorsByAccount
            ),
            hasAccounts = true,
            isLoading = false,
            balanceCertainFromEpoch = repository.balanceCertainFrom(ids)
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

    /**
     * The combined balance immediately before the window the charts show.
     *
     * Not simply the loaded span's opening: the span begins at the first whole bucket, which for a
     * quarter is the Monday before it. Measuring the period's change from there would count up to
     * six days the charts never draw, and would put the balance figure out of step with the
     * in-and-out totals beside it, which start at the window.
     *
     * Walked from data already in memory rather than asked of the database again, so this costs
     * nothing on a drag.
     */
    private fun openingAtWindowStart(
        window: TrendWindow,
        starts: List<Long>,
        ids: Set<String>,
        inputs: BalanceSeriesInputs
    ): Long {
        val spanStart = starts.first()
        if (spanStart >= window.startInclusive) return inputs.openingByAccount.values.sum()
        return BalanceTimeline.build(
            bucketStarts = listOf(spanStart),
            windowEndExclusive = window.startInclusive,
            accountIds = ids,
            openingByAccount = inputs.openingByAccount,
            movements = inputs.movements,
            anchorsByAccount = inputs.anchorsByAccount
        ).first()
    }

    /**
     * The scope's ids and oldest epoch, answered once per scope.
     *
     * Four queries otherwise, on every step of a drag.
     */
    private suspend fun accounts(): List<Account> =
        loadedAccounts ?: db.accountDao().getAllSync().also { loadedAccounts = it }

    private suspend fun resolveScope(scope: AccountScope): Pair<Set<String>, Long?> {
        resolvedScopes["$scope"]?.let { return it }
        val ids = scope.resolve(accounts())
        val resolved = ids to trendsRepository.earliestDataEpoch(ids)
        resolvedScopes["$scope"] = resolved
        return resolved
    }

    /** Only meaningful once a scope has been resolved, which is always true by the time it is read. */
    private fun earliestForScope(): Long? = resolvedScopes["$accountScope"]?.second

    /**
     * One bucket's money in and out.
     *
     * Read from the transaction rows, **not** from the category totals the pie is built from. Those
     * count only what has been reviewed: category splits and shares are written by the entry screen
     * alone, so an SMS-imported credit carries neither and read as no income at all. That is not a
     * quirk of one database -- it is every unreviewed credit in any of them.
     *
     * Transactions only. Transfers are added by the caller, which has both legs of each one and so
     * can tell a move inside the scope from one that crossed its edge.
     */
    private suspend fun bucketFlow(window: TrendWindow, accountIds: Set<String>): FlowTotal {
        val key = "${window.startInclusive}/${window.endExclusive}"
        bucketFlows[key]?.let { return it }
        val range = window.asDateRange()
        val flow = db.transactionDao()
            .getFlowBetween(accountIds.toList(), range.fromExclusive, range.toInclusive)
        bucketFlows[key] = flow
        return flow
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
