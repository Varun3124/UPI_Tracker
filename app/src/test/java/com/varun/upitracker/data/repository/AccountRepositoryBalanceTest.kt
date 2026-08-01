package com.varun.upitracker.data.repository

import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.domain.TransactionDeltaInput
import com.varun.upitracker.domain.TransferDeltaInput
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountRepositoryBalanceTest {

    @Test
    fun getBalance_usesPriorSnapshotPlusForwardDeltas() = runBlocking {
        val repository = AccountRepository(
            FakeBalanceDataSource(
                snapshots = listOf(snapshot(epoch = 100, balance = 10_000)),
                transactions = listOf(
                    TimedTransaction(150, outgoing(1_500)),
                    TimedTransaction(200, incoming(750))
                ),
                transfers = listOf(TimedTransfer(175, transferIn(250)))
            )
        )

        assertEquals(9_500, repository.getBalance(ACCOUNT_ID, 200))
    }

    @Test
    fun getBalance_usesFutureSnapshotMinusForwardDeltasWhenNoPriorSnapshot() = runBlocking {
        val repository = AccountRepository(
            FakeBalanceDataSource(
                snapshots = listOf(snapshot(epoch = 300, balance = 20_000)),
                transactions = listOf(TimedTransaction(250, outgoing(2_000))),
                transfers = listOf(TimedTransfer(275, transferIn(500)))
            )
        )

        assertEquals(21_500, repository.getBalance(ACCOUNT_ID, 200))
    }

    @Test
    fun getBalance_sumsDeltasWhenThereAreNoSnapshots() = runBlocking {
        val repository = AccountRepository(
            FakeBalanceDataSource(
                snapshots = emptyList(),
                transactions = listOf(
                    TimedTransaction(50, incoming(4_000)),
                    TimedTransaction(100, outgoing(1_250))
                ),
                transfers = listOf(TimedTransfer(120, transferOut(500)))
            )
        )

        assertEquals(2_250, repository.getBalance(ACCOUNT_ID, 150))
    }

    @Test
    fun getBalance_exactSnapshotEpochReturnsSnapshotWithoutDoubleCounting() = runBlocking {
        val repository = AccountRepository(
            FakeBalanceDataSource(
                snapshots = listOf(snapshot(epoch = 100, balance = 8_000)),
                transactions = listOf(TimedTransaction(100, incoming(1_000)))
            )
        )

        assertEquals(8_000, repository.getBalance(ACCOUNT_ID, 100))
    }

    private fun snapshot(epoch: Long, balance: Long) = BalanceSnapshot(
        id = "snapshot-$epoch",
        accountId = ACCOUNT_ID,
        snapshotEpoch = epoch,
        balancePaise = balance,
        source = BalanceSnapshotSource.MANUAL
    )

    private fun incoming(amountPaise: Long) = TransactionDeltaInput(
        amountPaise = amountPaise,
        payerActorType = ActorType.FRIEND,
        payeeActorType = ActorType.ME
    )

    private fun outgoing(amountPaise: Long) = TransactionDeltaInput(
        amountPaise = amountPaise,
        payerActorType = ActorType.ME,
        payeeActorType = ActorType.MERCHANT
    )

    private fun transferIn(amountPaise: Long) = TransferDeltaInput(
        fromAccountId = "other",
        toAccountId = ACCOUNT_ID,
        amountFromPaise = amountPaise,
        amountToPaise = amountPaise
    )

    private fun transferOut(amountPaise: Long) = TransferDeltaInput(
        fromAccountId = ACCOUNT_ID,
        toAccountId = "other",
        amountFromPaise = amountPaise,
        amountToPaise = amountPaise
    )

    private data class TimedTransaction(val epoch: Long, val input: TransactionDeltaInput)
    private data class TimedTransfer(val epoch: Long, val input: TransferDeltaInput)

    private class FakeBalanceDataSource(
        private val snapshots: List<BalanceSnapshot>,
        private val transactions: List<TimedTransaction> = emptyList(),
        private val transfers: List<TimedTransfer> = emptyList()
    ) : AccountBalanceDataSource {
        override suspend fun getLatestAtOrBefore(accountId: String, atEpoch: Long): BalanceSnapshot? {
            return snapshots
                .filter { it.accountId == accountId && it.snapshotEpoch <= atEpoch }
                .maxByOrNull { it.snapshotEpoch }
        }

        override suspend fun getEarliestAfter(accountId: String, atEpoch: Long): BalanceSnapshot? {
            return snapshots
                .filter { it.accountId == accountId && it.snapshotEpoch > atEpoch }
                .minByOrNull { it.snapshotEpoch }
        }

        override suspend fun getTransactionDeltasBetween(
            accountId: String,
            fromEpochExclusive: Long,
            toEpochInclusive: Long
        ): List<TransactionDeltaInput> {
            return transactions
                .filter { it.epoch > fromEpochExclusive && it.epoch <= toEpochInclusive }
                .map { it.input }
        }

        override suspend fun getTransferDeltasBetween(
            accountId: String,
            fromEpochExclusive: Long,
            toEpochInclusive: Long
        ): List<TransferDeltaInput> {
            return transfers
                .filter { it.epoch > fromEpochExclusive && it.epoch <= toEpochInclusive }
                .map { it.input }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "account"
    }
}
