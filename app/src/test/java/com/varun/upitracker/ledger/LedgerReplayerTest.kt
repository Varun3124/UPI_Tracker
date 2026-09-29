package com.varun.upitracker.ledger

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.declaration.Opening
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rebuilding a friend's base ledger from the transactions that still belong to it.
 *
 * What [com.varun.upitracker.data.repository.LedgerRepository] then does with those calls -- which
 * entries it settles oldest-first, how it splits a partial one -- needs a database and is not
 * reachable from here. So these assert the *sequence of port calls*, which is the whole of the
 * replayer's contract: the same calls in the same order reproduce the same balances.
 */
class LedgerReplayerTest {

    private val rahul = 7L
    private val priya = 8L

    /** Records what reaches the ledger, in order, the same shape LedgerPostingServiceTest uses. */
    private class RecordingLedger : LedgerPort {
        val calls = mutableListOf<String>()

        override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
            calls += "balance:$transactionId:$friendId:$deltaPaise"
        }

        override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
            calls += "repayment:$transactionId:$friendId:$creditAmountPaise"
        }

        override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
            calls += "settlement:$transactionId:$friendId:$debitAmountPaise"
        }

        override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) {
            calls += "opening:$declarationId:$friendId:$amountPaise"
        }
    }

    /**
     * Hands over a fixed list, and records that the delete happened -- and when. Rows at or before
     * the floor it is asked for are left out, as the real query leaves them out.
     */
    private class FakeSource(
        private val rows: List<TaggedTx>,
        private val openings: Map<Long, Opening> = emptyMap()
    ) : LedgerReplayer.Source {
        val events = mutableListOf<String>()
        var askedFor: Set<Long>? = null
        var askedAfter: Long? = null

        override suspend fun openingsFor(friendIds: Set<Long>): Map<Long, Opening> {
            events += "openings"
            return openings.filterKeys { it in friendIds }
        }

        override suspend fun rowsFor(friendIds: Set<Long>, afterEpoch: Long?): List<TaggedTx> {
            askedFor = friendIds
            askedAfter = afterEpoch
            events += "read"
            return rows.filter { afterEpoch == null || it.transaction.dateEpoch > afterEpoch }
        }

        override suspend fun clearEntriesFor(friendIds: Set<Long>) {
            events += "clear"
        }
    }

    private fun tx(
        id: Long,
        dateEpoch: Long,
        payerType: String,
        payerFriendId: Long? = null,
        payeeType: String,
        payeeFriendId: Long? = null,
        amountPaise: Long = 50_000L,
        shares: List<TransactionShare> = emptyList()
    ) = TaggedTx(
        Transaction(
            id = id,
            amountPaise = amountPaise,
            payerActorType = payerType,
            payerFriendId = payerFriendId,
            payerRawLabel = payerFriendId?.let { "Friend $it" },
            payeeActorType = payeeType,
            payeeFriendId = payeeFriendId,
            payeeRawLabel = payeeFriendId?.let { "Friend $it" },
            dateEpoch = dateEpoch,
            source = "MANUAL",
            ledgerEffect = LedgerEffect.DEBT,
            iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        ),
        shares.map { it.copy(transactionId = id) }
    )

    private fun purchaseSplitWith(id: Long, dateEpoch: Long, friendId: Long, eachPaise: Long) = tx(
        id = id,
        dateEpoch = dateEpoch,
        payerType = ActorType.ME,
        payeeType = ActorType.MERCHANT,
        amountPaise = eachPaise * 2,
        shares = listOf(
            TransactionShare(
                transactionId = id,
                side = "PAYER",
                participantType = ActorType.ME,
                amountPaise = eachPaise
            ),
            TransactionShare(
                transactionId = id,
                side = "PAYER",
                participantType = ActorType.FRIEND,
                friendId = friendId,
                amountPaise = eachPaise,
                rawLabel = "Friend $friendId"
            )
        )
    )

    private fun replay(
        rows: List<TaggedTx>,
        friendIds: Set<Long>,
        openings: Map<Long, Opening> = emptyMap()
    ): Pair<FakeSource, RecordingLedger> {
        val source = FakeSource(rows, openings)
        val ledger = RecordingLedger()
        runBlocking { LedgerReplayer(source, ledger).replay(friendIds) }
        return source to ledger
    }

    // --- ordering ------------------------------------------------------------------------------

    @Test
    fun `rows are replayed oldest first, with the id breaking a date tie`() {
        val shuffled = listOf(
            purchaseSplitWith(id = 5L, dateEpoch = 300L, friendId = rahul, eachPaise = 5_000L),
            purchaseSplitWith(id = 2L, dateEpoch = 100L, friendId = rahul, eachPaise = 1_000L),
            purchaseSplitWith(id = 4L, dateEpoch = 200L, friendId = rahul, eachPaise = 4_000L),
            purchaseSplitWith(id = 3L, dateEpoch = 100L, friendId = rahul, eachPaise = 2_000L)
        )
        val (_, ledger) = replay(shuffled, setOf(rahul))
        assertEquals(
            listOf(
                "balance:2:$rahul:1000",
                "balance:3:$rahul:2000",
                "balance:4:$rahul:4000",
                "balance:5:$rahul:5000"
            ),
            ledger.calls
        )
    }

    @Test
    fun `order is a pure function of the rows`() {
        val rows = listOf(
            purchaseSplitWith(id = 9L, dateEpoch = 200L, friendId = rahul, eachPaise = 1_000L),
            purchaseSplitWith(id = 1L, dateEpoch = 200L, friendId = rahul, eachPaise = 1_000L),
            purchaseSplitWith(id = 4L, dateEpoch = 100L, friendId = rahul, eachPaise = 1_000L)
        )
        assertEquals(
            listOf(4L, 1L, 9L),
            LedgerReplayer.order(rows.reversed()).map { it.transaction.id }
        )
        assertEquals(
            LedgerReplayer.order(rows).map { it.transaction.id },
            LedgerReplayer.order(rows.reversed()).map { it.transaction.id }
        )
    }

    // --- scoping --------------------------------------------------------------------------------

    @Test
    fun `only the friends being rebuilt are posted`() {
        // One transaction ME split with both friends; only Rahul's ledger is being rebuilt.
        val id = 11L
        val split = tx(
            id = id,
            dateEpoch = 100L,
            payerType = ActorType.ME,
            payeeType = ActorType.MERCHANT,
            amountPaise = 90_000L,
            shares = listOf(
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.ME, amountPaise = 30_000L),
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.FRIEND, friendId = rahul, amountPaise = 30_000L, rawLabel = "Rahul"),
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.FRIEND, friendId = priya, amountPaise = 30_000L, rawLabel = "Priya")
            )
        )
        val (_, ledger) = replay(listOf(split), setOf(rahul))
        assertEquals(listOf("balance:11:$rahul:30000"), ledger.calls)
    }

    @Test
    fun `an empty friend set does nothing at all`() {
        val source = FakeSource(listOf(purchaseSplitWith(1L, 100L, rahul, 5_000L)))
        val ledger = RecordingLedger()
        runBlocking { LedgerReplayer(source, ledger).replay(emptySet()) }
        // Not merely an optimisation: Room renders an empty list as `IN ()`, which SQLite rejects.
        assertTrue("nothing is read and nothing is deleted", source.events.isEmpty())
        assertTrue(ledger.calls.isEmpty())
    }

    @Test
    fun `the friend set is passed through to the source`() {
        val (source, _) = replay(emptyList(), setOf(rahul, priya))
        assertEquals(setOf(rahul, priya), source.askedFor)
    }

    // --- the delete ------------------------------------------------------------------------------

    @Test
    fun `entries are read before they are cleared`() {
        // The selection asks which transactions still own entries, which the delete then destroys.
        val (source, _) = replay(listOf(purchaseSplitWith(1L, 100L, rahul, 5_000L)), setOf(rahul))
        assertEquals(listOf("openings", "read", "clear"), source.events)
    }

    // --- the worked example ------------------------------------------------------------------------

    /**
     * Section 7.2 of docs/chapters-design.md. The dinner has been tagged into a chapter, so it is
     * gone from the selection; the repayment that settled against it is all that is left.
     *
     * Live, the repayment had found the dinner's +500 and settled it. Replayed, it finds nothing --
     * and records -500 instead, which is what keeps the friend balance at zero once the chapter adds
     * its own +500 back on top.
     */
    @Test
    fun `a repayment whose purchase has been tagged away still posts its full effect`() {
        val repayment = tx(
            id = 42L,
            dateEpoch = 500L,
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.ME,
            amountPaise = 50_000L
        )
        val (_, ledger) = replay(listOf(repayment), setOf(rahul))
        assertEquals(listOf("repayment:42:$rahul:50000"), ledger.calls)
    }

    /** A purchase and the partial repayment that followed it, in the order they happened. */
    @Test
    fun `a purchase and a later partial repayment replay in date order`() {
        val purchase = purchaseSplitWith(id = 1L, dateEpoch = 100L, friendId = rahul, eachPaise = 50_000L)
        val repayment = tx(
            id = 2L,
            dateEpoch = 200L,
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.ME,
            amountPaise = 20_000L
        )
        val (_, ledger) = replay(listOf(repayment, purchase), setOf(rahul))
        assertEquals(
            listOf("balance:1:$rahul:50000", "repayment:2:$rahul:20000"),
            ledger.calls
        )
    }

    // --- checkpoints (docs/declarations-design.md D7, D8) ---------------------------------------------

    private fun opening(friendId: Long, asOfEpoch: Long, amountPaise: Long) =
        Opening(declarationId = "d$friendId", friendId = friendId, asOfEpoch = asOfEpoch, amountPaise = amountPaise)

    @Test
    fun `an opening is posted before every row`() {
        val purchase = purchaseSplitWith(id = 1L, dateEpoch = 300L, friendId = rahul, eachPaise = 1_000L)
        val (_, ledger) = replay(listOf(purchase), setOf(rahul), mapOf(rahul to opening(rahul, 200L, 80_000L)))
        assertEquals(listOf("opening:d$rahul:$rahul:80000", "balance:1:$rahul:1000"), ledger.calls)
    }

    @Test
    fun `rows at or before a checkpoint post nothing for its friend`() {
        val rows = listOf(
            purchaseSplitWith(id = 1L, dateEpoch = 100L, friendId = rahul, eachPaise = 1_000L),
            purchaseSplitWith(id = 2L, dateEpoch = 200L, friendId = rahul, eachPaise = 2_000L),
            purchaseSplitWith(id = 3L, dateEpoch = 201L, friendId = rahul, eachPaise = 4_000L)
        )
        val (_, ledger) = replay(rows, setOf(rahul), mapOf(rahul to opening(rahul, 200L, 0L)))
        // The checkpoint speaks for the instant it names, so a row at exactly that instant is covered.
        assertEquals(listOf("opening:d$rahul:$rahul:0", "balance:3:$rahul:4000"), ledger.calls)
    }

    @Test
    fun `a checkpoint seals a row only for its own friend`() {
        val id = 11L
        val split = tx(
            id = id,
            dateEpoch = 100L,
            payerType = ActorType.ME,
            payeeType = ActorType.MERCHANT,
            amountPaise = 90_000L,
            shares = listOf(
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.ME, amountPaise = 30_000L),
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.FRIEND, friendId = rahul, amountPaise = 30_000L, rawLabel = "Rahul"),
                TransactionShare(transactionId = id, side = "PAYER", participantType = ActorType.FRIEND, friendId = priya, amountPaise = 30_000L, rawLabel = "Priya")
            )
        )
        val (_, ledger) = replay(listOf(split), setOf(rahul, priya), mapOf(rahul to opening(rahul, 150L, 30_000L)))
        assertEquals(listOf("opening:d$rahul:$rahul:30000", "balance:11:$priya:30000"), ledger.calls)
    }

    @Test
    fun `rows are read from the earliest checkpoint on when every friend has one`() {
        val (source, _) = replay(
            emptyList(),
            setOf(rahul, priya),
            mapOf(rahul to opening(rahul, 500L, 0L), priya to opening(priya, 300L, 0L))
        )
        assertEquals(300L, source.askedAfter)
    }

    @Test
    fun `every row is read when any friend has no checkpoint`() {
        val (source, _) = replay(emptyList(), setOf(rahul, priya), mapOf(rahul to opening(rahul, 500L, 0L)))
        assertEquals(null, source.askedAfter)
    }

    @Test
    fun `openings are posted in friend order`() {
        val (_, ledger) = replay(
            emptyList(),
            setOf(priya, rahul),
            mapOf(priya to opening(priya, 1L, 200L), rahul to opening(rahul, 1L, 100L))
        )
        assertEquals(listOf("opening:d$rahul:$rahul:100", "opening:d$priya:$priya:200"), ledger.calls)
    }
}
