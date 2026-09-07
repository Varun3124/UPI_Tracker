package com.varun.upitracker.database.model

/**
 * One counterparty's share of a single category over a window -- the merchant breakdown behind a
 * drilled-into pie slice.
 *
 * Both ids are nullable and at most one is set. A purchase carries [merchantId]; a ledger-neutral
 * gift carries [friendId] instead, and must still appear or the breakdown would not sum to the
 * slice it came from.
 */
data class PayeeTotal(
    val merchantId: Long?,
    val friendId: Long?,
    val payeeName: String,
    val netPaise: Long
)
