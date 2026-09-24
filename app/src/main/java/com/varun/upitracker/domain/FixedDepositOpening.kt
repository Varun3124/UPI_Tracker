package com.varun.upitracker.domain

/** The balance and note a fixed deposit's opening snapshot carries. */
data class FixedDepositOpeningSnapshot(val balancePaise: Long, val notes: String)

/**
 * What a new fixed deposit's opening snapshot says, given the date it is placed on.
 *
 * The date defaults to the source account's first snapshot rather than the booking, because
 * [BalanceConfidence.certainFrom] takes the **latest** of the accounts' first snapshots: a deposit
 * first reconciled at its booking would mark every total containing it as speculation right up to
 * that day, when its balance before booking was never in doubt -- it did not exist.
 *
 * `getBalance` sums `(snapshotEpoch, atEpoch]`, so the FD_BOOKING transfer counts on top of the
 * snapshot only when it falls strictly after it. A snapshot at or after the booking already holds
 * the principal; one before it holds nothing, and the transfer supplies the principal later.
 */
object FixedDepositOpening {

    fun snapshotFor(bookedEpoch: Long, principalPaise: Long, snapshotEpoch: Long): FixedDepositOpeningSnapshot =
        when {
            snapshotEpoch < bookedEpoch -> FixedDepositOpeningSnapshot(0L, "Before booking")
            snapshotEpoch == bookedEpoch -> FixedDepositOpeningSnapshot(principalPaise, "Principal at booking")
            else -> FixedDepositOpeningSnapshot(principalPaise, "Principal")
        }
}
