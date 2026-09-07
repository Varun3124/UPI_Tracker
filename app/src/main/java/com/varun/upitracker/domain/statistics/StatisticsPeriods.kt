package com.varun.upitracker.domain.statistics

import java.util.Calendar

enum class StatsPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ALL_TIME,
    CUSTOM;

    /** All time and a hand-picked range have no neighbouring period to move to. */
    val isShiftable: Boolean get() = this != ALL_TIME && this != CUSTOM
}

/**
 * A window in the shape the DAO expects: `(fromExclusive, toInclusive]`.
 *
 * Exclusive at the *start*, which is the opposite end from the half-open `[from, to)` the
 * transaction list queries use. Keeping the field names honest is the whole point of this type --
 * a one-millisecond slip silently drops or double-counts a transaction dated exactly at midnight.
 */
data class DateRange(val fromExclusive: Long, val toInclusive: Long)

/**
 * Period arithmetic for the dashboard figures and the statistics screen.
 *
 * The first shared date utility in the app: `startOfDay` and `startOfMonth` were previously
 * duplicated privately in DashboardViewModel, ScreenViewModels, AllTransactionsActivity and
 * XlsStatementReader. Pure and Android-free so the boundary rules can actually be tested.
 *
 * Formatting deliberately lives with the screens instead of here, so this stays locale-independent
 * and its tests stay deterministic.
 */
object StatisticsPeriods {

    const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Weeks start Monday, set explicitly -- `firstDayOfWeek` resolves to Sunday on an en-IN device. */
    private const val WEEK_START = Calendar.MONDAY

    fun startOfDay(epoch: Long): Long = calendarAt(epoch).also(::zeroTime).timeInMillis

    /** Only the trends X axis buckets this finely, but the rule belongs with the other boundaries. */
    fun startOfHour(epoch: Long): Long = calendarAt(epoch).apply {
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfWeek(epoch: Long): Long {
        val cal = calendarAt(epoch).also(::zeroTime)
        val daysFromWeekStart = (cal.get(Calendar.DAY_OF_WEEK) - WEEK_START + 7) % 7
        cal.add(Calendar.DAY_OF_MONTH, -daysFromWeekStart)
        return cal.timeInMillis
    }

    fun startOfMonth(epoch: Long): Long {
        val cal = calendarAt(epoch).also(::zeroTime)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun startOfQuarter(epoch: Long): Long {
        val cal = calendarAt(epoch).also(::zeroTime)
        // Day first: setting MONTH while the day is the 31st would roll a short month over.
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val month = cal.get(Calendar.MONTH)
        cal.set(Calendar.MONTH, month - (month % 3))
        return cal.timeInMillis
    }

    /** First millisecond of the period containing [epoch]. Meaningless for ALL_TIME and CUSTOM. */
    fun startOf(period: StatsPeriod, epoch: Long): Long = when (period) {
        StatsPeriod.DAILY -> startOfDay(epoch)
        StatsPeriod.WEEKLY -> startOfWeek(epoch)
        StatsPeriod.MONTHLY -> startOfMonth(epoch)
        StatsPeriod.QUARTERLY -> startOfQuarter(epoch)
        StatsPeriod.ALL_TIME, StatsPeriod.CUSTOM -> epoch
    }

    /**
     * The window covering [anchorEpoch]'s period.
     *
     * [customFromEpoch] and [customToEpoch] are only read for [StatsPeriod.CUSTOM] and are both
     * treated as whole days the user picked, inclusive at each end.
     */
    fun rangeFor(
        period: StatsPeriod,
        anchorEpoch: Long,
        customFromEpoch: Long = 0L,
        customToEpoch: Long = 0L
    ): DateRange = when (period) {
        StatsPeriod.DAILY -> dayRange(startOfDay(anchorEpoch))
        StatsPeriod.WEEKLY -> {
            val start = startOfWeek(anchorEpoch)
            DateRange(start - 1, addDays(start, 7) - 1)
        }
        StatsPeriod.MONTHLY -> {
            val start = startOfMonth(anchorEpoch)
            DateRange(start - 1, addMonths(start, 1) - 1)
        }
        StatsPeriod.QUARTERLY -> {
            val start = startOfQuarter(anchorEpoch)
            DateRange(start - 1, addMonths(start, 3) - 1)
        }
        StatsPeriod.ALL_TIME -> DateRange(Long.MIN_VALUE, Long.MAX_VALUE)
        StatsPeriod.CUSTOM -> DateRange(
            startOfDay(customFromEpoch) - 1,
            addDays(startOfDay(customToEpoch), 1) - 1
        )
    }

    /**
     * One whole day, used per-column by the weekly stacked bar.
     *
     * Field arithmetic rather than a fixed 86_400_000ms stride: a DST day is 23 or 25 hours long,
     * and a fixed stride would slide every later bucket off midnight -- which would break the
     * tiling the stacked bar depends on to sum to the pie.
     */
    fun dayRange(dayStartEpoch: Long): DateRange =
        DateRange(dayStartEpoch - 1, addDays(dayStartEpoch, 1) - 1)

    /** The seven day-starts of [anchorEpoch]'s week, Monday first. */
    fun daysOfWeek(anchorEpoch: Long): List<Long> {
        val start = startOfWeek(anchorEpoch)
        return (0 until 7).map { offset ->
            calendarAt(start).apply { add(Calendar.DAY_OF_MONTH, offset) }.timeInMillis
        }
    }

    /** A new anchor [delta] periods away. Returns [anchorEpoch] unchanged for unshiftable periods. */
    fun shift(period: StatsPeriod, anchorEpoch: Long, delta: Int): Long = when (period) {
        StatsPeriod.DAILY -> addDays(startOfDay(anchorEpoch), delta)
        StatsPeriod.WEEKLY -> addDays(startOfWeek(anchorEpoch), delta * 7)
        StatsPeriod.MONTHLY -> addMonths(startOfMonth(anchorEpoch), delta)
        StatsPeriod.QUARTERLY -> addMonths(startOfQuarter(anchorEpoch), delta * 3)
        StatsPeriod.ALL_TIME, StatsPeriod.CUSTOM -> anchorEpoch
    }

    /**
     * Whether moving forward would land on a period that has not started yet.
     *
     * Nothing else in the app guards this -- `AllTransactionsViewModel.shiftMonth` walks into the
     * future indefinitely -- so this is new behaviour rather than a borrowed pattern.
     */
    fun canShiftForward(period: StatsPeriod, anchorEpoch: Long, now: Long): Boolean {
        if (!period.isShiftable) return false
        return startOf(period, shift(period, anchorEpoch, 1)) <= now
    }

    /**
     * Field arithmetic rather than a fixed 86_400_000ms stride: a DST day is 23 or 25 hours long,
     * and a stride would slide every later boundary off midnight.
     *
     * Public because [TrendsBuckets] tiles the same calendar and duplicating this is how the two
     * would drift apart.
     */
    fun addDays(epoch: Long, days: Int): Long =
        calendarAt(epoch).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    // Calendar.add, not millisecond arithmetic: month and quarter lengths vary, and adding a month
    // to the 31st has to land on the last day of a short month rather than overflowing into the next.
    fun addMonths(epoch: Long, months: Int): Long =
        calendarAt(epoch).apply { add(Calendar.MONTH, months) }.timeInMillis

    /** Field arithmetic again: the DST hour is repeated or skipped, and a stride would miscount it. */
    fun addHours(epoch: Long, hours: Int): Long =
        calendarAt(epoch).apply { add(Calendar.HOUR_OF_DAY, hours) }.timeInMillis

    private fun calendarAt(epoch: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = epoch }

    private fun zeroTime(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
}
