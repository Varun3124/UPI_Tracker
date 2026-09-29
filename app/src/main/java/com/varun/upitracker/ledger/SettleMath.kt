package com.varun.upitracker.ledger

import com.varun.upitracker.database.entity.IouEntry
import kotlin.math.abs

/** What settling one amount against a friend's unsettled entries does to them. */
data class SettleOutcome(
    /** Entries now fully settled, already copied with `isSettled = true`. */
    val settled: List<IouEntry>,
    /** What is left of the entry that was only partly settled, as a new unsettled row to insert. */
    val residual: IouEntry?,
    /** Whatever the entries could not absorb, still to be recorded as a balance change of its own. */
    val remainderPaise: Long
)

/**
 * Settle-oldest-first, with no database behind it.
 *
 * [com.varun.upitracker.data.repository.LedgerRepository] applies what this decides. Lifted out so the
 * exact bookkeeping a repayment performs can run under plain JUnit: whether a replay rebuilds the same
 * balances as live posting is a question about these rules, not about Room.
 */
object SettleMath {

    /**
     * Settles [amountPaise] against [oldestFirst] in order.
     *
     * An entry the amount covers is flagged settled. The first one it cannot cover is flagged settled
     * too, and a residual row carries what is left of it -- attributed to the same transaction, or the
     * same declaration for an opening, so the residual stays where the debt came from.
     */
    fun settle(oldestFirst: List<IouEntry>, amountPaise: Long, now: Long): SettleOutcome {
        var remaining = amountPaise
        val settled = mutableListOf<IouEntry>()
        var residual: IouEntry? = null
        for (entry in oldestFirst) {
            if (remaining <= 0L) break
            val magnitude = abs(entry.amountPaise)
            settled += entry.copy(isSettled = true, settledEpoch = now)
            if (remaining >= magnitude) {
                remaining -= magnitude
            } else {
                val residualMagnitude = magnitude - remaining
                residual = IouEntry(
                    transactionId = entry.transactionId,
                    friendId = entry.friendId,
                    amountPaise = if (entry.amountPaise >= 0L) residualMagnitude else -residualMagnitude,
                    isSettled = false,
                    declarationId = entry.declarationId
                )
                remaining = 0L
            }
        }
        return SettleOutcome(settled, residual, remaining)
    }
}
