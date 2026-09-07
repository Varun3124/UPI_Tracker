package com.varun.upitracker.domain.statistics

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsPeriodsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayOfWeek(epoch: Long): Int =
        Calendar.getInstance().apply { timeInMillis = epoch }.get(Calendar.DAY_OF_WEEK)

    // --- boundaries -------------------------------------------------------

    /**
     * The whole reason DateRange names its ends: the window is exclusive at the start, so a
     * transaction stamped exactly at midnight has to fall in its own day and not the one before.
     * Statement import snaps rows to midnight, so this is the realistic case, not a corner one.
     */
    @Test
    fun midnightBelongsToItsOwnDayAndNotThePrevious() {
        val midnight = at(2026, Calendar.MARCH, 10)
        val today = StatisticsPeriods.rangeFor(StatsPeriod.DAILY, midnight)
        val yesterday = StatisticsPeriods.rangeFor(StatsPeriod.DAILY, midnight - 1)

        assertTrue(midnight > today.fromExclusive && midnight <= today.toInclusive)
        assertFalse(midnight > yesterday.fromExclusive && midnight <= yesterday.toInclusive)
    }

    @Test
    fun consecutiveDaysAbutWithoutOverlapOrGap() {
        val day = StatisticsPeriods.rangeFor(StatsPeriod.DAILY, at(2026, Calendar.MARCH, 10, 13))
        val next = StatisticsPeriods.rangeFor(StatsPeriod.DAILY, at(2026, Calendar.MARCH, 11, 13))
        assertEquals(day.toInclusive, next.fromExclusive)
    }

    @Test
    fun monthRangeCoversTheWholeMonth() {
        val range = StatisticsPeriods.rangeFor(StatsPeriod.MONTHLY, at(2026, Calendar.FEBRUARY, 14))
        assertEquals(at(2026, Calendar.FEBRUARY, 1) - 1, range.fromExclusive)
        assertEquals(at(2026, Calendar.MARCH, 1) - 1, range.toInclusive)
    }

    // --- weeks start Monday ----------------------------------------------

    @Test
    fun weekStartsOnMonday() {
        assertEquals(Calendar.MONDAY, dayOfWeek(StatisticsPeriods.startOfWeek(at(2026, Calendar.MARCH, 12))))
    }

    @Test
    fun sundayBelongsToTheWeekThatBeganSixDaysEarlier() {
        // 15 March 2026 is a Sunday; its week starts Monday the 9th.
        val sunday = at(2026, Calendar.MARCH, 15, 20)
        assertEquals(Calendar.SUNDAY, dayOfWeek(sunday))
        assertEquals(at(2026, Calendar.MARCH, 9), StatisticsPeriods.startOfWeek(sunday))
    }

    @Test
    fun mondayIsItsOwnWeekStart() {
        val monday = at(2026, Calendar.MARCH, 9, 9)
        assertEquals(at(2026, Calendar.MARCH, 9), StatisticsPeriods.startOfWeek(monday))
    }

    @Test
    fun weekHasSevenDistinctDaysMondayFirst() {
        val days = StatisticsPeriods.daysOfWeek(at(2026, Calendar.MARCH, 12))
        assertEquals(7, days.size)
        assertEquals(7, days.toSet().size)
        assertEquals(Calendar.MONDAY, dayOfWeek(days.first()))
        assertEquals(Calendar.SUNDAY, dayOfWeek(days.last()))
    }

    /** The premise of the stacked bar: the seven day windows tile the week exactly. */
    @Test
    fun dayWindowsTileTheWeekExactly() {
        val anchor = at(2026, Calendar.MARCH, 12)
        val week = StatisticsPeriods.rangeFor(StatsPeriod.WEEKLY, anchor)
        val days = StatisticsPeriods.daysOfWeek(anchor).map(StatisticsPeriods::dayRange)

        assertEquals(week.fromExclusive, days.first().fromExclusive)
        assertEquals(week.toInclusive, days.last().toInclusive)
        days.zipWithNext { a, b -> assertEquals(a.toInclusive, b.fromExclusive) }
    }

    // --- quarters ---------------------------------------------------------

    @Test
    fun quartersAlignToCalendarQuarters() {
        val starts = listOf(
            Calendar.JANUARY to Calendar.JANUARY, Calendar.FEBRUARY to Calendar.JANUARY,
            Calendar.MARCH to Calendar.JANUARY, Calendar.APRIL to Calendar.APRIL,
            Calendar.JUNE to Calendar.APRIL, Calendar.JULY to Calendar.JULY,
            Calendar.SEPTEMBER to Calendar.JULY, Calendar.OCTOBER to Calendar.OCTOBER,
            Calendar.DECEMBER to Calendar.OCTOBER
        )
        starts.forEach { (month, expected) ->
            assertEquals(
                "month $month",
                at(2026, expected, 1),
                StatisticsPeriods.startOfQuarter(at(2026, month, 15))
            )
        }
    }

    /** Setting the month while the day is the 31st would otherwise roll into the next month. */
    @Test
    fun quarterStartFromTheThirtyFirstDoesNotRollOver() {
        assertEquals(
            at(2026, Calendar.JULY, 1),
            StatisticsPeriods.startOfQuarter(at(2026, Calendar.AUGUST, 31, 23))
        )
    }

    @Test
    fun shiftingPastQ4RollsTheYear() {
        val q4 = at(2026, Calendar.NOVEMBER, 5)
        val next = StatisticsPeriods.shift(StatsPeriod.QUARTERLY, q4, 1)
        assertEquals(at(2027, Calendar.JANUARY, 1), next)
    }

    // --- shifting ---------------------------------------------------------

    @Test
    fun monthShiftCrossesTheYearBoundary() {
        assertEquals(
            at(2027, Calendar.JANUARY, 1),
            StatisticsPeriods.shift(StatsPeriod.MONTHLY, at(2026, Calendar.DECEMBER, 20), 1)
        )
        assertEquals(
            at(2025, Calendar.DECEMBER, 1),
            StatisticsPeriods.shift(StatsPeriod.MONTHLY, at(2026, Calendar.JANUARY, 20), -1)
        )
    }

    /** Anchors are normalised to the period start, so a 31st never leaks into a short month. */
    @Test
    fun monthShiftFromTheThirtyFirstLandsOnAShortMonthsFirst() {
        assertEquals(
            at(2026, Calendar.FEBRUARY, 1),
            StatisticsPeriods.shift(StatsPeriod.MONTHLY, at(2026, Calendar.JANUARY, 31), 1)
        )
    }

    @Test
    fun weekShiftMovesSevenDays() {
        val anchor = at(2026, Calendar.MARCH, 12)
        assertEquals(
            at(2026, Calendar.MARCH, 16),
            StatisticsPeriods.shift(StatsPeriod.WEEKLY, anchor, 1)
        )
    }

    @Test
    fun unshiftablePeriodsDoNotMove() {
        val anchor = at(2026, Calendar.MARCH, 12)
        assertEquals(anchor, StatisticsPeriods.shift(StatsPeriod.ALL_TIME, anchor, 1))
        assertEquals(anchor, StatisticsPeriods.shift(StatsPeriod.CUSTOM, anchor, -1))
    }

    // --- forward guard ----------------------------------------------------

    @Test
    fun cannotShiftForwardOutOfThePeriodContainingNow() {
        val now = at(2026, Calendar.MARCH, 12, 15)
        listOf(StatsPeriod.DAILY, StatsPeriod.WEEKLY, StatsPeriod.MONTHLY, StatsPeriod.QUARTERLY)
            .forEach { period ->
                assertFalse(period.name, StatisticsPeriods.canShiftForward(period, now, now))
            }
    }

    @Test
    fun canShiftForwardFromAnEarlierPeriod() {
        val now = at(2026, Calendar.MARCH, 12, 15)
        val lastMonth = at(2026, Calendar.FEBRUARY, 12)
        assertTrue(StatisticsPeriods.canShiftForward(StatsPeriod.MONTHLY, lastMonth, now))
        assertTrue(StatisticsPeriods.canShiftForward(StatsPeriod.DAILY, now - StatisticsPeriods.DAY_MILLIS, now))
    }

    /** The boundary itself: yesterday can advance into today, because today has started. */
    @Test
    fun yesterdayCanAdvanceIntoToday() {
        val now = at(2026, Calendar.MARCH, 12, 0, 1)
        assertTrue(StatisticsPeriods.canShiftForward(StatsPeriod.DAILY, at(2026, Calendar.MARCH, 11), now))
    }

    @Test
    fun allTimeAndCustomNeverShiftForward() {
        val now = at(2026, Calendar.MARCH, 12)
        assertFalse(StatisticsPeriods.canShiftForward(StatsPeriod.ALL_TIME, at(2020, Calendar.JANUARY, 1), now))
        assertFalse(StatisticsPeriods.canShiftForward(StatsPeriod.CUSTOM, at(2020, Calendar.JANUARY, 1), now))
    }

    // --- all time / custom ------------------------------------------------

    @Test
    fun allTimeSpansEverything() {
        val range = StatisticsPeriods.rangeFor(StatsPeriod.ALL_TIME, at(2026, Calendar.MARCH, 12))
        assertEquals(Long.MIN_VALUE, range.fromExclusive)
        assertEquals(Long.MAX_VALUE, range.toInclusive)
    }

    /** Both picked days are inclusive: a one-day custom range must cover that whole day. */
    @Test
    fun customRangeIncludesBothPickedDaysWhole() {
        val day = at(2026, Calendar.MARCH, 10)
        val range = StatisticsPeriods.rangeFor(StatsPeriod.CUSTOM, 0L, day + 3600_000, day + 7200_000)
        assertEquals(StatisticsPeriods.rangeFor(StatsPeriod.DAILY, day), range)
    }
}
