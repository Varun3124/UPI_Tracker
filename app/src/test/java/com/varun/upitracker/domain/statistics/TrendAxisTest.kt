package com.varun.upitracker.domain.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAxisTest {

    private fun isNiceStep(step: Long): Boolean {
        var unit = 1L
        while (unit < step) {
            if (step == unit || step == 2 * unit || step == 5 * unit) return true
            unit *= 10
        }
        return step == unit
    }

    // --- the axis contains the data --------------------------------------

    @Test
    fun theAxisEnclosesTheValuesItWasFittedTo() {
        listOf(
            0L to 1L,
            0L to 999_999L,
            -50_000L to 120_000L,
            1L to 3L,
            -7L to -3L,
            12_345_678L to 98_765_432L
        ).forEach { (lo, hi) ->
            val axis = TrendAxis.fit(lo, hi)
            assertTrue("$lo..$hi -> ${axis.minPaise}", axis.minPaise <= lo)
            assertTrue("$lo..$hi -> ${axis.maxPaise}", axis.maxPaise >= hi)
        }
    }

    @Test
    fun theStepIsAlwaysAOneTwoOrFive() {
        (0..40).forEach { i ->
            val hi = (1L shl i)
            assertTrue("span $hi step ${TrendAxis.fit(0, hi).stepPaise}", isNiceStep(TrendAxis.fit(0, hi).stepPaise))
        }
    }

    @Test
    fun theStepNeverDividesFinerThanARupee() {
        assertEquals(TrendAxis.MIN_STEP_PAISE, TrendAxis.fit(0L, 1L).stepPaise)
    }

    @Test
    fun bothEndsSitOnAStepBoundary() {
        listOf(0L to 47_321L, -9_100L to 88_888L, 500L to 501L).forEach { (lo, hi) ->
            val axis = TrendAxis.fit(lo, hi)
            assertEquals(0L, axis.minPaise % axis.stepPaise)
            assertEquals(0L, axis.maxPaise % axis.stepPaise)
        }
    }

    // --- degenerate input -------------------------------------------------

    /** An empty chart still has to draw an axis, and a zero span would divide by zero. */
    @Test
    fun anAllZeroSeriesStillGetsAUsableAxis() {
        val axis = TrendAxis.fit(0L, 0L)
        assertEquals(0L, axis.minPaise)
        assertTrue(axis.maxPaise > 0L)
    }

    /**
     * A balance that did not move all week. Grown upward only: pushing the floor below zero would
     * make an income bar chart's bars float off its baseline.
     */
    @Test
    fun aFlatSeriesGrowsUpwardsRatherThanAroundItself() {
        val axis = TrendAxis.fit(50_000L, 50_000L)
        assertEquals(50_000L, axis.minPaise)
        assertTrue(axis.maxPaise > 50_000L)
    }

    @Test
    fun reversedBoundsAreReadInEitherOrder() {
        assertEquals(TrendAxis.fit(0L, 90_000L), TrendAxis.fit(90_000L, 0L))
    }

    @Test
    fun zeroStaysTheFloorWhenTheCallerAsksForIt() {
        assertEquals(0L, TrendAxis.fit(0L, 123_456L).minPaise)
    }

    /** An overdraft has to be drawable, and integer division truncates towards zero. */
    @Test
    fun aNegativeMinimumRoundsDownAndNotTowardsZero() {
        val axis = TrendAxis.fit(-45_000L, 10_000L)
        assertTrue("floor ${axis.minPaise} is above the data", axis.minPaise <= -45_000L)
        assertEquals(0L, axis.minPaise % axis.stepPaise)
    }

    // --- gridlines and mapping -------------------------------------------

    @Test
    fun gridlinesRunFromTheFloorToTheTop() {
        val axis = TrendAxis.fit(0L, 200_000L)
        val lines = axis.gridlines
        assertEquals(axis.minPaise, lines.first())
        assertEquals(axis.maxPaise, lines.last())
        lines.zipWithNext { a, b -> assertEquals(axis.stepPaise, b - a) }
    }

    @Test
    fun theGridlineCountStaysReadable() {
        listOf(0L to 1L, 0L to 350_000L, -120_000L to 4_500_000L, 0L to 99_000_000L)
            .forEach { (lo, hi) ->
                val count = TrendAxis.fit(lo, hi).gridlines.size
                assertTrue("$lo..$hi drew $count gridlines", count in 2..8)
            }
    }

    @Test
    fun theFractionRunsFromZeroAtTheFloorToOneAtTheTop() {
        val axis = TrendAxis.fit(-50_000L, 150_000L)
        assertEquals(0f, axis.fractionOf(axis.minPaise), 0.0001f)
        assertEquals(1f, axis.fractionOf(axis.maxPaise), 0.0001f)
        assertTrue(axis.fractionOf(0L) > 0f && axis.fractionOf(0L) < 1f)
    }

    @Test
    fun aDegenerateAxisCannotBeBuiltAtAll() {
        assertThrows(IllegalArgumentException::class.java) { ValueAxis(100L, 100L, 10L) }
        assertThrows(IllegalArgumentException::class.java) { ValueAxis(0L, 100L, 0L) }
    }
}
