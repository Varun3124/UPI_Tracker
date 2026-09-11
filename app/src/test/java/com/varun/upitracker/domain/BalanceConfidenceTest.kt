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

    // --- speculativePointCount -----------------------------------------------------------------

    private val starts = listOf(0L, day, 2 * day, 3 * day)
    private val windowEnd = 4 * day

    @Test
    fun `a point is judged by the end of its bucket, not its start`() {
        // A snapshot taken during day 2 has already been applied by the time that day's point is
        // plotted, so only days 0 and 1 are speculation.
        assertEquals(2, BalanceConfidence.speculativePointCount(starts, windowEnd, 2 * day + 1))
    }

    @Test
    fun `a reconciliation at the very start leaves nothing speculative`() {
        assertEquals(0, BalanceConfidence.speculativePointCount(starts, windowEnd, 0L))
    }

    @Test
    fun `a reconciliation after the window leaves everything speculative`() {
        assertEquals(4, BalanceConfidence.speculativePointCount(starts, windowEnd, 10 * day))
        assertEquals(4, BalanceConfidence.speculativePointCount(starts, windowEnd, null))
    }

    @Test
    fun `an empty series counts nothing`() {
        assertEquals(0, BalanceConfidence.speculativePointCount(emptyList(), windowEnd, null))
    }

    // --- checkpointFraction --------------------------------------------------------------------

    @Test
    fun `the checkpoint lands proportionally inside its own bucket`() {
        // Halfway through bucket 1 of 4: one whole slot plus half of the next, over four.
        val fraction = BalanceConfidence.checkpointFraction(starts, windowEnd, day + day / 2)
        assertEquals(0.375f, fraction!!, 0.0001f)
    }

    @Test
    fun `a bucket boundary lands on the slot edge`() {
        assertEquals(0.5f, BalanceConfidence.checkpointFraction(starts, windowEnd, 2 * day)!!, 0.0001f)
    }

    @Test
    fun `measured in slots rather than in time`() {
        // Uneven buckets: a month next to a day. The checkpoint at the second bucket's start sits
        // halfway across a two-bucket plot regardless of how long the first one ran.
        val uneven = listOf(0L, 30 * day)
        val fraction = BalanceConfidence.checkpointFraction(uneven, 31 * day, 30 * day)
        assertEquals(0.5f, fraction!!, 0.0001f)
    }

    @Test
    fun `no line is drawn when it would say nothing`() {
        // Nothing on screen is speculative, everything is, or there is no reconciliation at all.
        assertNull(BalanceConfidence.checkpointFraction(starts, windowEnd, 0L))
        assertNull(BalanceConfidence.checkpointFraction(starts, windowEnd, -day))
        assertNull(BalanceConfidence.checkpointFraction(starts, windowEnd, windowEnd))
        assertNull(BalanceConfidence.checkpointFraction(starts, windowEnd, null))
        assertNull(BalanceConfidence.checkpointFraction(emptyList(), windowEnd, day))
    }

    @Test
    fun `the checkpoint always stays inside the plot`() {
        listOf(1L, day, 2 * day, windowEnd - 1).forEach { epoch ->
            val fraction = BalanceConfidence.checkpointFraction(starts, windowEnd, epoch)
            assertTrue("$epoch produced $fraction", fraction!! in 0f..1f)
        }
    }

    @Test
    fun `the checkpoint and the speculative run agree on where the boundary is`() {
        // The line must land inside the first bucket drawn as certain, never back among the brown
        // ones. Bucket i owns slot [i/n, (i+1)/n), and the bucket holding the reconciliation is
        // the first certain one -- its point is the balance at the END of that bucket, by which
        // time the snapshot has been applied.
        listOf(day / 2, day + 1, 2 * day, 2 * day + day / 2, windowEnd - 1).forEach { certainFrom ->
            val count = BalanceConfidence.speculativePointCount(starts, windowEnd, certainFrom)
            val fraction = BalanceConfidence.checkpointFraction(starts, windowEnd, certainFrom)!!
            // Inclusive at the top: a reconciliation on the window's last millisecond rounds to
            // the plot's right edge in float, which is where it belongs.
            assertTrue(
                "certainFrom=$certainFrom count=$count fraction=$fraction",
                fraction >= count.toFloat() / starts.size &&
                    fraction <= (count + 1).toFloat() / starts.size
            )
        }
    }
}
