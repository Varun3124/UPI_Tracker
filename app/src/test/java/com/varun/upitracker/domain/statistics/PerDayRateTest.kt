package com.varun.upitracker.domain.statistics

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerDayRateTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun monthOf(epoch: Long): DateRange =
        StatisticsPeriods.rangeFor(StatsPeriod.MONTHLY, epoch)

    // --- days elapsed -----------------------------------------------------

    /** The reason this exists: on the 3rd a month's figure covers three days, not thirty-one. */
    @Test
    fun aMonthInProgressCountsOnlyTheDaysSoFar() {
        val now = at(2026, Calendar.MARCH, 3, 14)
        val month = monthOf(now)

        assertEquals(3, PerDayRate.daysElapsed(month.fromExclusive + 1, month.toInclusive, now))
    }

    @Test
    fun theFirstDayOfAPeriodCountsAsOne() {
        val now = at(2026, Calendar.MARCH, 1, 9)
        val month = monthOf(now)

        assertEquals(1, PerDayRate.daysElapsed(month.fromExclusive + 1, month.toInclusive, now))
    }

    /** A period already over is divided by its whole length, not by days since it began. */
    @Test
    fun aPastMonthCountsEveryDayItHad() {
        val february = monthOf(at(2026, Calendar.FEBRUARY, 10))
        val now = at(2026, Calendar.MARCH, 3)

        assertEquals(28, PerDayRate.daysElapsed(february.fromExclusive + 1, february.toInclusive, now))
    }

    @Test
    fun aPeriodThatHasNotStartedCountsNothing() {
        val april = monthOf(at(2026, Calendar.APRIL, 10))
        val now = at(2026, Calendar.MARCH, 3)

        assertEquals(0, PerDayRate.daysElapsed(april.fromExclusive + 1, april.toInclusive, now))
    }

    @Test
    fun oneWholeDayCountsAsOne() {
        val now = at(2026, Calendar.MARCH, 10, 23, 59)
        val day = StatisticsPeriods.rangeFor(StatsPeriod.DAILY, now)

        assertEquals(1, PerDayRate.daysElapsed(day.fromExclusive + 1, day.toInclusive, now))
    }

    /**
     * The quotient of the millisecond span is one out across a DST change -- a 23-hour day makes it
     * round down, a 25-hour one makes it round up -- which is why the count is corrected by field
     * arithmetic rather than being left as a division.
     */
    @Test
    fun aSpanCrossingDaylightSavingStillCountsWholeDays() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // Spring forward: 8 March 2026 is 23 hours long in New York.
            val from = at(2026, Calendar.MARCH, 6)
            val to = at(2026, Calendar.MARCH, 10, 20)
            assertEquals(5, PerDayRate.daysElapsed(from, to, to))

            // Fall back: 1 November 2026 is 25 hours long.
            val autumnFrom = at(2026, Calendar.OCTOBER, 30)
            val autumnTo = at(2026, Calendar.NOVEMBER, 3, 20)
            assertEquals(5, PerDayRate.daysElapsed(autumnFrom, autumnTo, autumnTo))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun theTimeOfDayAtEitherEndDoesNotChangeTheCount() {
        val from = at(2026, Calendar.MARCH, 1, 23, 59)
        val to = at(2026, Calendar.MARCH, 3, 0, 1)

        assertEquals(3, PerDayRate.daysElapsed(from, to, to))
    }

    // --- the rate itself --------------------------------------------------

    @Test
    fun theRateIsTheFigureDividedByTheDays() {
        assertEquals(50_000L, PerDayRate.perDayPaise(150_000L, 3))
    }

    /** A single day's rate is the figure itself; printing it twice says nothing. */
    @Test
    fun aSingleDayHasNoRate() {
        assertNull(PerDayRate.perDayPaise(150_000L, 1))
    }

    @Test
    fun aPeriodThatHasNotStartedHasNoRate() {
        assertNull(PerDayRate.perDayPaise(150_000L, 0))
    }

    /** Net inflow is a negative spend; the rate keeps the sign rather than flipping it. */
    @Test
    fun aNegativeFigureKeepsItsSign() {
        assertEquals(-25_000L, PerDayRate.perDayPaise(-100_000L, 4))
    }
}
