package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.toBalanceMovements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epochs here are small round numbers rather than real dates. [BalanceTimeline] never interprets
 * one -- it only compares them -- and calendar fixtures would obscure the ordering rules that are
 * the actual subject of these tests.
 */
class BalanceTimelineTest {

    private val savings = "acc-savings"
    private val cash = "acc-cash"
    private val outside = "acc-broker"

    private val starts = listOf(1_000L, 2_000L, 3_000L)
    private val windowEnd = 4_000L

    private fun build(
        accountIds: Set<String> = setOf(savings, cash),
        opening: Map<String, Long> = emptyMap(),
        movements: List<BalanceMovement> = emptyList(),
        anchors: Map<String, List<BalanceAnchor>> = emptyMap()
    ): List<Long> = BalanceTimeline.build(
        bucketStarts = starts,
        windowEndExclusive = windowEnd,
        accountIds = accountIds,
        openingByAccount = opening,
        movements = movements,
        anchorsByAccount = anchors
    )

    // --- the shape of the series -----------------------------------------

    @Test
    fun oneBalancePerBucket() {
        assertEquals(starts.size, build().size)
    }

    @Test
    fun noBucketsIsNoSeriesRatherThanACrash() {
        assertEquals(
            emptyList<Long>(),
            BalanceTimeline.build(emptyList(), 0L, setOf(savings), emptyMap(), emptyList())
        )
    }

    /** The last bucket needs room to end, or its point would fall before its own start. */
    @Test
    fun aWindowEndingInsideTheLastBucketIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BalanceTimeline.build(starts, 3_000L, setOf(savings), emptyMap(), emptyList())
        }
    }

    @Test
    fun anEmptyScopeHoldsNothing() {
        assertEquals(listOf(0L, 0L, 0L), build(accountIds = emptySet(), opening = mapOf(savings to 500L)))
    }

    @Test
    fun anAccountMissingFromTheOpeningMapStartsAtZero() {
        assertEquals(listOf(700L, 700L, 700L), build(opening = mapOf(savings to 700L)))
    }

    // --- movements --------------------------------------------------------

    @Test
    fun noMovementsHoldsTheOpeningBalanceFlat() {
        val opening = mapOf(savings to 10_000L, cash to 2_500L)
        assertEquals(listOf(12_500L, 12_500L, 12_500L), build(opening = opening))
    }

    @Test
    fun oneExpenseStepsTheLineDownOnce() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_400L, savings, -1_500L))
        )
        assertEquals(listOf(10_000L, 8_500L, 8_500L), series)
    }

    /**
     * The bucket a boundary movement lands in. Statement import stamps rows at midnight, so this is
     * the ordinary case rather than a corner one.
     */
    @Test
    fun aMovementAtABucketStartLandsInTheBucketItStarts() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_000L, savings, -1_000L))
        )
        assertEquals(listOf(10_000L, 9_000L, 9_000L), series)
    }

    @Test
    fun aMovementAtTheVeryLastMillisecondStillCounts() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(windowEnd - 1, savings, -1_000L))
        )
        assertEquals(listOf(10_000L, 10_000L, 9_000L), series)
    }

    @Test
    fun movementsOutsideTheWindowAreAlreadyInTheOpeningAndAreNotCountedTwice() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(
                BalanceMovement(500L, savings, -9_999L),      // before the window
                BalanceMovement(9_000L, savings, -9_999L)     // after it
            )
        )
        assertEquals(listOf(10_000L, 10_000L, 10_000L), series)
    }

    @Test
    fun aMovementOnAnAccountOutsideTheScopeIsIgnored() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_400L, outside, -5_000L))
        )
        assertEquals(listOf(10_000L, 10_000L, 10_000L), series)
    }

    // --- anchors ----------------------------------------------------------

    /** The property the whole per-account decomposition exists for. */
    @Test
    fun aMidRangeAnchorReAnchorsOnlyItsOwnAccount() {
        val series = build(
            opening = mapOf(savings to 10_000L, cash to 3_000L),
            movements = listOf(
                BalanceMovement(1_500L, savings, -1_000L),
                BalanceMovement(2_500L, cash, -500L)
            ),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_100L, 50_000L)))
        )
        // Bucket 1: 9_000 + 3_000. Bucket 2: the anchor replaces savings, cash keeps its movement.
        assertEquals(listOf(12_000L, 52_500L, 52_500L), series)
    }

    @Test
    fun movementsAfterAnAnchorAccumulateFromIt() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_500L, savings, -400L)),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_100L, 50_000L)))
        )
        assertEquals(listOf(10_000L, 49_600L, 49_600L), series)
    }

    /**
     * `getBalance` sums `(snapshotEpoch, atEpoch]`, so a movement stamped on the snapshot instant is
     * taken to be already reflected in it. Reproduced here by applying anchors after movements at an
     * equal epoch.
     */
    @Test
    fun aMovementStampedExactlyOnAnAnchorIsDiscardedByIt() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_100L, savings, -9_000L)),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_100L, 50_000L)))
        )
        assertEquals(listOf(10_000L, 50_000L, 50_000L), series)
    }

    @Test
    fun anAnchorBeforeTheWindowIsAlreadyInTheOpeningAndIsNotAppliedAgain() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            anchors = mapOf(savings to listOf(BalanceAnchor(500L, 77_777L)))
        )
        assertEquals(listOf(10_000L, 10_000L, 10_000L), series)
    }

    @Test
    fun successiveAnchorsEachReplaceTheOneBefore() {
        val series = build(
            opening = mapOf(savings to 10_000L),
            anchors = mapOf(
                savings to listOf(BalanceAnchor(1_500L, 20_000L), BalanceAnchor(2_500L, 30_000L))
            )
        )
        assertEquals(listOf(20_000L, 30_000L, 30_000L), series)
    }

    // --- agreement with getBalance ---------------------------------------

    /**
     * The constraint the feature rests on: the line has to read the same as the Accounts screen.
     *
     * Asserted against a transcription of `AccountRepository.getBalance` -- all three of its
     * branches, including the backwards one that derives a balance from a *later* snapshot -- rather
     * than against numbers worked out by hand, which would only re-state whatever this file happens
     * to do.
     *
     * The fixture is built to hit every branch at once: one account reconciled mid-window, one
     * reconciled only before it, one only after it, movements on both sides of the window, and a
     * movement stamped on a snapshot.
     */
    @Test
    fun everyPointAgreesWithGetBalance() {
        val ids = setOf(savings, cash, outside)
        val movements = listOf(
            BalanceMovement(200L, savings, 40_000L),
            BalanceMovement(900L, cash, 5_000L),
            BalanceMovement(1_200L, savings, -1_100L),
            BalanceMovement(1_800L, cash, -300L),
            BalanceMovement(2_100L, savings, -700L),   // stamped on the savings anchor
            BalanceMovement(2_200L, outside, 60_000L),
            BalanceMovement(2_600L, cash, -900L),
            BalanceMovement(3_400L, savings, -250L),
            BalanceMovement(3_900L, outside, -1_000L),
            BalanceMovement(5_000L, cash, -99_999L)    // after the window
        )
        val anchors = mapOf(
            savings to listOf(BalanceAnchor(2_100L, 50_000L)),   // mid-window
            cash to listOf(BalanceAnchor(600L, 8_000L)),         // before it
            outside to listOf(BalanceAnchor(6_000L, 12_000L))    // after it: the backwards branch
        )
        val oracle = GetBalanceOracle(movements, anchors)

        val series = BalanceTimeline.build(
            bucketStarts = starts,
            windowEndExclusive = windowEnd,
            accountIds = ids,
            openingByAccount = ids.associateWith { oracle.balanceAt(it, starts.first() - 1) },
            movements = movements,
            anchorsByAccount = anchors
        )

        val points = listOf(starts[1] - 1, starts[2] - 1, windowEnd - 1)
        points.forEachIndexed { index, point ->
            assertEquals(
                "point $point",
                ids.sumOf { oracle.balanceAt(it, point) },
                series[index]
            )
        }
    }

    /** Transcribed from `AccountRepository.getBalance`; the timeline is checked against this. */
    private class GetBalanceOracle(
        private val movements: List<BalanceMovement>,
        private val anchors: Map<String, List<BalanceAnchor>>
    ) {
        fun balanceAt(accountId: String, atEpoch: Long): Long {
            val own = anchors[accountId].orEmpty()
            own.filter { it.epoch <= atEpoch }.maxByOrNull { it.epoch }?.let { prior ->
                return prior.balancePaise + sum(accountId, prior.epoch, atEpoch)
            }
            own.filter { it.epoch > atEpoch }.minByOrNull { it.epoch }?.let { future ->
                return future.balancePaise - sum(accountId, atEpoch, future.epoch)
            }
            return sum(accountId, Long.MIN_VALUE, atEpoch)
        }

        private fun sum(accountId: String, fromExclusive: Long, toInclusive: Long): Long =
            movements
                .filter { it.accountId == accountId && it.epoch > fromExclusive && it.epoch <= toInclusive }
                .sumOf { it.deltaPaise }
    }

    // --- the mapping from real ledger entries ----------------------------

    private fun spend(accountId: String, epoch: Long, paise: Long) = LedgerEntry.Tx(
        Transaction(
            id = epoch,
            amountPaise = paise,
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.MERCHANT,
            myAccountId = accountId,
            dateEpoch = epoch,
            source = "MANUAL"
        )
    )

    private fun move(from: String?, to: String?, epoch: Long, paise: Long, toPaise: Long = paise) =
        LedgerEntry.Transfer(
            AccountTransfer(
                id = "x$epoch",
                fromAccountId = from,
                toAccountId = to,
                amountFromPaise = paise,
                amountToPaise = toPaise,
                type = AccountTransferType.GENERIC_TRANSFER,
                dateEpoch = epoch,
                source = EntrySource.MANUAL
            )
        )

    @Test
    fun anInternalTransferKeepsBothLegsSoEitherCanBeReAnchored() {
        val movements = listOf(move(savings, cash, 1_500L, 2_000L))
            .toBalanceMovements(setOf(savings, cash))

        assertEquals(2, movements.size)
        assertEquals(0L, movements.sumOf { it.deltaPaise })
        assertTrue(movements.all { it.epoch == 1_500L })
    }

    @Test
    fun anInternalTransferDoesNotMoveTheCombinedLine() {
        val series = build(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, cash, 1_500L, 2_000L)).toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(11_000L, 11_000L, 11_000L), series)
    }

    @Test
    fun aTransferLeavingTheScopeDoesMoveTheLine() {
        val series = build(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, outside, 1_500L, 2_000L)).toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(9_000L, 9_000L, 9_000L), series)
    }

    /** A fee is the difference between the legs and has to survive being split in two. */
    @Test
    fun aTransferFeeLeavesTheCombinedLine() {
        val series = build(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, cash, 1_500L, 2_000L, toPaise = 1_900L))
                .toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(10_900L, 10_900L, 10_900L), series)
    }

    @Test
    fun entriesThatMoveNothingInScopeProduceNoMovements() {
        val entries = listOf(spend(outside, 1_500L, 500L), move(outside, null, 1_600L, 700L))
        assertEquals(emptyList<BalanceMovement>(), entries.toBalanceMovements(setOf(savings, cash)))
    }

    @Test
    fun aSpendMapsToOneNegativeMovementOnItsOwnAccount() {
        val movements = listOf(spend(savings, 1_500L, 2_500L)).toBalanceMovements(setOf(savings, cash))
        assertEquals(listOf(BalanceMovement(1_500L, savings, -2_500L)), movements)
    }
}
