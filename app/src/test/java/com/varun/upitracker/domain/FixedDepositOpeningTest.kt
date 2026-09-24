package com.varun.upitracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedDepositOpeningTest {

    private val booked = 1_000L
    private val principal = 50_000L

    @Test
    fun `a snapshot before the booking holds nothing yet`() {
        assertEquals(
            FixedDepositOpeningSnapshot(0L, "Before booking"),
            FixedDepositOpening.snapshotFor(booked, principal, snapshotEpoch = booked - 1)
        )
    }

    @Test
    fun `a snapshot at the booking holds the principal`() {
        assertEquals(
            FixedDepositOpeningSnapshot(principal, "Principal at booking"),
            FixedDepositOpening.snapshotFor(booked, principal, snapshotEpoch = booked)
        )
    }

    @Test
    fun `a snapshot after the booking holds the principal`() {
        assertEquals(
            FixedDepositOpeningSnapshot(principal, "Principal"),
            FixedDepositOpening.snapshotFor(booked, principal, snapshotEpoch = booked + 1)
        )
    }
}
