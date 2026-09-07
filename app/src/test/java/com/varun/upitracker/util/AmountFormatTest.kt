package com.varun.upitracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grouping threshold is the whole point of this formatter, so the boundary either side of ten
 * thousand and the lakh/crore transitions are pinned here rather than eyeballed on a device.
 */
class AmountFormatTest {

    private val rs = AmountFormat.SYMBOL

    @Test
    fun `below ten thousand carries no separator`() {
        assertEquals("0", AmountFormat.groupIndian(0))
        assertEquals("383", AmountFormat.groupIndian(383))
        assertEquals("4916", AmountFormat.groupIndian(4916))
        assertEquals("9999", AmountFormat.groupIndian(9999))
    }

    @Test
    fun `ten thousand is the first grouped value`() {
        assertEquals("10,000", AmountFormat.groupIndian(10_000))
        assertEquals("99,999", AmountFormat.groupIndian(99_999))
    }

    @Test
    fun `groups the Indian way above a lakh`() {
        assertEquals("1,00,000", AmountFormat.groupIndian(100_000))
        assertEquals("1,50,000", AmountFormat.groupIndian(150_000))
        assertEquals("12,34,567", AmountFormat.groupIndian(1_234_567))
        assertEquals("1,20,00,000", AmountFormat.groupIndian(12_000_000))
        assertEquals("1,00,00,00,000", AmountFormat.groupIndian(1_000_000_000))
    }

    @Test
    fun `sign sits outside the symbol`() {
        assertEquals("-" + rs + "500", AmountFormat.rupees(-50_000))
        assertEquals(rs + "383", AmountFormat.rupees(38_300))
        assertEquals(rs + "1,50,000", AmountFormat.rupees(15_000_000))
    }

    @Test
    fun `exact keeps paise only when there are any`() {
        assertEquals(rs + "600", AmountFormat.rupeesExact(60_000))
        assertEquals(rs + "600.50", AmountFormat.rupeesExact(60_050))
        assertEquals(rs + "600.05", AmountFormat.rupeesExact(60_005))
        assertEquals(rs + "1,50,000.25", AmountFormat.rupeesExact(15_000_025))
        assertEquals("-" + rs + "600.50", AmountFormat.rupeesExact(-60_050))
    }

    /**
     * The one that would break saving rather than only looking wrong: a grouped string in an amount
     * field fails `toDoubleOrNull()` on the way back out.
     */
    @Test
    fun `input form is never grouped`() {
        assertEquals("150000", AmountFormat.forInput(15_000_000))
        assertEquals("600.50", AmountFormat.forInput(60_050))
        assertEquals("600", AmountFormat.forInput(60_000))
        assertEquals("0", AmountFormat.forInput(0))
        assertEquals("150000", AmountFormat.forInput(-15_000_000))
        assertEquals(150000.0, AmountFormat.forInput(15_000_000).toDouble(), 0.001)
    }

    @Test
    fun `axis abbreviates instead of grouping`() {
        assertEquals("0", AmountFormat.axis(0))
        assertEquals("999", AmountFormat.axis(99_900))
        assertEquals("1.5k", AmountFormat.axis(150_000))
        assertEquals("12k", AmountFormat.axis(1_200_000))
        assertEquals("1.5L", AmountFormat.axis(15_000_000))
        assertEquals("12L", AmountFormat.axis(120_000_000))
        assertEquals("2.4Cr", AmountFormat.axis(240_000_000_0L))
        assertEquals("-1.5L", AmountFormat.axis(-15_000_000))
    }
}
