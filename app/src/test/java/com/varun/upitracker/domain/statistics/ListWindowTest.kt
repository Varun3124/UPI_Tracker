package com.varun.upitracker.domain.statistics

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListWindowTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayOfMonth(epoch: Long): Int =
        Calendar.getInstance().apply { timeInMillis = epoch }.get(Calendar.DAY_OF_MONTH)

    /**
     * The whole reason the ends are named: a window is half-open, so a row stamped exactly at
     * midnight on the 1st is inside its own month and a row at the next month's midnight is not.
     * Statement import stamps every row it writes at exactly midnight, so this is the ordinary case.
     */
    @Test
    fun midnightAtEitherEndBelongsToExactlyOneMonth() {
        val march = ListWindow.monthOf(at(2026, Calendar.MARCH, 15))
        val firstOfMarch = at(2026, Calendar.MARCH, 1)
        val firstOfApril = at(2026, Calendar.APRIL, 1)

        assertTrue(firstOfMarch in march)
        assertFalse(firstOfApril in march)
        assertTrue(firstOfApril in ListWindow.monthOf(firstOfApril))
    }

    @Test
    fun consecutiveMonthsAbutWithoutOverlapOrGap() {
        val march = ListWindow.monthOf(at(2026, Calendar.MARCH, 10))
        val april = ListWindow.monthOf(at(2026, Calendar.APRIL, 10))

        assertEquals(march.endExclusiveEpoch, april.startEpoch)
    }

    @Test
    fun aMonthCoversItsWholeLastDay() {
        val february = ListWindow.monthOf(at(2026, Calendar.FEBRUARY, 3))

        assertTrue(at(2026, Calendar.FEBRUARY, 28, 23) in february)
        assertEquals(28, dayOfMonth(february.lastMillis))
    }

    /** Both ends are days the user picked, so the last one has to be covered in full. */
    @Test
    fun aCustomRangeIncludesAllOfTheDayItEndsOn() {
        val window = ListWindow.custom(at(2026, Calendar.MARCH, 3, 14), at(2026, Calendar.MARCH, 5, 9))

        assertTrue(window.isCustom)
        assertTrue(at(2026, Calendar.MARCH, 3) in window)
        assertTrue(at(2026, Calendar.MARCH, 5, 23) in window)
        assertFalse(at(2026, Calendar.MARCH, 6) in window)
    }

    @Test
    fun aRangeOfOneDayIsThatDay() {
        val day = at(2026, Calendar.MARCH, 3)
        val window = ListWindow.custom(day, day)

        assertTrue(day in window)
        assertTrue(at(2026, Calendar.MARCH, 3, 23) in window)
        assertFalse(at(2026, Calendar.MARCH, 4) in window)
    }

    /**
     * All time starts at the Unix epoch rather than Long.MIN_VALUE: the transactions screen derives
     * an opening balance from `startEpoch - 1`, which would overflow.
     */
    @Test
    fun allTimeCanHaveOneSubtractedFromItsStart() {
        val window = ListWindow.allTime()

        assertTrue(window.isAllTime)
        assertEquals(0L, window.startEpoch)
        assertTrue(window.startEpoch - 1 > Long.MIN_VALUE)
        assertTrue(at(2026, Calendar.MARCH, 3) in window)
    }

    @Test
    fun steppingMovesWholeMonthsAndLandsOnTheFirst() {
        val march = ListWindow.monthOf(at(2026, Calendar.MARCH, 31))
        val april = march.shiftedByMonths(1, at(2026, Calendar.MARCH, 31))

        assertEquals(1, dayOfMonth(april.startEpoch))
        assertEquals(ListWindow.monthOf(at(2026, Calendar.APRIL, 10)), april)
    }

    /** Stepping off the 31st must not roll a short month over into the next one. */
    @Test
    fun steppingBackFromAThirtyFirstLandsOnTheShortMonth() {
        val march = ListWindow.monthOf(at(2026, Calendar.MARCH, 31))
        val february = march.shiftedByMonths(-1, at(2026, Calendar.MARCH, 31))

        assertEquals(ListWindow.monthOf(at(2026, Calendar.FEBRUARY, 10)), february)
        assertEquals(28, dayOfMonth(february.lastMillis))
    }

    /** All time has no month to anchor on, so a step starts from today's rather than refusing. */
    @Test
    fun steppingFromAllTimeAnchorsOnToday() {
        val now = at(2026, Calendar.MARCH, 15)
        val stepped = ListWindow.allTime().shiftedByMonths(-1, now)

        assertEquals(ListWindow.monthOf(at(2026, Calendar.FEBRUARY, 10)), stepped)
        assertFalse(stepped.isAllTime)
    }

    /** A statistics month arrives as that month, so the list steps by months from it as usual. */
    @Test
    fun aStatisticsMonthBecomesThatMonth() {
        val range = StatisticsPeriods.rangeFor(StatsPeriod.MONTHLY, at(2026, Calendar.MARCH, 15))

        assertEquals(ListWindow.monthOf(at(2026, Calendar.MARCH, 10)), ListWindow.of(range))
    }

    /** The two types name opposite ends open; the same instants have to land inside either way. */
    @Test
    fun aStatisticsWeekCoversExactlyTheSameInstants() {
        val range = StatisticsPeriods.rangeFor(StatsPeriod.WEEKLY, at(2026, Calendar.MARCH, 11))
        val window = ListWindow.of(range)

        assertTrue(window.isCustom)
        assertTrue(range.fromExclusive + 1 in window)
        assertTrue(range.toInclusive in window)
        assertFalse(range.fromExclusive in window)
        assertFalse(range.toInclusive + 1 in window)
    }

    /** All time on Statistics starts at Long.MIN_VALUE, which a list window must not inherit. */
    @Test
    fun statisticsAllTimeBecomesTheListsAllTime() {
        val range = StatisticsPeriods.rangeFor(StatsPeriod.ALL_TIME, at(2026, Calendar.MARCH, 11))

        assertEquals(ListWindow.allTime(), ListWindow.of(range))
    }

    /** A stepped window is a plain month again, not a custom range. */
    @Test
    fun steppingOffACustomRangeGivesAWholeMonth() {
        val custom = ListWindow.custom(at(2026, Calendar.MARCH, 3), at(2026, Calendar.MARCH, 5))
        val stepped = custom.shiftedByMonths(1, at(2026, Calendar.MARCH, 3))

        assertFalse(stepped.isCustom)
        assertEquals(ListWindow.monthOf(at(2026, Calendar.APRIL, 10)), stepped)
    }
}
