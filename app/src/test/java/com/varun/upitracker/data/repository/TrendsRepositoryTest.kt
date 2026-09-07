package com.varun.upitracker.data.repository

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.statistics.BalanceAnchor
import com.varun.upitracker.domain.statistics.BalanceMovement
import com.varun.upitracker.domain.statistics.BalanceTimeline
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsRepositoryTest {

    private val savings = "acc-savings"
    private val cash = "acc-cash"
    private val scope = setOf(savings, cash)

    private val spanFrom = 1_000L
    private val spanTo = 4_000L

    // --- assembly ---------------------------------------------------------

    @Test
    fun bothTablesFeedTheSameMovementList() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(
                transactions = listOf(spend(savings, epoch = 1_500L, paise = 2_000L)),
                transfers = listOf(transfer(cash, null, epoch = 2_500L, paise = 700L))
            )
        )

        val inputs = repository.loadBalanceInputs(spanFrom, spanTo, scope)

        assertEquals(
            listOf(
                BalanceMovement(1_500L, savings, -2_000L),
                BalanceMovement(2_500L, cash, -700L)
            ),
            inputs.movements.sortedBy { it.epoch }
        )
    }

    /** Both legs survive, so a reconciliation can re-anchor one of the two accounts alone. */
    @Test
    fun anInternalTransferArrivesAsTwoMovements() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(transfers = listOf(transfer(savings, cash, epoch = 2_000L, paise = 900L)))
        )

        val movements = repository.loadBalanceInputs(spanFrom, spanTo, scope).movements

        assertEquals(2, movements.size)
        assertEquals(0L, movements.sumOf { it.deltaPaise })
    }

    @Test
    fun onlyTheScopedAccountsAreCarried() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(transactions = listOf(spend("acc-broker", epoch = 1_500L, paise = 2_000L)))
        )

        assertEquals(emptyList<BalanceMovement>(), repository.loadBalanceInputs(spanFrom, spanTo, scope).movements)
    }

    /** Inclusive at its upper bound, so a movement dated at the span start is inside the span. */
    @Test
    fun theOpeningBalanceIsTakenOneMillisecondBeforeTheSpan() = runBlocking {
        val source = FakeTrendsDataSource(balances = mapOf(savings to 10_000L, cash to 250L))
        val repository = TrendsRepository(source)

        val inputs = repository.loadBalanceInputs(spanFrom, spanTo, scope)

        assertEquals(mapOf(savings to 10_000L, cash to 250L), inputs.openingByAccount)
        assertEquals(setOf(spanFrom - 1), source.balanceEpochsAsked.toSet())
    }

    @Test
    fun snapshotsBecomeAnchorsGroupedByAccount() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(
                snapshots = listOf(
                    snapshot(savings, epoch = 1_200L, paise = 44_000L),
                    snapshot(savings, epoch = 3_100L, paise = 41_000L),
                    snapshot(cash, epoch = 2_000L, paise = 900L)
                )
            )
        )

        val anchors = repository.loadBalanceInputs(spanFrom, spanTo, scope).anchorsByAccount

        assertEquals(
            mapOf(
                savings to listOf(BalanceAnchor(1_200L, 44_000L), BalanceAnchor(3_100L, 41_000L)),
                cash to listOf(BalanceAnchor(2_000L, 900L))
            ),
            anchors
        )
    }

    @Test
    fun theSpanBoundsReachTheQueriesUnchanged() = runBlocking {
        val source = FakeTrendsDataSource()
        TrendsRepository(source).loadBalanceInputs(spanFrom, spanTo, scope)

        assertEquals(listOf(spanFrom to spanTo), source.transactionRangesAsked)
        assertEquals(listOf(spanFrom to spanTo), source.transferRangesAsked)
        assertEquals(listOf(spanFrom to spanTo), source.snapshotRangesAsked)
    }

    // --- degenerate input -------------------------------------------------

    @Test
    fun anEmptyScopeAsksTheDatabaseNothing() = runBlocking {
        val source = FakeTrendsDataSource(transactions = listOf(spend(savings, 1_500L, 2_000L)))

        val inputs = TrendsRepository(source).loadBalanceInputs(spanFrom, spanTo, emptySet())

        assertEquals(0, source.queryCount)
        assertEquals(emptyList<BalanceMovement>(), inputs.movements)
        assertEquals(emptyMap<String, Long>(), inputs.openingByAccount)
    }

    /** The opening is read one millisecond earlier, which would wrap round. */
    @Test
    fun aSpanStartingAtTheBottomOfTheRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { TrendsRepository(FakeTrendsDataSource()).loadBalanceInputs(Long.MIN_VALUE, spanTo, scope) }
        }
    }

    // --- how far back panning may go --------------------------------------

    @Test
    fun theEarliestEpochIsTheOldestOfTheThreeTables() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(
                transactions = listOf(spend(savings, epoch = 5_000L, paise = 1L)),
                transfers = listOf(transfer(savings, null, epoch = 2_000L, paise = 1L)),
                snapshots = listOf(snapshot(savings, epoch = 9_000L, paise = 1L))
            )
        )

        assertEquals(2_000L, repository.earliestDataEpoch(scope))
    }

    @Test
    fun anEmptyTableIsSkippedRatherThanCountingAsZero() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(snapshots = listOf(snapshot(savings, epoch = 9_000L, paise = 1L)))
        )

        assertEquals(9_000L, repository.earliestDataEpoch(scope))
    }

    @Test
    fun aScopeThatKnowsNothingHasNoEarliestEpoch() = runBlocking {
        assertNull(TrendsRepository(FakeTrendsDataSource()).earliestDataEpoch(scope))
        assertNull(TrendsRepository(FakeTrendsDataSource()).earliestDataEpoch(emptySet()))
    }

    // --- the whole chain --------------------------------------------------

    /**
     * What Phase 2 and Phase 3 amount to together: rows in, a drawable series out. Asserted here
     * because the two halves are separately convincing and could still disagree at the seam --
     * about the movement sign, the anchor grouping, or which end of the span the opening sits at.
     */
    @Test
    fun theLoadedInputsDrawTheSeriesTheyDescribe() = runBlocking {
        val repository = TrendsRepository(
            FakeTrendsDataSource(
                transactions = listOf(
                    spend(savings, epoch = 1_400L, paise = 1_000L),
                    spend(cash, epoch = 2_600L, paise = 500L)
                ),
                transfers = listOf(transfer(savings, cash, epoch = 3_200L, paise = 2_000L)),
                snapshots = listOf(snapshot(savings, epoch = 2_050L, paise = 30_000L)),
                balances = mapOf(savings to 20_000L, cash to 5_000L)
            )
        )
        val inputs = repository.loadBalanceInputs(spanFrom, spanTo, scope)

        val series = BalanceTimeline.build(
            bucketStarts = listOf(1_000L, 2_000L, 3_000L),
            windowEndExclusive = spanTo,
            accountIds = inputs.accountIds,
            openingByAccount = inputs.openingByAccount,
            movements = inputs.movements,
            anchorsByAccount = inputs.anchorsByAccount
        )

        assertEquals(
            listOf(
                24_000L,  // 19_000 savings + 5_000 cash
                34_500L,  // savings re-anchored to 30_000, cash down 500
                34_500L   // the internal transfer nets out
            ),
            series
        )
    }

    // --- fixtures ---------------------------------------------------------

    private fun spend(accountId: String, epoch: Long, paise: Long) = Transaction(
        id = epoch,
        amountPaise = paise,
        payerActorType = ActorType.ME,
        payeeActorType = ActorType.MERCHANT,
        myAccountId = accountId,
        dateEpoch = epoch,
        source = "MANUAL"
    )

    private fun transfer(from: String?, to: String?, epoch: Long, paise: Long) = AccountTransfer(
        id = "x$epoch",
        fromAccountId = from,
        toAccountId = to,
        amountFromPaise = paise,
        amountToPaise = paise,
        type = AccountTransferType.GENERIC_TRANSFER,
        dateEpoch = epoch,
        source = EntrySource.MANUAL
    )

    private fun snapshot(accountId: String, epoch: Long, paise: Long) = BalanceSnapshot(
        id = "s-$accountId-$epoch",
        accountId = accountId,
        snapshotEpoch = epoch,
        balancePaise = paise,
        source = BalanceSnapshotSource.MANUAL
    )

    /**
     * Applies the same half-open range and scope filters the shipped SQL does, so the assembly is
     * exercised against realistic rows. That the SQL really filters this way is checked separately,
     * by running the shipped query text against a SQLite fixture.
     */
    private class FakeTrendsDataSource(
        private val transactions: List<Transaction> = emptyList(),
        private val transfers: List<AccountTransfer> = emptyList(),
        private val snapshots: List<BalanceSnapshot> = emptyList(),
        private val balances: Map<String, Long> = emptyMap()
    ) : TrendsDataSource {

        val transactionRangesAsked = mutableListOf<Pair<Long, Long>>()
        val transferRangesAsked = mutableListOf<Pair<Long, Long>>()
        val snapshotRangesAsked = mutableListOf<Pair<Long, Long>>()
        val balanceEpochsAsked = mutableListOf<Long>()
        var queryCount = 0
            private set

        override suspend fun getTransactionsBetween(fromEpoch: Long, toEpochExclusive: Long): List<Transaction> {
            queryCount++
            transactionRangesAsked += fromEpoch to toEpochExclusive
            return transactions.filter { it.dateEpoch >= fromEpoch && it.dateEpoch < toEpochExclusive }
        }

        override suspend fun getTransfersBetween(fromEpoch: Long, toEpochExclusive: Long): List<AccountTransfer> {
            queryCount++
            transferRangesAsked += fromEpoch to toEpochExclusive
            return transfers.filter { it.dateEpoch >= fromEpoch && it.dateEpoch < toEpochExclusive }
        }

        override suspend fun getSnapshotsBetween(
            accountIds: List<String>,
            fromEpoch: Long,
            toEpochExclusive: Long
        ): List<BalanceSnapshot> {
            queryCount++
            snapshotRangesAsked += fromEpoch to toEpochExclusive
            return snapshots
                .filter { it.accountId in accountIds }
                .filter { it.snapshotEpoch >= fromEpoch && it.snapshotEpoch < toEpochExclusive }
                .sortedWith(compareBy({ it.accountId }, { it.snapshotEpoch }, { it.id }))
        }

        override suspend fun earliestTransactionEpoch(accountIds: List<String>): Long? {
            queryCount++
            return transactions.filter { it.myAccountId in accountIds }.minOfOrNull { it.dateEpoch }
        }

        override suspend fun earliestTransferEpoch(accountIds: List<String>): Long? {
            queryCount++
            return transfers
                .filter { it.fromAccountId in accountIds || it.toAccountId in accountIds }
                .minOfOrNull { it.dateEpoch }
        }

        override suspend fun earliestSnapshotEpoch(accountIds: List<String>): Long? {
            queryCount++
            return snapshots.filter { it.accountId in accountIds }.minOfOrNull { it.snapshotEpoch }
        }

        override suspend fun getBalance(accountId: String, atEpoch: Long): Long {
            queryCount++
            balanceEpochsAsked += atEpoch
            return balances[accountId] ?: 0L
        }
    }

    @Test
    fun theFakeIsNotSilentlyDoingNothing() = runBlocking {
        val source = FakeTrendsDataSource()
        TrendsRepository(source).loadBalanceInputs(spanFrom, spanTo, scope)
        assertTrue("the assembly asked for nothing", source.queryCount > 0)
    }
}
