package com.varun.upitracker.domain.transactionentry.share

import org.junit.Assert.assertEquals
import org.junit.Test

class BalancingSuggestionsTest {

    private val calculator = ShareCalculator()

    @Test
    fun noAmountYet_suggestsNothing() {
        assertEquals(emptyList<Long>(), calculator.balancingSuggestions(0L, listOf(0L, 500L)))
    }

    @Test
    fun othersAlreadyCoverTheAmount_suggestsNothing() {
        assertEquals(emptyList<Long>(), calculator.balancingSuggestions(1000L, listOf(600L, 400L)))
        assertEquals(emptyList<Long>(), calculator.balancingSuggestions(1000L, listOf(1200L)))
    }

    @Test
    fun everyOtherRowFilled_suggestsOnlyTheRemainder() {
        assertEquals(listOf(400L), calculator.balancingSuggestions(1000L, listOf(600L)))
    }

    @Test
    fun emptyRows_alsoSuggestTheRemainderSplitWithThisOne() {
        // 1000 - 400 = 600 left, shared between the two empty rows and this one.
        assertEquals(listOf(600L, 200L), calculator.balancingSuggestions(1000L, listOf(400L, 0L, 0L)))
    }

    @Test
    fun evenSplit_isRoundedDownToThePaisa() {
        assertEquals(listOf(10000L, 3333L), calculator.balancingSuggestions(10000L, listOf(0L, 0L)))
    }

    @Test
    fun evenSplitOfOnePaisa_isDropped() {
        assertEquals(listOf(1L), calculator.balancingSuggestions(1001L, listOf(1000L, 0L)))
    }
}
