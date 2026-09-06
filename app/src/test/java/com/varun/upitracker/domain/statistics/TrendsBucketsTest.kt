package com.varun.upitracker.domain.statistics

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrendsBucketsTest {

    private val originalZone = TimeZone.getDefault()

    /**
     * Pinned, because bucket *counts* are the subject here and a DST transition changes them. The
     * app's own zone has none, so this is the honest default; [hourBucketsTileA23HourDay] then opts
     * into a zone that does.
     */
    @Before
    fun pinZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Every start is the previous bucket's end, and the run covers the window with nothing spare. */
    private fun assertTiles(bucket: TrendBucket, window: TrendWindow, starts: List<Long>) {
        assertTrue("no buckets", starts.isNotEmpty())
        assertTrue("first start is after the window", starts.first() <= window.startInclusive)
        starts.zipWithNext { a, b ->
            assertEquals("gap or overlap at $a", b, TrendsBuckets.addBuckets(bucket, a, 1))
        }
        assertTrue(
            "the last bucket ends before the window does",
            TrendsBuckets.addBuckets(bucket, starts.last(), 1) >= window.endExclusive
        )
        assertTrue("a bucket starts past the window", starts.last() < window.endExclusive)
    }

    // --- windows ----------------------------------------------------------

    @Test
    fun aWeekWindowRunsFromMondayToTheNextMonday() {
        val window = TrendsBuckets.windowFor(StatsPeriod.WEEKLY, at(2026, Calendar.MARCH, 12))
        assertEquals(at(2026, Calendar.MARCH, 9), window.startInclusive)
        assertEquals(at(2026, Calendar.MARCH, 16), window.endExclusive)
    }

    /** Half-open here, exclusive-at-the-start for the DAO: one place converts, and this is it. */
    @Test
    fun theWindowConvertsToTheRangeTheDaoExpects() {
        val window = TrendsBuckets.windowFor(StatsPeriod.MONTHLY, at(2026, Calendar.FEBRUARY, 14))
        val range = window.asDateRange()
        assertEquals(
            StatisticsPeriods.rangeFor(StatsPeriod.MONTHLY, at(2026, Calendar.FEBRUARY, 14)),
            range
        )
        assertTrue(window.startInclusive > range.fromExclusive)
        assertEquals(window.endExclusive - 1, range.toInclusive)
    }

    /**
     * `startOf` and `shift` are silent no-ops for these two, and `rangeFor(ALL_TIME)` answers
     * `Long.MIN_VALUE`, which overflows the moment anything subtracts one from it. Both have to fail
     * loudly rather than return a window that looks plausible.
     */
    @Test
    fun theTwoPeriodsWithoutArithmeticAreRejected() {
        listOf(StatsPeriod.ALL_TIME, StatsPeriod.CUSTOM).forEach { period ->
            assertThrows(IllegalArgumentException::class.java) {
                TrendsBuckets.windowFor(period, at(2026, Calendar.MARCH, 12))
            }
        }
    }

    // --- bucket choice ----------------------------------------------------

    @Test
    fun eachPeriodPicksItsBucket() {
        val window = TrendWindow(0L, StatisticsPeriods.DAY_MILLIS)
        assertEquals(TrendBucket.HOUR, TrendsBuckets.bucketFor(StatsPeriod.DAILY, window))
        assertEquals(TrendBucket.DAY, TrendsBuckets.bucketFor(StatsPeriod.WEEKLY, window))
        assertEquals(TrendBucket.DAY, TrendsBuckets.bucketFor(StatsPeriod.MONTHLY, window))
        assertEquals(TrendBucket.WEEK, TrendsBuckets.bucketFor(StatsPeriod.QUARTERLY, window))
        assertEquals(TrendBucket.MONTH, TrendsBuckets.bucketFor(StatsPeriod.ALL_TIME, window))
    }

    @Test
    fun aLongCustomRangeCoarsensToWeeks() {
        val short = TrendWindow(0L, 90 * StatisticsPeriods.DAY_MILLIS)
        val long = TrendWindow(0L, 200 * StatisticsPeriods.DAY_MILLIS)
        assertEquals(TrendBucket.DAY, TrendsBuckets.bucketFor(StatsPeriod.CUSTOM, short))
        assertEquals(TrendBucket.WEEK, TrendsBuckets.bucketFor(StatsPeriod.CUSTOM, long))
    }

    // --- tiling -----------------------------------------------------------

    @Test
    fun aDayHoldsTwentyFourHourBuckets() {
        val window = TrendsBuckets.windowFor(StatsPeriod.DAILY, at(2026, Calendar.MARCH, 12, 15))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.HOUR, window)
        assertEquals(24, starts.size)
        assertTiles(TrendBucket.HOUR, window, starts)
    }

    @Test
    fun aWeekHoldsSevenDayBuckets() {
        val window = TrendsBuckets.windowFor(StatsPeriod.WEEKLY, at(2026, Calendar.MARCH, 12))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.DAY, window)
        assertEquals(7, starts.size)
        assertTiles(TrendBucket.DAY, window, starts)
    }

    @Test
    fun aShortMonthHoldsItsOwnNumberOfDayBuckets() {
        val window = TrendsBuckets.windowFor(StatsPeriod.MONTHLY, at(2026, Calendar.FEBRUARY, 14))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.DAY, window)
        assertEquals(28, starts.size)
        assertTiles(TrendBucket.DAY, window, starts)
    }

    @Test
    fun aQuarterHoldsWholeWeeksCoveringIt() {
        val window = TrendsBuckets.windowFor(StatsPeriod.QUARTERLY, at(2026, Calendar.MAY, 4))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.WEEK, window)
        assertTiles(TrendBucket.WEEK, window, starts)
        assertTrue("a quarter is about thirteen weeks, got ${starts.size}", starts.size in 13..15)
    }

    /**
     * The reason none of this uses a millisecond stride. A spring-forward day is 23 hours long, and
     * a fixed 3_600_000ms step would leave every later bucket an hour off the clock.
     */
    @Test
    fun hourBucketsTileA23HourDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val window = TrendsBuckets.windowFor(StatsPeriod.DAILY, at(2026, Calendar.MARCH, 8, 12))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.HOUR, window)

        assertEquals(23, starts.size)
        assertTiles(TrendBucket.HOUR, window, starts)
    }

    @Test
    fun dayBucketsTileAcrossASpringForward() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val window = TrendsBuckets.windowFor(StatsPeriod.WEEKLY, at(2026, Calendar.MARCH, 8, 12))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.DAY, window)

        assertEquals(7, starts.size)
        assertTiles(TrendBucket.DAY, window, starts)
    }

    @Test
    fun aWindowStartingMidBucketStillStartsOnOne() {
        val window = TrendWindow(at(2026, Calendar.MARCH, 12, 13), at(2026, Calendar.MARCH, 15))
        val starts = TrendsBuckets.bucketStarts(TrendBucket.DAY, window)
        assertEquals(at(2026, Calendar.MARCH, 12), starts.first())
    }

    @Test
    fun anEmptyWindowHasNoBuckets() {
        val at = at(2026, Calendar.MARCH, 12)
        assertEquals(emptyList<Long>(), TrendsBuckets.bucketStarts(TrendBucket.DAY, TrendWindow(at, at)))
    }

    @Test
    fun aBucketWindowIsExactlyOneBucketWide() {
        val start = at(2026, Calendar.MARCH, 12)
        val window = TrendsBuckets.bucketWindow(TrendBucket.DAY, start)
        assertEquals(start, window.startInclusive)
        assertEquals(at(2026, Calendar.MARCH, 13), window.endExclusive)
    }

    // --- labels -----------------------------------------------------------

    @Test
    fun everyBucketIsLabelledWhenTheyFit() {
        assertEquals(1, TrendsBuckets.labelStride(7))
    }

    @Test
    fun aMonthOfDaysThinsOutItsLabels() {
        assertTrue(labelCount(31, TrendsBuckets.labelStride(31)) < 31)
    }

    /** The guarantee the caller relies on when it picks a cap for its own label width. */
    @Test
    fun theStrideNeverLetsMoreLabelsThroughThanAsked() {
        (1..400).forEach { count ->
            (1..12).forEach { maxLabels ->
                val drawn = labelCount(count, TrendsBuckets.labelStride(count, maxLabels))
                assertTrue("$count buckets, max $maxLabels, drew $drawn", drawn <= maxLabels)
            }
        }
    }

    @Test
    fun theStrideIsNeverZero() {
        (0..400).forEach { count ->
            assertTrue("count $count", TrendsBuckets.labelStride(count) >= 1)
            assertTrue("count $count, no cap", TrendsBuckets.labelStride(count, 0) >= 1)
        }
    }

    private fun labelCount(bucketCount: Int, stride: Int): Int =
        (0 until bucketCount).count { it % stride == 0 }
}
