package com.varun.upitracker.domain.transactionentry.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.varun.upitracker.util.AmountFormat

/**
 * One rule guards both directions: a refund must not push a category below zero, and editing a
 * purchase must not drop a category below what has already been refunded against it.
 */
class RefundCoverageTest {

    private val validator = TransactionValidator()
    private val names = mapOf(1L to "Food", 2L to "Drinks")

    private fun check(original: Map<Long, Long>, refunded: Map<Long, Long>) =
        validator.validateRefundCoverage(original, refunded, names)

    @Test
    fun partialRefundWithinTheCategory_isAllowed() {
        assertTrue(check(mapOf(1L to 60000L, 2L to 40000L), mapOf(1L to 18000L)).isValid)
    }

    @Test
    fun fullRefundOfEveryCategory_isAllowed() {
        assertTrue(
            check(mapOf(1L to 60000L, 2L to 40000L), mapOf(1L to 60000L, 2L to 40000L)).isValid
        )
    }

    @Test
    fun refundingMoreThanWasSpentOnACategory_isRejected() {
        val result = check(mapOf(1L to 60000L, 2L to 40000L), mapOf(1L to 70000L))
        assertFalse(result.isValid)
        assertEquals(
            "Refunds under Food would exceed the ${AmountFormat.SYMBOL}600 spent on it.",
            result.message
        )
    }

    @Test
    fun refundingACategoryThePurchaseNeverUsed_isRejected() {
        val result = check(mapOf(1L to 60000L), mapOf(2L to 5000L))
        assertFalse(result.isValid)
        assertEquals("The purchase has nothing under Drinks to refund.", result.message)
    }

    /** Two partial refunds are fine apart and too much together. */
    @Test
    fun cumulativeRefundsAcrossSeparateTransactions_areCaught() {
        assertTrue(check(mapOf(1L to 60000L), mapOf(1L to 40000L)).isValid)
        assertFalse(check(mapOf(1L to 60000L), mapOf(1L to 40000L + 30000L)).isValid)
    }

    /** The reverse direction: the purchase is being edited down under an existing refund. */
    @Test
    fun editingThePurchaseBelowWhatWasRefunded_isRejected() {
        assertFalse(check(mapOf(1L to 10000L), mapOf(1L to 18000L)).isValid)
        assertTrue(check(mapOf(1L to 20000L), mapOf(1L to 18000L)).isValid)
    }

    @Test
    fun zeroRefundEntriesAreIgnored() {
        assertTrue(check(mapOf(1L to 60000L), mapOf(1L to 0L, 2L to 0L)).isValid)
    }

    @Test
    fun paiseAmountsFormatWithDecimals() {
        val result = check(mapOf(1L to 60050L), mapOf(1L to 70000L))
        assertEquals(
            "Refunds under Food would exceed the ${AmountFormat.SYMBOL}600.50 spent on it.",
            result.message
        )
    }
}
