package com.varun.upitracker.domain.statistics

import java.util.Calendar

/**
 * The span a transaction list is showing: a whole month, all of history, or a range the user picked.
 *
 * Half-open `[startEpoch, endExclusiveEpoch)`, which is the convention the two range DAOs take and
 * **the opposite end from [DateRange]'s** `(fromExclusive, toInclusive]`. The two exist because the
 * queries genuinely differ: a list asks for rows in a span, while the aggregates are built around
 * snapshot-relative arithmetic where the lower bound has to be exclusive. Keeping them separate
 * types, each naming its own ends, is what stops a one-millisecond slip silently dropping a
 * transaction stamped exactly at midnight -- and statement import stamps every row it writes at
 * exactly midnight, so that is the ordinary case rather than a corner one.
 *
 * Pure and Android-free: this is what three screens' month controls agree on, and the boundary rules
 * are worth asserting without a device.
 */
data class ListWindow(
    val startEpoch: Long,
    val endExclusiveEpoch: Long,
    /** True when the range came from the date pickers rather than being a whole month. */
    val isCustom: Boolean = false,
    /** True when this is every transaction ever recorded. */
    val isAllTime: Boolean = false
) {

    operator fun contains(epoch: Long): Boolean =
        epoch >= startEpoch && epoch < endExclusiveEpoch

    /** The last millisecond inside the window, for labelling the day the user actually picked. */
    val lastMillis: Long get() = endExclusiveEpoch - 1

    /**
     * A window [delta] whole months away, anchored on the month in view.
     *
     * All time has no month to anchor on, so it steps from [nowEpoch]'s month instead of refusing.
     */
    fun shiftedByMonths(delta: Int, nowEpoch: Long): ListWindow {
        val anchor = if (isAllTime) nowEpoch else startEpoch
        return monthOf(StatisticsPeriods.addMonths(StatisticsPeriods.startOfMonth(anchor), delta))
    }

    companion object {

        fun monthOf(epoch: Long): ListWindow {
            val start = StatisticsPeriods.startOfMonth(epoch)
            return ListWindow(start, StatisticsPeriods.addMonths(start, 1))
        }

        fun currentMonth(nowEpoch: Long = System.currentTimeMillis()): ListWindow = monthOf(nowEpoch)

        /**
         * Every transaction and transfer ever recorded.
         *
         * Starts at the Unix epoch rather than [Long.MIN_VALUE] deliberately: the transactions screen
         * derives an opening balance from `startEpoch - 1`, and subtracting from [Long.MIN_VALUE]
         * would overflow. Nothing real predates 1970.
         */
        fun allTime(): ListWindow = ListWindow(0L, Long.MAX_VALUE, isAllTime = true)

        /** Both ends are days the user picked, and both are inclusive of their whole day. */
        fun custom(fromEpoch: Long, toEpoch: Long): ListWindow = ListWindow(
            startEpoch = StatisticsPeriods.startOfDay(fromEpoch),
            endExclusiveEpoch = StatisticsPeriods.addDays(StatisticsPeriods.startOfDay(toEpoch), 1),
            isCustom = true
        )

        /**
         * The same span as a statistics [range], which names its ends the other way round.
         *
         * A range that is exactly one calendar month comes back as that month rather than a custom
         * range, so the list's control reads "March 2026" and steps by months as usual.
         */
        fun of(range: DateRange): ListWindow {
            if (range.fromExclusive == Long.MIN_VALUE) return allTime()
            val start = range.fromExclusive + 1
            val endExclusive = range.toInclusive + 1
            val month = monthOf(start)
            if (month.startEpoch == start && month.endExclusiveEpoch == endExclusive) return month
            return ListWindow(start, endExclusive, isCustom = true)
        }

        /** A month and year straight from the picker wheels. */
        fun month(year: Int, monthIndex: Int): ListWindow = monthOf(
            Calendar.getInstance().apply {
                set(year, monthIndex, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        )
    }
}
