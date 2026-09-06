package com.varun.upitracker.domain.statistics

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PanMathTest {

    private val originalZone = TimeZone.getDefault()

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

    private val now = at(2026, Calendar.MARCH, 12, 15)
    private val earliest = at(2025, Calendar.JANUARY, 6)
    private val visible = 7

    private fun clamp(proposed: Long) =
        PanMath.clampStart(proposed, TrendBucket.DAY, visible, earliest, now)

    // --- drag to buckets --------------------------------------------------

    /** Content follows the finger, so dragging right walks back in time. */
    @Test
    fun draggingRightMovesBackwards() {
        assertEquals(-2, PanMath.bucketDeltaFor(200f, 700f, 7))
    }

    @Test
    fun draggingLeftMovesForwards() {
        assertEquals(3, PanMath.bucketDeltaFor(-300f, 700f, 7))
    }

    @Test
    fun aDragShorterThanHalfABucketMovesNothing() {
        assertEquals(0, PanMath.bucketDeltaFor(40f, 700f, 7))
    }

    @Test
    fun aChartWithNoWidthYetCannotBeDragged() {
        assertEquals(0, PanMath.bucketDeltaFor(200f, 0f, 7))
        assertEquals(0, PanMath.bucketDeltaFor(200f, 700f, 0))
    }

    // --- clamping ---------------------------------------------------------

    @Test
    fun theWindowSnapsToABucketBoundary() {
        assertEquals(at(2026, Calendar.FEBRUARY, 2), clamp(at(2026, Calendar.FEBRUARY, 2, 17)))
    }

    /** The current partial bucket stays the last column rather than being cut off. */
    @Test
    fun panningForwardStopsWithTodayAsTheLastBucket() {
        val start = clamp(at(2026, Calendar.APRIL, 30))
        assertEquals(at(2026, Calendar.MARCH, 6), start)
        val window = PanMath.windowFrom(start, TrendBucket.DAY, visible)
        assertTrue(now >= window.startInclusive && now < window.endExclusive)
        assertEquals(at(2026, Calendar.MARCH, 13), window.endExclusive)
    }

    @Test
    fun panningBackwardStopsAtTheOldestData() {
        assertEquals(earliest, clamp(at(2020, Calendar.JANUARY, 1)))
    }

    @Test
    fun aStartInsideTheDataIsLeftWhereItIs() {
        val start = at(2026, Calendar.FEBRUARY, 2)
        assertEquals(start, clamp(start))
    }

    /**
     * Less history than fits on screen. The window still ends at today, leaving the empty space on
     * the left -- sliding *now* off the right edge to fit three days of data would read worse.
     */
    @Test
    fun tooLittleHistoryStillEndsAtToday() {
        val threeDaysAgo = at(2026, Calendar.MARCH, 9)
        val start = PanMath.clampStart(threeDaysAgo, TrendBucket.DAY, visible, threeDaysAgo, now)
        assertEquals(at(2026, Calendar.MARCH, 6), start)
        assertTrue("the window would start after the data", start < threeDaysAgo)
    }

    // --- windows ----------------------------------------------------------

    @Test
    fun aWindowHoldsExactlyTheBucketsItShows() {
        val window = PanMath.windowFrom(at(2026, Calendar.MARCH, 6), TrendBucket.DAY, visible)
        assertEquals(visible, TrendsBuckets.bucketStarts(TrendBucket.DAY, window).size)
    }

    @Test
    fun aMonthWindowHoldsWholeMonths() {
        val window = PanMath.windowFrom(at(2026, Calendar.JANUARY, 20), TrendBucket.MONTH, 3)
        assertEquals(at(2026, Calendar.JANUARY, 1), window.startInclusive)
        assertEquals(at(2026, Calendar.APRIL, 1), window.endExclusive)
    }

    /**
     * The invariant panning rests on: the window never changes width as it slides.
     *
     * The chart maps a drag to buckets using the count it is currently drawing, and the view model
     * rebuilds the next window from that same count. If a slid window ever tiled to a different
     * number, the two would disagree and the drag would accelerate or stall.
     */
    @Test
    fun aSlidWindowAlwaysHoldsTheSameNumberOfBuckets() {
        listOf(TrendBucket.HOUR to 24, TrendBucket.DAY to 7, TrendBucket.WEEK to 13, TrendBucket.MONTH to 6)
            .forEach { (bucket, count) ->
                var start = TrendsBuckets.startOfBucket(bucket, now)
                repeat(40) {
                    start = TrendsBuckets.addBuckets(bucket, start, -1)
                    // Offset into the bucket, which is what makes this catch a missing snap:
                    // adding two months to the 31st is not adding one twice.
                    val window = PanMath.windowFrom(start + 3_600_000L, bucket, count)
                    assertEquals(
                        "$bucket at $start",
                        count,
                        TrendsBuckets.bucketStarts(bucket, window).size
                    )
                }
            }
    }

    /** Including across the two days of the year that are not 24 hours long. */
    @Test
    fun aSlidWindowKeepsItsWidthAcrossADaylightSavingChange() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        listOf(
            TrendBucket.HOUR to 24,
            TrendBucket.DAY to 7
        ).forEach { (bucket, count) ->
            // Walk a window across the spring forward and the autumn back.
            listOf(at(2026, Calendar.MARCH, 6), at(2026, Calendar.OCTOBER, 30)).forEach { from ->
                var start = TrendsBuckets.startOfBucket(bucket, from)
                repeat(8) {
                    val window = PanMath.windowFrom(start, bucket, count)
                    assertEquals(
                        "$bucket at $start",
                        count,
                        TrendsBuckets.bucketStarts(bucket, window).size
                    )
                    start = TrendsBuckets.addBuckets(bucket, start, 1)
                }
            }
        }
    }

    // --- whether panning is possible at all -------------------------------

    @Test
    fun aWindowWithHistoryBehindItCanPan() {
        assertTrue(PanMath.canPan(at(2026, Calendar.MARCH, 6), TrendBucket.DAY, visible, earliest, now))
    }

    @Test
    fun aWindowThatAlreadyCoversEverythingCannotPan() {
        val start = at(2026, Calendar.MARCH, 6)
        assertFalse(PanMath.canPan(start, TrendBucket.DAY, visible, start, now))
    }

    @Test
    fun panningNeverEscapesItsBoundsHoweverFarItIsPushed() {
        var start = clamp(now)
        repeat(500) {
            start = PanMath.clampStart(
                TrendsBuckets.addBuckets(TrendBucket.DAY, start, -3),
                TrendBucket.DAY, visible, earliest, now
            )
        }
        assertEquals(earliest, start)

        repeat(500) {
            start = PanMath.clampStart(
                TrendsBuckets.addBuckets(TrendBucket.DAY, start, 3),
                TrendBucket.DAY, visible, earliest, now
            )
        }
        assertEquals(at(2026, Calendar.MARCH, 6), start)
    }
}
