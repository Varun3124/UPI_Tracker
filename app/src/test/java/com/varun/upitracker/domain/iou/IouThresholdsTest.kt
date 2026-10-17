package com.varun.upitracker.domain.iou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IouThresholdsTest {

    private val defaults = IouThresholds()

    @Test
    fun `under ten rupees either way is settled, ten itself is not`() {
        assertTrue(defaults.isSettled(0L))
        assertTrue(defaults.isSettled(999L))
        assertTrue(defaults.isSettled(-999L))
        assertFalse(defaults.isSettled(1_000L))
        assertFalse(defaults.isSettled(-1_000L))
    }

    @Test
    fun `under a hundred rupees either way is insignificant`() {
        assertTrue(defaults.isInsignificant(9_999L))
        assertTrue(defaults.isInsignificant(-9_999L))
        assertFalse(defaults.isInsignificant(10_000L))
    }

    @Test
    fun `a settled limit of nothing means only an exact zero is settled`() {
        val exact = IouThresholds(settledBelowPaise = 1L, insignificantBelowPaise = 10_000L)
        assertTrue(exact.isSettled(0L))
        assertFalse(exact.isSettled(1L))
        assertFalse(IouThresholds(0L, 0L).isSettled(0L))
    }

    @Test
    fun `settled can be no wider than insignificant, and neither negative`() {
        assertNull(IouThresholds.problemWith(1_000L, 10_000L))
        assertNull(IouThresholds.problemWith(5_000L, 5_000L))
        assertNotNull(IouThresholds.problemWith(20_000L, 10_000L))
        assertNotNull(IouThresholds.problemWith(-1L, 10_000L))
    }
}
