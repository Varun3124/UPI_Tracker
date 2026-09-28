package com.varun.upitracker.domain.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSearchTest {

    private val row = listOf("Dan Sharma", "Blue Tokai", null, "morning coffee")

    /** An empty search box is not a filter. */
    @Test
    fun aBlankQueryMatchesEverything() {
        assertTrue(TransactionSearch.matches(row, ""))
        assertTrue(TransactionSearch.matches(row, "   "))
        assertTrue(TransactionSearch.matches(emptyList(), ""))
    }

    @Test
    fun aPartialWordMatches() {
        assertTrue(TransactionSearch.matches(row, "toka"))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(TransactionSearch.matches(row, "DAN"))
        assertTrue(TransactionSearch.matches(listOf("dan"), "Dan"))
    }

    /** The point of splitting on whitespace: the two terms live in different fields. */
    @Test
    fun everyTermMayMatchADifferentField() {
        assertTrue(TransactionSearch.matches(row, "dan coffee"))
    }

    @Test
    fun everyTermMustMatchSomething() {
        assertFalse(TransactionSearch.matches(row, "dan pizza"))
    }

    @Test
    fun repeatedWhitespaceIsNotAnEmptyTerm() {
        assertTrue(TransactionSearch.matches(row, "  dan   coffee  "))
    }

    @Test
    fun nullAndBlankFieldsAreIgnoredRatherThanMatched() {
        assertFalse(TransactionSearch.matches(listOf(null, "   "), "dan"))
    }

    @Test
    fun aRowWithNothingToMatchAgainstNeverMatchesARealQuery() {
        assertFalse(TransactionSearch.matches(emptyList(), "dan"))
    }
}
