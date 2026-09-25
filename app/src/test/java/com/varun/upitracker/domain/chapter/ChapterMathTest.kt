package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a chapter works out from its transactions, and the plan it reduces them to.
 *
 * Which legs a split produces is [com.varun.upitracker.domain.iou.IouLegsTest]'s business; these
 * check the netting, the simplification and its tie-breaks. Amounts are rupees times 100 throughout,
 * written as the design brief writes them.
 */
class ChapterMathTest {

    private val rahul = 1L
    private val priya = 2L
    private val dan = 3L

    private fun me() = ChapterParty.Me
    private fun f(id: Long) = ChapterParty.Friend(id)

    private fun pays(debtor: ChapterParty, creditor: ChapterParty, amountPaise: Long) =
        ChapterPayment(debtor, creditor, amountPaise)

    // --- builders -----------------------------------------------------------------------------

    private var nextId = 1L

    private fun tx(
        payerType: String,
        payerFriendId: Long? = null,
        payeeType: String,
        payeeFriendId: Long? = null,
        amountPaise: Long,
        shares: List<TransactionShare> = emptyList(),
        isPending: Boolean = false,
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT,
        dateEpoch: Long = 0L
    ): TaggedTx {
        val id = nextId++
        val transaction = Transaction(
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
            isPending = isPending,
            ledgerEffect = ledgerEffect,
            iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        )
        return TaggedTx(transaction, shares.map { it.copy(transactionId = id) })
    }

    private fun share(side: String, friendId: Long?, amountPaise: Long, isMe: Boolean = false) =
        TransactionShare(
            transactionId = 0L,
            side = side,
            participantType = if (isMe) ActorType.ME else ActorType.FRIEND,
            friendId = friendId,
            amountPaise = amountPaise,
            rawLabel = friendId?.let { "Friend $it" }
        )

    private fun meShare(side: String, amountPaise: Long) = share(side, null, amountPaise, isMe = true)

    /** ME paid a shop and split it with [friendIds], everyone taking an equal share. */
    private fun mePaidSplit(totalPaise: Long, vararg friendIds: Long): TaggedTx {
        val each = totalPaise / (friendIds.size + 1)
        return tx(
            payerType = ActorType.ME,
            payeeType = ActorType.MERCHANT,
            amountPaise = totalPaise,
            shares = listOf(meShare("PAYER", each)) + friendIds.map { share("PAYER", it, each) }
        )
    }

    /** [payerId] paid a shop and split it; ME and [otherIds] each owe them a share. */
    private fun friendPaidSplit(payerId: Long, totalPaise: Long, meIncluded: Boolean, vararg otherIds: Long): TaggedTx {
        val parties = otherIds.size + 1 + if (meIncluded) 1 else 0
        val each = totalPaise / parties
        val shares = mutableListOf(share("PAYER", payerId, each))
        if (meIncluded) shares += meShare("PAYER", each)
        otherIds.forEach { shares += share("PAYER", it, each) }
        return tx(
            payerType = ActorType.FRIEND,
            payerFriendId = payerId,
            payeeType = ActorType.MERCHANT,
            amountPaise = totalPaise,
            shares = shares
        )
    }

    // --- the worked example -------------------------------------------------------------------

    /** Section 4 of docs/chapters-design.md, end to end. */
    @Test
    fun `the Goa trip settles in three payments and Dan ends up owing ME`() {
        val hotel = mePaidSplit(900_000L, rahul, priya)
        val dinner = friendPaidSplit(priya, 300_000L, meIncluded = true, dan)
        val cab = friendPaidSplit(dan, 60_000L, meIncluded = false, rahul)

        val result = ChapterMath.compute(listOf(hotel, dinner, cab))

        assertEquals(
            mapOf(me() to 500_000L, f(rahul) to -330_000L, f(priya) to -100_000L, f(dan) to -70_000L),
            result.nets
        )
        assertEquals(
            listOf(
                pays(f(rahul), me(), 330_000L),
                pays(f(priya), me(), 100_000L),
                pays(f(dan), me(), 70_000L)
            ),
            result.plan
        )
        // Dan never transacted with ME, and now owes ME 700. That is the point of simplification.
        assertEquals(mapOf(rahul to 330_000L, priya to 100_000L, dan to 70_000L), result.contributions)
        assertFalse(result.settled)
    }

    // --- invariants ---------------------------------------------------------------------------

    private fun assertInvariants(result: ChapterResult) {
        assertEquals("nets must sum to zero", 0L, result.nets.values.sum())
        result.nets.forEach { (party, net) ->
            val moved = result.plan.sumOf {
                when (party) {
                    it.creditor -> it.amountPaise
                    it.debtor -> -it.amountPaise
                    else -> 0L
                }
            }
            assertEquals("the plan must preserve $party's net", net, moved)
        }
        assertTrue(
            "at most one payment fewer than there are parties",
            result.plan.size <= maxOf(0, result.nets.size - 1)
        )
        val pairs = result.plan.map { it.debtor to it.creditor }
        assertEquals("no pair is paid twice", pairs.size, pairs.toSet().size)
        assertEquals(
            "ME's contributions must sum to ME's net",
            result.nets[ChapterParty.Me] ?: 0L,
            result.contributions.values.sum()
        )
    }

    @Test
    fun `the worked example holds every invariant`() {
        assertInvariants(
            ChapterMath.compute(
                listOf(
                    mePaidSplit(900_000L, rahul, priya),
                    friendPaidSplit(priya, 300_000L, meIncluded = true, dan),
                    friendPaidSplit(dan, 60_000L, meIncluded = false, rahul)
                )
            )
        )
    }

    @Test
    fun `a tangle of friend to friend debts holds every invariant`() {
        val transactions = listOf(
            friendPaidSplit(rahul, 90_000L, meIncluded = false, priya, dan),
            friendPaidSplit(priya, 60_000L, meIncluded = true, dan),
            mePaidSplit(120_000L, dan),
            friendPaidSplit(dan, 45_000L, meIncluded = false, rahul)
        )
        assertInvariants(ChapterMath.compute(transactions))
    }

    /** The plan has to be a function of the transactions, not of the order they arrived in. */
    @Test
    fun `shuffling the input never changes the result`() {
        val transactions = listOf(
            mePaidSplit(900_000L, rahul, priya),
            friendPaidSplit(priya, 300_000L, meIncluded = true, dan),
            friendPaidSplit(dan, 60_000L, meIncluded = false, rahul),
            friendPaidSplit(rahul, 45_000L, meIncluded = false, priya)
        )
        val expected = ChapterMath.compute(transactions)

        permutations(transactions).forEach { shuffled ->
            val actual = ChapterMath.compute(shuffled)
            assertEquals(expected.nets, actual.nets)
            assertEquals(expected.plan, actual.plan)
            assertEquals(expected.contributions, actual.contributions)
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head ->
            permutations(items - head).map { listOf(head) + it }
        }

    // --- what counts and what does not ---------------------------------------------------------

    @Test
    fun `a pending transaction counts for nothing until it is reviewed`() {
        val result = ChapterMath.compute(
            listOf(mePaidSplit(900_000L, rahul).let { TaggedTx(it.transaction.copy(isPending = true), it.shares) })
        )
        assertTrue(result.nets.isEmpty())
        assertTrue(result.contributions.isEmpty())
        assertEquals(1, result.pendingCount)
        assertFalse("a pending row blocks settled", result.settled)
    }

    @Test
    fun `a gift moves nobody`() {
        val gift = tx(
            payerType = ActorType.ME,
            payeeType = ActorType.FRIEND,
            payeeFriendId = rahul,
            amountPaise = 50_000L,
            ledgerEffect = LedgerEffect.NONE
        )
        val result = ChapterMath.compute(listOf(gift))
        assertTrue(result.nets.isEmpty())
        assertTrue(result.settled)
    }

    /** Decision 1: a solo transaction is taggable, and contributes nothing. */
    @Test
    fun `a solo transaction is recorded but moves no balance`() {
        val solo = tx(
            payerType = ActorType.ME,
            payeeType = ActorType.MERCHANT,
            amountPaise = 25_000L
        )
        val result = ChapterMath.compute(listOf(solo))
        assertTrue(result.nets.isEmpty())
        assertTrue(result.plan.isEmpty())
        assertTrue(result.contributions.isEmpty())
        assertTrue(result.settled)
        assertEquals(0, result.unidentifiedLegCount)
    }

    @Test
    fun `a leg to a shop is spending, not an IOU`() {
        // ME and Rahul split what a shop was paid; the shop is at one end of every candidate leg
        // that names it, and none of those survive.
        val result = ChapterMath.compute(listOf(mePaidSplit(60_000L, rahul)))
        assertEquals(mapOf(me() to 30_000L, f(rahul) to -30_000L), result.nets)
        assertEquals(0, result.unidentifiedLegCount)
    }

    @Test
    fun `an empty chapter is settled`() {
        val result = ChapterMath.compute(emptyList())
        assertTrue(result.nets.isEmpty())
        assertTrue(result.plan.isEmpty())
        assertTrue(result.settled)
    }

    // --- direct payments ------------------------------------------------------------------------

    @Test
    fun `a friend paying ME directly reduces what they owe`() {
        val purchase = mePaidSplit(60_000L, rahul)
        val repayment = tx(
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.ME,
            amountPaise = 30_000L
        )
        val result = ChapterMath.compute(listOf(purchase, repayment))
        assertTrue("the debt is squared", result.nets.isEmpty())
        assertTrue(result.contributions.isEmpty())
        assertTrue(result.settled)
    }

    @Test
    fun `ME paying a friend directly leaves ME owed`() {
        val result = ChapterMath.compute(
            listOf(
                tx(
                    payerType = ActorType.ME,
                    payeeType = ActorType.FRIEND,
                    payeeFriendId = rahul,
                    amountPaise = 30_000L
                )
            )
        )
        assertEquals(mapOf(me() to 30_000L, f(rahul) to -30_000L), result.nets)
        assertEquals(mapOf(rahul to 30_000L), result.contributions)
    }

    /** The genuinely new case: the base ledger has nowhere to put this. */
    @Test
    fun `one friend paying another back reduces that debt`() {
        val cab = friendPaidSplit(dan, 60_000L, meIncluded = false, rahul)
        val payback = tx(
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.FRIEND,
            payeeFriendId = dan,
            amountPaise = 30_000L
        )
        val result = ChapterMath.compute(listOf(cab, payback))
        assertTrue(result.nets.isEmpty())
        assertTrue("nothing reaches ME's balances", result.contributions.isEmpty())
        assertTrue(result.settled)
    }

    /**
     * A legacy sideless split says nothing about who owes what, so it must produce no legs -- the
     * same as the base ledger. See IouLegs.directPaymentParties.
     */
    @Test
    fun `a legacy sideless split moves nobody`() {
        val legacy = tx(
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.ME,
            amountPaise = 30_000L,
            shares = listOf(share(side = "", friendId = rahul, amountPaise = 30_000L).copy(side = null))
        )
        val result = ChapterMath.compute(listOf(legacy))
        assertTrue(result.nets.isEmpty())
        assertTrue(result.contributions.isEmpty())
    }

    // --- tie-breaks ------------------------------------------------------------------------------

    @Test
    fun `the largest debt is settled first`() {
        val result = ChapterMath.compute(
            listOf(
                mePaidSplit(20_000L, rahul),          // ME +100, Rahul -100
                mePaidSplit(40_000L, priya)           // ME +200, Priya -200
            )
        )
        assertEquals(mapOf(me() to 30_000L, f(rahul) to -10_000L, f(priya) to -20_000L), result.nets)
        // Nobody matches ME's 300 exactly, so this is the greedy pass: Priya owes more, and goes first.
        assertEquals(
            listOf(pays(f(priya), me(), 20_000L), pays(f(rahul), me(), 10_000L)),
            result.plan
        )
        assertInvariants(result)
    }

    /** Two pairs that cancel exactly are paired off, rather than one debt being split across both. */
    @Test
    fun `amounts that cancel exactly are paid in one payment each`() {
        val result = ChapterMath.compute(
            listOf(
                mePaidSplit(20_000L, rahul),                                   // ME +100, Rahul -100
                friendPaidSplit(priya, 40_000L, meIncluded = false, dan)       // Priya +200, Dan -200
            )
        )
        assertEquals(
            mapOf(me() to 10_000L, f(rahul) to -10_000L, f(priya) to 20_000L, f(dan) to -20_000L),
            result.nets
        )
        // ME's pair goes first only because both are exact matches and ME breaks the tie.
        assertEquals(
            listOf(pays(f(rahul), me(), 10_000L), pays(f(dan), f(priya), 20_000L)),
            result.plan
        )
        // The friend-to-friend payment never reaches ME's balances.
        assertEquals(mapOf(rahul to 10_000L), result.contributions)
        assertInvariants(result)
    }

    /** With every amount tied, the pairs who actually transacted are the ones paired off. */
    @Test
    fun `a pair that transacted is preferred when amounts tie`() {
        val cab = friendPaidSplit(dan, 20_000L, meIncluded = false, rahul)
        val lunch = mePaidSplit(20_000L, priya)
        val result = ChapterMath.compute(listOf(cab, lunch))

        assertEquals(
            mapOf(me() to 10_000L, f(rahul) to -10_000L, f(priya) to -10_000L, f(dan) to 10_000L),
            result.nets
        )
        // Every one of the four pairings is an exact match at 100. Pairing the two who transacted --
        // rather than Rahul with ME and Priya with Dan -- keeps the plan recognisable. ME's pair is
        // emitted first because a pair involving ME breaks the tie between two transacted pairs.
        assertEquals(
            listOf(pays(f(priya), me(), 10_000L), pays(f(rahul), f(dan), 10_000L)),
            result.plan
        )
        assertInvariants(result)
    }

    @Test
    fun `the plan is at most one payment shorter than the number of parties`() {
        val result = ChapterMath.compute(
            listOf(
                mePaidSplit(900_000L, rahul, priya, dan),
                friendPaidSplit(rahul, 30_000L, meIncluded = false, priya)
            )
        )
        assertInvariants(result)
        assertTrue(result.plan.size <= result.nets.size - 1)
    }
}
