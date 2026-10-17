package com.varun.upitracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceConfidenceTest {

    private val day = 86_400_000L

    // --- certainFrom ---------------------------------------------------------------------------

    @Test
    fun `one account is certain from its own first snapshot`() {
        assertEquals(100L, BalanceConfidence.certainFrom(listOf(100L)))
    }

    @Test
    fun `a combination waits for the last account to be reconciled`() {
        // Not the earliest: until the 300 account has been counted, the total still contains a
        // figure nobody has checked.
        assertEquals(300L, BalanceConfidence.certainFrom(listOf(100L, 300L, 200L)))
    }

    @Test
    fun `one unreconciled account makes the whole combination speculation`() {
        assertNull(BalanceConfidence.certainFrom(listOf(100L, null, 200L)))
        assertNull(BalanceConfidence.certainFrom(listOf(null)))
    }

    @Test
    fun `an empty scope is never certain`() {
        assertNull(BalanceConfidence.certainFrom(emptyList()))
    }

    // --- isSpeculative -------------------------------------------------------------------------

    @Test
    fun `the snapshot instant itself is already certain`() {
        assertFalse(BalanceConfidence.isSpeculative(100L, 100L))
        assertTrue(BalanceConfidence.isSpeculative(99L, 100L))
    }

    @Test
    fun `nothing is certain without a reconciliation`() {
        assertTrue(BalanceConfidence.isSpeculative(Long.MAX_VALUE, null))
    }
}
