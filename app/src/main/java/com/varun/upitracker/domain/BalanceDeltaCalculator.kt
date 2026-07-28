package com.varun.upitracker.domain

import com.varun.upitracker.ui.ActorType

data class TransactionDeltaInput(
    val amountPaise: Long,
    val payerActorType: String,
    val payeeActorType: String
)

data class TransferDeltaInput(
    val fromAccountId: String?,
    val toAccountId: String?,
    val amountFromPaise: Long,
    val amountToPaise: Long
)

object BalanceDeltaCalculator {
    fun transactionDelta(transaction: TransactionDeltaInput): Long {
        return when {
            transaction.payeeActorType == ActorType.ME -> transaction.amountPaise
            transaction.payerActorType == ActorType.ME -> -transaction.amountPaise
            else -> 0L
        }
    }

    fun transferDelta(accountId: String, transfer: TransferDeltaInput): Long {
        var delta = 0L
        if (transfer.fromAccountId == accountId) delta -= transfer.amountFromPaise
        if (transfer.toAccountId == accountId) delta += transfer.amountToPaise
        return delta
    }
}
