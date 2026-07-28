package com.varun.upitracker.domain

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ui.ActorType

class GetAccountBalanceUseCase(private val db: AppDatabase) {

    suspend operator fun invoke(accountId: String, atEpoch: Long = System.currentTimeMillis()): Long {
        val priorSnapshot = db.balanceSnapshotDao().getLatestAtOrBefore(accountId, atEpoch)
        if (priorSnapshot != null) {
            return priorSnapshot.balancePaise + sumDeltas(
                accountId = accountId,
                fromEpochExclusive = priorSnapshot.snapshotEpoch,
                toEpochInclusive = atEpoch
            )
        }

        val futureSnapshot = db.balanceSnapshotDao().getEarliestAfter(accountId, atEpoch)
        if (futureSnapshot != null) {
            return futureSnapshot.balancePaise - sumDeltas(
                accountId = accountId,
                fromEpochExclusive = atEpoch,
                toEpochInclusive = futureSnapshot.snapshotEpoch
            )
        }

        return sumDeltas(accountId, Long.MIN_VALUE, atEpoch)
    }

    private suspend fun sumDeltas(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long {
        val transactionDelta = db.transactionDao()
            .getAccountTransactionsBetween(accountId, fromEpochExclusive, toEpochInclusive)
            .sumOf { transaction ->
                BalanceDeltaCalculator.transactionDelta(
                    TransactionDeltaInput(
                        amountPaise = transaction.amountPaise,
                        payerActorType = transaction.payerActorType,
                        payeeActorType = transaction.payeeActorType
                    )
                )
            }

        val transferDelta = db.accountTransferDao()
            .getAccountTransfersBetween(accountId, fromEpochExclusive, toEpochInclusive)
            .sumOf { transfer ->
                BalanceDeltaCalculator.transferDelta(
                    accountId = accountId,
                    transfer = TransferDeltaInput(
                        fromAccountId = transfer.fromAccountId,
                        toAccountId = transfer.toAccountId,
                        amountFromPaise = transfer.amountFromPaise,
                        amountToPaise = transfer.amountToPaise
                    )
                )
            }

        return transactionDelta + transferDelta
    }
}
