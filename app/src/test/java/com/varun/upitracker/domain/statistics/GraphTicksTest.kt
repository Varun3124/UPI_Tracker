package com.varun.upitracker.domain.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GraphTicksTest {

    private val day = 86_400_000L

    private fun epoch(year: Int, month: Int, dayOfMonth: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, hour, 0, 0)
        }.timeInMillis

    private fun cal(epoch: Long) = Calendar.getInstance().apply { timeInMillis = epoch }

    // --- balance ----------------------------------------------------------

    @Test
    fun `steps are one, two or five times a power of ten`() {
        assertEquals(1_000.0 to 5, GraphTicks.niceStep(1_000.0))
        assertEquals(2_000.0 to 4, GraphTicks.niceStep(1_200.0))
        assertEquals(5_000.0 to 5, GraphTicks.niceStep(3_000.0))
        assertEquals(10_000.0 to 5, GraphTicks.niceStep(7_000.0))
    }

    @Test
    fun `balance majors are round and far enough apart, with minors between`() {
        // ₹0 to ₹1,000 over 500px, majors at least 100px apart: a ₹200 step.
        val ticks = GraphTicks.valueTicks(0.0, 100_000.0, 500f, 100f)
        assertEquals(listOf(0.0, 20_000.0, 40_000.0, 60_000.0, 80_000.0, 100_000.0), ticks.major)
        assertTrue(ticks.minor.isNotEmpty())
        assertTrue(ticks.minor.none { it in ticks.major })
        assertTrue(ticks.minor.all { it in 0.0..100_000.0 })
    }

    @Test
    fun `nothing finer than a rupee`() {
        val ticks = GraphTicks.valueTicks(0.0, 1_000.0, 2_000f, 50f)
        assertTrue(ticks.major.zipWithNext().all { (a, b) -> b - a >= 100.0 })
    }

    // --- time -------------------------------------------------------------

    @Test
    fun `a week across is marked by days at midnight, with hours between`() {
        val from = epoch(2026, Calendar.OCTOBER, 12, 15)
        val to = from + 7 * day
        // 1,000px for a week: a day is about 143px, an hour too small for a major.
        val ticks = GraphTicks.timeTicks(from, to, 1_000.0 / (7 * day), 100f)
        assertEquals(7, ticks.major.size)
        ticks.major.forEach { assertEquals(0, cal(it.epoch).get(Calendar.HOUR_OF_DAY)) }
        assertTrue(ticks.minor.isNotEmpty())
    }

    @Test
    fun `a year across is marked by months on the first`() {
        val from = epoch(2026, Calendar.JANUARY, 15)
        val to = from + 365 * day
        val ticks = GraphTicks.timeTicks(from, to, 1_000.0 / (365 * day), 60f)
        assertEquals(12, ticks.major.size)
        ticks.major.forEach { assertEquals(1, cal(it.epoch).get(Calendar.DAY_OF_MONTH)) }
    }

    @Test
    fun `marks never fall outside the stretch asked about`() {
        val from = epoch(2026, Calendar.MARCH, 3, 9)
        val to = from + day
        val ticks = GraphTicks.timeTicks(from, to, 1_000.0 / day, 80f)
        assertTrue(ticks.major.isNotEmpty())
        assertTrue((ticks.major + ticks.minor).all { it.epoch in from..to })
    }

    @Test
    fun `marks land in the same places wherever the screen starts`() {
        val from = epoch(2026, Calendar.MAY, 1)
        val pxPerMilli = 1_000.0 / (30 * day)
        val a = GraphTicks.timeTicks(from, from + 30 * day, pxPerMilli, 80f).major.map { it.epoch }.toSet()
        val b = GraphTicks.timeTicks(from + 3 * day + 12345, from + 33 * day, pxPerMilli, 80f).major.map { it.epoch }.toSet()
        val overlap = a.intersect(b)
        assertTrue(overlap.isNotEmpty())
        // Every mark of the shifted screen that falls inside the first is one of the first's.
        assertTrue(b.filter { it <= from + 30 * day }.all { it in a })
    }
}
