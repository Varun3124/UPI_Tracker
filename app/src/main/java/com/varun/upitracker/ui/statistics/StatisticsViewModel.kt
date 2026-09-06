package com.varun.upitracker.ui.statistics

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.domain.statistics.Breakdown
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.DateRange
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsAggregator
import com.varun.upitracker.domain.statistics.StatsPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatisticsUiState(
    val period: StatsPeriod = StatsPeriod.WEEKLY,
    val anchorEpoch: Long = 0L,
    val range: DateRange = DateRange(0L, 0L),
    val canGoForward: Boolean = false,
    val breakdown: Breakdown = Breakdown(emptyList()),
    /** Non-null while drilled into one category; the screen then shows its counterparties. */
    val drilledCategory: CategorySlice? = null,
    val payeeSlices: List<CategorySlice> = emptyList(),
    val isLoading: Boolean = true
)

class StatisticsViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context.applicationContext)
    private val repository = AccountRepository(db)

    private val _uiState = MutableLiveData(StatisticsUiState())
    val uiState: LiveData<StatisticsUiState> = _uiState

    private var period = StatsPeriod.WEEKLY
    private var anchor = System.currentTimeMillis()
    private var customFrom = 0L
    private var customTo = 0L
    private var drilled: CategorySlice? = null
    private var loadJob: Job? = null

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
                period = period,
                anchorEpoch = anchor,
                range = range,
                canGoForward = StatisticsPeriods.canShiftForward(period, anchor, System.currentTimeMillis()),
                breakdown = breakdown,
                // Re-priced against this window, so the header total follows a period change even
                // when the category is absent from the new one.
                drilledCategory = drilled?.copy(paise = payees.sumOf { it.paise }),
                payeeSlices = payees,
                isLoading = false
            )
        }
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
