package com.varun.upitracker.domain.statistics

import com.varun.upitracker.domain.BalanceDeltaCalculator
import com.varun.upitracker.domain.TransferDeltaInput
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFlowTest {

    private val savings = "acc-savings"
    private val cash = "acc-cash"
    private val fd = "acc-fd"
    private val scope = setOf(savings, cash)

    private fun move(from: String?, to: String?, fromPaise: Long, toPaise: Long = fromPaise) =
        TransferDeltaInput(from, to, fromPaise, toPaise)

    // --- crossing the edge of the scope -----------------------------------

    @Test
    fun moneyLeavingTheScopeIsOut() {
        assertEquals(FlowOf(0L, 20_000L), flow(move(savings, fd, 20_000L)))
    }

    @Test
    fun moneyArrivingFromOutsideIsIn() {
        assertEquals(FlowOf(20_000L, 0L), flow(move(fd, cash, 20_000L)))
    }

    @Test
    fun anExternalLegCountsInFull() {
        assertEquals(FlowOf(0L, 5_000L), flow(move(savings, null, 5_000L)))
        assertEquals(FlowOf(5_000L, 0L), flow(move(null, cash, 5_000L)))
    }

    @Test
    fun aTransferTouchingNeitherSideIsNothing() {
        assertEquals(FlowOf(0L, 0L), flow(move(fd, null, 9_000L)))
    }

    // --- moving inside the scope ------------------------------------------

    /**
     * A cash withdrawal with both accounts in scope. Counting it as in *and* out would swamp the
     * chart with the user's own housekeeping, and nothing actually crossed the edge.
     */
    @Test
    fun anInternalMoveIsNeitherDirection() {
        assertEquals(FlowOf(0L, 0L), flow(move(savings, cash, 20_000L)))
    }

    @Test
    fun onlyAnInternalTransfersFeeLeaves() {
        assertEquals(FlowOf(0L, 100L), flow(move(savings, cash, 20_000L, toPaise = 19_900L)))
    }

    @Test
    fun anInternalGainArrives() {
        assertEquals(FlowOf(150L, 0L), flow(move(savings, cash, 20_000L, toPaise = 20_150L)))
    }

    // --- the property the card rests on -----------------------------------

    /**
     * In minus out has to be exactly what the transfer moved the combined balance by, or the
     * in-and-out card stops reconciling with the balance line directly above it.
     *
     * Held against [BalanceDeltaCalculator.transferDelta] itself rather than against numbers worked
     * out here, since that function is what the balance line is built from.
     */
    @Test
    fun inMinusOutIsWhatTheBalanceMoved() {
        listOf(
            move(savings, cash, 20_000L),
            move(savings, cash, 20_000L, toPaise = 19_900L),
            move(savings, cash, 20_000L, toPaise = 20_150L),
            move(savings, fd, 50_000L),
            move(fd, cash, 50_000L),
            move(savings, null, 5_000L),
            move(null, cash, 5_000L),
            move(fd, null, 9_000L),
            move(null, null, 1_000L),
            move(savings, savings, 700L)
        ).forEach { transfer ->
            val flow = TransferFlow.of(transfer, scope)
            assertEquals(
                "$transfer",
                scope.sumOf { BalanceDeltaCalculator.transferDelta(it, transfer) },
                flow.inPaise - flow.outPaise
            )
        }
    }

    @Test
    fun aSumIsTheSumOfItsParts() {
        val transfers = listOf(
            move(savings, fd, 20_000L),
            move(fd, cash, 8_000L),
            move(savings, cash, 3_000L, toPaise = 2_900L)
        )
        val total = TransferFlow.sum(transfers, scope)

        assertEquals(8_000L, total.inPaise)
        assertEquals(20_100L, total.outPaise)
        assertEquals(
            transfers.sumOf { t -> scope.sumOf { BalanceDeltaCalculator.transferDelta(it, t) } },
            total.inPaise - total.outPaise
        )
    }

    @Test
    fun anEmptyScopeMovesNothing() {
        assertEquals(FlowOf(0L, 0L), flow(move(savings, cash, 20_000L), emptySet()))
        assertEquals(FlowOf(0L, 0L), flow(move(savings, null, 20_000L), emptySet()))
    }

    @Test
    fun aSingleAccountScopeSeesItsOwnSideOnly() {
        assertEquals(FlowOf(0L, 20_000L), flow(move(savings, cash, 20_000L), setOf(savings)))
        assertEquals(FlowOf(20_000L, 0L), flow(move(savings, cash, 20_000L), setOf(cash)))
    }

    private data class FlowOf(val inPaise: Long, val outPaise: Long)

    private fun flow(transfer: TransferDeltaInput, accountIds: Set<String> = scope): FlowOf =
        TransferFlow.of(transfer, accountIds).let { FlowOf(it.inPaise, it.outPaise) }
}
