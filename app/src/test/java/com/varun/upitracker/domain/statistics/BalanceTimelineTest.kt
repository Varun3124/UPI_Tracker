package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.toBalanceMovements
import org.junit.Assert.assertEquals
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

    private val spanFrom = 1_000L

    /** The points as (epoch, balance) pairs, which reads more plainly in an assertion than two arrays. */
    private fun points(
        accountIds: Set<String> = setOf(savings, cash),
        opening: Map<String, Long> = emptyMap(),
        movements: List<BalanceMovement> = emptyList(),
        anchors: Map<String, List<BalanceAnchor>> = emptyMap()
    ): List<Pair<Long, Long>> {
        val result = BalanceTimeline.points(spanFrom, accountIds, opening, movements, anchors)
        return result.epochs.indices.map { result.epochs[it] to result.balances[it] }
    }

    // --- the shape of the series -----------------------------------------

    @Test
    fun theOpeningBalanceIsAlwaysTheFirstPoint() {
        assertEquals(listOf(spanFrom to 12_500L), points(opening = mapOf(savings to 10_000L, cash to 2_500L)))
    }

    @Test
    fun anEmptyScopeHoldsNothing() {
        assertEquals(listOf(spanFrom to 0L), points(accountIds = emptySet(), opening = mapOf(savings to 500L)))
    }

    @Test
    fun anAccountMissingFromTheOpeningMapStartsAtZero() {
        assertEquals(listOf(spanFrom to 700L), points(opening = mapOf(savings to 700L)))
    }

    // --- movements --------------------------------------------------------

    @Test
    fun everyMovementIsAPointAfterIt() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(
                BalanceMovement(2_400L, savings, -1_500L),
                BalanceMovement(3_100L, cash, 400L)
            )
        )
        assertEquals(listOf(spanFrom to 10_000L, 2_400L to 8_500L, 3_100L to 8_900L), series)
    }

    @Test
    fun movementsSharingAnInstantAreOnePoint() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_400L, savings, -1_500L), BalanceMovement(2_400L, cash, 300L))
        )
        assertEquals(listOf(spanFrom to 10_000L, 2_400L to 8_800L), series)
    }

    @Test
    fun aMovementAtTheOpeningInstantIsWhereTheLineOpens() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(spanFrom, savings, -1_000L))
        )
        assertEquals(listOf(spanFrom to 9_000L), series)
    }

    @Test
    fun movementsBeforeTheSpanAreAlreadyInTheOpeningAndAreNotCountedTwice() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(500L, savings, -9_999L))
        )
        assertEquals(listOf(spanFrom to 10_000L), series)
    }

    @Test
    fun aMovementOnAnAccountOutsideTheScopeIsIgnored() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_400L, outside, -5_000L))
        )
        assertEquals(listOf(spanFrom to 10_000L), series)
    }

    // --- anchors ----------------------------------------------------------

    /** The property the whole per-account decomposition exists for. */
    @Test
    fun anAnchorReAnchorsOnlyItsOwnAccount() {
        val series = points(
            opening = mapOf(savings to 10_000L, cash to 3_000L),
            movements = listOf(
                BalanceMovement(1_500L, savings, -1_000L),
                BalanceMovement(2_500L, cash, -500L)
            ),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_100L, 50_000L)))
        )
        assertEquals(
            listOf(spanFrom to 13_000L, 1_500L to 12_000L, 2_100L to 53_000L, 2_500L to 52_500L),
            series
        )
    }

    /**
     * `getBalance` sums `(snapshotEpoch, atEpoch]`, so a movement stamped on the snapshot instant is
     * taken to be already reflected in it. Reproduced by applying anchors after movements at an
     * equal epoch -- and the two land as one point, where the snapshot says.
     */
    @Test
    fun aMovementStampedExactlyOnAnAnchorIsDiscardedByIt() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            movements = listOf(BalanceMovement(2_100L, savings, -9_000L)),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_100L, 50_000L)))
        )
        assertEquals(listOf(spanFrom to 10_000L, 2_100L to 50_000L), series)
    }

    @Test
    fun anAnchorBeforeTheSpanIsAlreadyInTheOpeningAndIsNotAppliedAgain() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            anchors = mapOf(savings to listOf(BalanceAnchor(500L, 77_777L)))
        )
        assertEquals(listOf(spanFrom to 10_000L), series)
    }

    @Test
    fun anAnchorThatConfirmsTheBalanceAddsNoPoint() {
        val series = points(
            opening = mapOf(savings to 10_000L),
            anchors = mapOf(savings to listOf(BalanceAnchor(2_000L, 10_000L)))
        )
        assertEquals(listOf(spanFrom to 10_000L), series)
    }

    // --- agreement with getBalance ---------------------------------------

    /**
     * The constraint the feature rests on: the graph has to read the same as the Accounts screen,
     * at every point and at every moment between two of them.
     *
     * Asserted against a transcription of `AccountRepository.getBalance` -- all three of its
     * branches, including the backwards one that derives a balance from a *later* snapshot -- rather
     * than against numbers worked out by hand.
     */
    @Test
    fun everyMomentAgreesWithGetBalance() {
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
            BalanceMovement(3_900L, outside, -1_000L)
        )
        val anchors = mapOf(
            savings to listOf(BalanceAnchor(2_100L, 50_000L)),   // mid-span
            cash to listOf(BalanceAnchor(600L, 8_000L)),         // before it
            outside to listOf(BalanceAnchor(6_000L, 12_000L))    // after it: the backwards branch
        )
        val oracle = GetBalanceOracle(movements, anchors)
        val result = BalanceTimeline.points(
            spanFrom = spanFrom,
            accountIds = ids,
            openingByAccount = ids.associateWith { oracle.balanceAt(it, spanFrom - 1) },
            movements = movements,
            anchorsByAccount = anchors
        )

        (spanFrom..4_500L step 50).forEach { moment ->
            assertEquals("at $moment", ids.sumOf { oracle.balanceAt(it, moment) }, result.balanceAt(moment))
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

    /** No money in the scope moved, so there is nothing to plot. */
    @Test
    fun anInternalTransferAddsNoPoint() {
        val series = points(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, cash, 1_500L, 2_000L)).toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(spanFrom to 11_000L), series)
    }

    @Test
    fun aTransferLeavingTheScopeIsAPoint() {
        val series = points(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, outside, 1_500L, 2_000L)).toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(spanFrom to 11_000L, 1_500L to 9_000L), series)
    }

    /** A fee is the difference between the legs and has to survive being split in two. */
    @Test
    fun aTransferFeeIsAPoint() {
        val series = points(
            opening = mapOf(savings to 10_000L, cash to 1_000L),
            movements = listOf(move(savings, cash, 1_500L, 2_000L, toPaise = 1_900L))
                .toBalanceMovements(setOf(savings, cash))
        )
        assertEquals(listOf(spanFrom to 11_000L, 1_500L to 10_900L), series)
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

    @Test
    fun theBalanceAtAMomentIsTheLastPointAtOrBeforeIt() {
        val result = BalancePoints(longArrayOf(10L, 20L, 30L), longArrayOf(100L, 200L, 300L))
        assertEquals(100L, result.balanceAt(5L))   // before them all: where the line opens
        assertEquals(100L, result.balanceAt(10L))
        assertEquals(100L, result.balanceAt(19L))
        assertEquals(200L, result.balanceAt(20L))
        assertEquals(300L, result.balanceAt(99L))
    }
}
