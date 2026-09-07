package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.model.FlowTotal
import com.varun.upitracker.domain.TransferDeltaInput

/**
 * What a transfer moves into and out of a set of accounts.
 *
 * Money crossing the edge of the scope, not money moving inside it. A withdrawal from savings to
 * cash is both of those accounts' business and none of the scope's while both are in it -- the same
 * rule the balance line follows, and the same one `getFlowBetween` already applies to a transaction
 * with ME on both sides. Counting an internal move as in *and* out would swamp the chart with the
 * user's own housekeeping.
 *
 * Kotlin rather than SQL because the rule needs the scope on both legs at once, which in SQL means
 * repeating the id list six times across two CASE expressions; and because expressed here it can be
 * held against [com.varun.upitracker.domain.BalanceDeltaCalculator.transferDelta] directly, which is
 * what guarantees the card reconciles with the chart above it.
 */
object TransferFlow {

    /**
     * @return in and out for one transfer, whose difference is that transfer's contribution to the
     *   combined balance of [accountIds].
     */
    fun of(transfer: TransferDeltaInput, accountIds: Set<String>): FlowTotal {
        val leaving = transfer.fromAccountId in accountIds
        val arriving = transfer.toAccountId in accountIds
        return when {
            // Internal. Only the difference between the legs crossed the edge: an ATM fee left, an
            // investment gain arrived, and an even transfer did neither.
            leaving && arriving -> {
                val fee = transfer.amountFromPaise - transfer.amountToPaise
                if (fee >= 0L) FlowTotal(0L, fee) else FlowTotal(-fee, 0L)
            }
            leaving -> FlowTotal(0L, transfer.amountFromPaise)
            arriving -> FlowTotal(transfer.amountToPaise, 0L)
            else -> FlowTotal(0L, 0L)
        }
    }

    /** The running total across a bucket's transfers. */
    fun sum(transfers: List<TransferDeltaInput>, accountIds: Set<String>): FlowTotal {
        var inPaise = 0L
        var outPaise = 0L
        transfers.forEach { transfer ->
            val flow = of(transfer, accountIds)
            inPaise += flow.inPaise
            outPaise += flow.outPaise
        }
        return FlowTotal(inPaise, outPaise)
    }
}
