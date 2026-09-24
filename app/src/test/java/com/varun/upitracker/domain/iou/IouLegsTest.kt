package com.varun.upitracker.domain.iou

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two recovery techniques, straight off the sketch they came from. X is the primary payer and Y
 * the primary payee throughout; P1 and P2 are co-payers, Q1 and Q2 co-payees.
 */
class IouLegsTest {

    private val payers = IouRecovery.FROM_SECONDARY_PAYERS
    private val payees = IouRecovery.FROM_SECONDARY_PAYEES

    private val x = 1L
    private val p1 = 2L
    private val p2 = 3L
    private val y = 4L
    private val q1 = 5L
    private val q2 = 6L

    private val me = ActorRef(ActorType.ME, rawLabel = "Me")
    private val shop = ActorRef(ActorType.MERCHANT, merchantId = 50L, rawLabel = "Swiggy")

    private fun friend(id: Long, name: String = "Friend $id") =
        ActorRef(ActorType.FRIEND, friendId = id, rawLabel = name)

    private fun payer(id: Long?, amountPaise: Long, name: String? = null) = share("PAYER", id, amountPaise, name)
    private fun payee(id: Long?, amountPaise: Long, name: String? = null) = share("PAYEE", id, amountPaise, name)

    private fun share(side: String, id: Long?, amountPaise: Long, name: String?) = TransactionShare(
        transactionId = 1L,
        side = side,
        participantType = ActorType.FRIEND,
        friendId = id,
        amountPaise = amountPaise,
        rawLabel = name
    )

    private fun mePaying(amountPaise: Long) =
        TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.ME, amountPaise = amountPaise)

    private fun mePaid(amountPaise: Long) =
        TransactionShare(transactionId = 1L, side = "PAYEE", participantType = ActorType.ME, amountPaise = amountPaise)

    private fun f(id: Long) = IouParty.Friend(id)

    private fun owes(debtor: IouParty, creditor: IouParty, amountPaise: Long) = IouLeg(debtor, creditor, amountPaise)

    private fun netFor(
        payerActor: ActorRef,
        payeeActor: ActorRef,
        shares: List<TransactionShare>,
        recovery: IouRecovery,
        amountPaise: Long = 30000L
    ): List<Pair<Long, Long>> =
        IouLegs.netByFriend(IouLegs.legs(payerActor, payeeActor, shares, amountPaise, LedgerEffect.DEBT, recovery))
            .toList()

    // --- the sketch ---------------------------------------------------------------------------------

    @Test
    fun `recovering from co-payers, the payee repays everyone who paid and each co-payer repays the payer`() {
        val shares = listOf(payer(x, 30000L), payer(p1, 20000L), payer(p2, 40000L), payee(y, 90000L))
        assertEquals(
            listOf(
                owes(f(y), f(x), 30000L),
                owes(f(y), f(p1), 20000L), owes(f(p1), f(x), 20000L),
                owes(f(y), f(p2), 40000L), owes(f(p2), f(x), 40000L)
            ),
            IouLegs.legs(friend(x), friend(y), shares, 90000L, LedgerEffect.DEBT, payers)
        )
    }

    @Test
    fun `recovering from co-payees, each co-payee owes the payer and the payee passes their share on`() {
        val shares = listOf(payer(x, 90000L), payee(y, 30000L), payee(q1, 20000L), payee(q2, 40000L))
        assertEquals(
            listOf(
                owes(f(y), f(x), 30000L),
                owes(f(y), f(q1), 20000L), owes(f(q1), f(x), 20000L),
                owes(f(y), f(q2), 40000L), owes(f(q2), f(x), 40000L)
            ),
            IouLegs.legs(friend(x), friend(y), shares, 90000L, LedgerEffect.DEBT, payees)
        )
    }

    @Test
    fun `either way the payer is owed the whole amount, the payee owes it, and the rest net to nothing`() {
        val shares = listOf(payer(x, 30000L), payer(p1, 60000L), payee(y, 50000L), payee(q1, 40000L))
        listOf(payers, payees).forEach { recovery ->
            val balances = mutableMapOf<IouParty, Long>()
            IouLegs.legs(friend(x), friend(y), shares, 90000L, LedgerEffect.DEBT, recovery).forEach { leg ->
                balances[leg.creditor] = (balances[leg.creditor] ?: 0L) + leg.amountPaise
                balances[leg.debtor] = (balances[leg.debtor] ?: 0L) - leg.amountPaise
            }
            assertEquals("$recovery", 90000L, balances[f(x)])
            assertEquals("$recovery", -90000L, balances[f(y)])
            assertEquals("$recovery", listOf(0L), (balances - f(x) - f(y)).values.distinct())
        }
    }

    // --- ME in every seat ---------------------------------------------------------------------------

    @Test
    fun `ME paying is owed by the payee and by every co-payer`() {
        val shares = listOf(mePaying(20000L), payer(p1, 10000L), payee(y, 30000L))
        assertEquals(listOf(y to 20000L, p1 to 10000L), netFor(me, friend(y), shares, payers))
    }

    @Test
    fun `ME as a co-payer owes the payer and is owed by the payee`() {
        val shares = listOf(payer(x, 20000L), mePaying(10000L), payee(y, 30000L))
        assertEquals(listOf(y to 10000L, x to -10000L), netFor(friend(x), friend(y), shares, payers))
    }

    @Test
    fun `ME paid owes the payer and every co-payee`() {
        val shares = listOf(payer(x, 30000L), mePaid(20000L), payee(q1, 10000L))
        assertEquals(listOf(x to -20000L, q1 to -10000L), netFor(friend(x), me, shares, payees))
    }

    @Test
    fun `ME as a co-payee is owed by the payee and owes the payer`() {
        val shares = listOf(payer(x, 30000L), payee(y, 20000L), mePaid(10000L))
        assertEquals(listOf(y to 10000L, x to -10000L), netFor(friend(x), friend(y), shares, payees))
    }

    @Test
    fun `a share on the side not being recovered from moves nothing`() {
        val shares = listOf(payer(x, 20000L), mePaying(10000L), payee(y, 30000L))
        assertTrue(netFor(friend(x), friend(y), shares, payees).isEmpty())
    }

    @Test
    fun `ME on both sides is never counted twice`() {
        val shares = listOf(mePaying(30000L), payee(y, 20000L), mePaid(10000L))
        listOf(payers, payees).forEach { recovery ->
            assertEquals("$recovery", listOf(y to 30000L), netFor(me, friend(y), shares, recovery))
        }
    }

    // --- the edges ----------------------------------------------------------------------------------

    @Test
    fun `a shop at either end decides which side is read, whatever was chosen`() {
        val bill = listOf(mePaying(20000L), payer(p1, 10000L))
        assertEquals(listOf(p1 to 10000L), netFor(me, shop, bill, payees))

        val refund = listOf(mePaid(20000L), payee(q1, 10000L))
        assertEquals(listOf(q1 to -10000L), netFor(shop, me, refund, payers))

        assertEquals(payees, IouLegs.effectiveRecovery(shop, me, payers))
        assertEquals(payers, IouLegs.effectiveRecovery(me, shop, payees))
    }

    @Test
    fun `a gift has no legs at all`() {
        val shares = listOf(mePaying(30000L), payee(y, 20000L), payee(q1, 10000L))
        assertTrue(IouLegs.legs(me, friend(y), shares, 30000L, LedgerEffect.NONE, payees).isEmpty())
    }

    @Test
    fun `a side left unsplit stands for its primary holding the whole amount`() {
        // Only the payee side was entered; recovering from the payer side still charges the payee in full.
        val shares = listOf(payee(y, 20000L), payee(q1, 10000L))
        assertEquals(
            listOf(owes(f(y), IouParty.Me, 30000L)),
            IouLegs.legs(me, friend(y), shares, 30000L, LedgerEffect.DEBT, payers)
        )
    }

    @Test
    fun `a legacy split with no sides says nothing`() {
        val shares = listOf(payer(p1, 10000L).copy(side = null))
        assertTrue(IouLegs.legs(me, friend(y), shares, 30000L, LedgerEffect.DEBT, payers).isEmpty())
    }

    @Test
    fun `a row still carrying a name is matched to the end that has since been given an id`() {
        // Imported with Charlie unidentified, then saved: the actor got an id, its share row did not.
        val charlie = friend(9L, "Charlie")
        val shares = listOf(payer(null, 30000L, "Charlie"), mePaid(30000L))
        assertEquals(listOf(9L to -30000L), netFor(charlie, me, shares, payers))
        assertFalse(IouLegs.choiceMatters(charlie, me, shares, LedgerEffect.DEBT))
    }

    // --- choosing a side ----------------------------------------------------------------------------

    @Test
    fun `a new transaction recovers from whichever side is actually split`() {
        val plain = listOf(payer(x, 30000L), payee(y, 30000L))
        val payerSplit = listOf(payer(x, 20000L), payer(p1, 10000L), payee(y, 30000L))
        val payeeSplit = listOf(payer(x, 30000L), payee(y, 20000L), payee(q1, 10000L))
        val bothSplit = payerSplit.take(2) + payeeSplit.drop(1)

        assertEquals(payers, IouLegs.shapeDefault(friend(x), friend(y), plain))
        assertEquals(payers, IouLegs.shapeDefault(friend(x), friend(y), payerSplit))
        assertEquals(payees, IouLegs.shapeDefault(friend(x), friend(y), payeeSplit))
        assertEquals(payers, IouLegs.shapeDefault(friend(x), friend(y), bothSplit))
        assertEquals(payees, IouLegs.shapeDefault(shop, me, listOf(mePaid(30000L))))
    }

    @Test
    fun `a row from before the choice existed keeps the side ME was on`() {
        assertEquals(
            payers,
            IouLegs.fromRole(me, friend(y), listOf(mePaying(30000L), payee(y, 20000L), payee(q1, 10000L)), IouParty.Me)
        )
        assertEquals(
            payees,
            IouLegs.fromRole(friend(x), me, listOf(payer(x, 20000L), payer(p1, 10000L), mePaid(30000L)), IouParty.Me)
        )
        // A primary role wins over a share on the other side.
        assertEquals(
            payees,
            IouLegs.fromRole(friend(x), me, listOf(payer(x, 20000L), mePaying(10000L), mePaid(30000L)), IouParty.Me)
        )
        // Only in the split: the side the share is on, the payer side first.
        assertEquals(
            payers,
            IouLegs.fromRole(
                friend(x), friend(y),
                listOf(payer(x, 20000L), mePaying(10000L), payee(y, 20000L), mePaid(10000L)),
                IouParty.Me
            )
        )
        assertEquals(
            payees,
            IouLegs.fromRole(friend(x), friend(y), listOf(payer(x, 30000L), payee(y, 20000L), mePaid(10000L)), IouParty.Me)
        )
        // Not involved at all: nothing to preserve, so the split's shape decides.
        assertEquals(
            payees,
            IouLegs.fromRole(friend(x), friend(y), listOf(payer(x, 30000L), payee(y, 20000L), payee(q1, 10000L)), IouParty.Me)
        )
        // The same rule works from a friend's seat, which is how an older parcel is read.
        assertEquals(
            payees,
            IouLegs.fromRole(friend(x), friend(y), listOf(payer(x, 30000L), payee(y, 30000L)), f(y))
        )
    }

    @Test
    fun `the choice is only offered when a split makes the two sides differ`() {
        assertFalse(IouLegs.choiceMatters(friend(x), me, listOf(payer(x, 30000L), mePaid(30000L)), LedgerEffect.DEBT))
        assertTrue(
            IouLegs.choiceMatters(friend(x), me, listOf(payer(x, 20000L), payer(p1, 10000L), mePaid(30000L)), LedgerEffect.DEBT)
        )
        assertTrue(
            IouLegs.choiceMatters(friend(x), friend(y), listOf(payer(x, 30000L), payee(y, 20000L), mePaid(10000L)), LedgerEffect.DEBT)
        )
        assertFalse(IouLegs.choiceMatters(me, shop, listOf(mePaying(20000L), payer(p1, 10000L)), LedgerEffect.DEBT))
        assertFalse(
            IouLegs.choiceMatters(friend(x), me, listOf(payer(x, 20000L), payer(p1, 10000L), mePaid(30000L)), LedgerEffect.NONE)
        )
    }

    @Test
    fun `a stored choice is used as it is, and a missing one reads as ME's seat`() {
        val shares = listOf(payer(x, 30000L), mePaid(20000L), payee(q1, 10000L))
        val tx = Transaction(
            amountPaise = 30000L,
            payerActorType = ActorType.FRIEND,
            payerFriendId = x,
            payeeActorType = ActorType.ME,
            dateEpoch = 0L,
            source = "MANUAL",
            iouRecovery = payers
        )
        assertEquals(payers, IouLegs.resolve(tx, shares))
        assertEquals(payees, IouLegs.resolve(tx.copy(iouRecovery = null), shares))
        assertEquals(
            payees,
            IouLegs.resolve(
                tx.copy(payerActorType = ActorType.MERCHANT, payerFriendId = null, payerMerchantId = 50L),
                shares
            )
        )
    }

    // --- choosing which IOUs to keep --------------------------------------------------------------

    @Test
    fun `a leg left out is simply not owed`() {
        val shares = listOf(payer(x, 20000L), payer(p1, 10000L).copy(keepPayerLeg = false), payee(y, 30000L))
        assertEquals(
            listOf(owes(f(y), f(x), 20000L), owes(f(y), f(p1), 10000L)),
            IouLegs.legs(friend(x), friend(y), shares, 30000L, LedgerEffect.DEBT, payers)
        )
    }

    @Test
    fun `a line folds every leg between two people and is kept only when all of them are`() {
        // ME paid Y, split on the payee side with ME: both legs are Y owing ME.
        val shares = listOf(mePaying(30000L), payee(y, 20000L), mePaid(10000L).copy(keepPayeeLeg = false))
        val line = IouLegs.lines(me, friend(y), shares, 30000L, payees).single()

        assertEquals(f(y), line.debtor)
        assertEquals(IouParty.Me, line.creditor)
        assertEquals(30000L, line.amountPaise)
        assertFalse(line.kept)
        assertEquals(listOf(IouLegSource(1, owesPayer = true), IouLegSource(2, owesPayer = false)), line.sources)
    }

    @Test
    fun `a shop is never a line`() {
        assertTrue(IouLegs.lines(me, shop, listOf(mePaying(30000L)), 30000L, payers).isEmpty())
        val split = IouLegs.lines(me, shop, listOf(mePaying(20000L), payer(p1, 10000L)), 30000L, payers)
        assertEquals(listOf(f(p1) to IouParty.Me), split.map { it.debtor to it.creditor })
    }

    @Test
    fun `every line left out means the transaction moves nobody's balance`() {
        val shares = listOf(mePaying(30000L), payee(y, 30000L))
        assertEquals(LedgerEffect.DEBT, IouLegs.ledgerEffect(me, friend(y), shares, 30000L, payers))

        val noneKept = shares.map { it.copy(keepPayeeLeg = false, keepPayerLeg = false) }
        assertEquals(LedgerEffect.NONE, IouLegs.ledgerEffect(me, friend(y), noneKept, 30000L, payers))

        // With no line to leave out there is nothing to switch off.
        assertEquals(LedgerEffect.DEBT, IouLegs.ledgerEffect(me, shop, listOf(mePaying(30000L)), 30000L, payers))
    }

    @Test
    fun `what ME is left with is the money ME moved against the IOUs kept`() {
        fun billNet(shares: List<TransactionShare>) =
            IouLegs.meNet(me, shop, 30000L, IouLegs.legs(me, shop, shares, 30000L, LedgerEffect.DEBT, payers))
        assertEquals(-20000L, billNet(listOf(mePaying(20000L), payer(p1, 10000L))))
        // Leaving the friend's IOU out makes their share ME's spending too.
        assertEquals(-30000L, billNet(listOf(mePaying(20000L), payer(p1, 10000L).copy(keepPayerLeg = false))))

        val loan = listOf(mePaying(30000L), payee(y, 30000L))
        val loanLegs = IouLegs.legs(me, friend(y), loan, 30000L, LedgerEffect.DEBT, payers)
        assertEquals(0L, IouLegs.meNet(me, friend(y), 30000L, loanLegs))
        assertEquals(-30000L, IouLegs.meNet(me, friend(y), 30000L, emptyList()))

        // Only in the split: nothing moved through ME's own account.
        val coPayer = listOf(payer(x, 20000L), mePaying(10000L), payee(y, 30000L))
        val kept = IouLegs.legs(friend(x), friend(y), coPayer, 30000L, LedgerEffect.DEBT, payers)
        assertEquals(0L, IouLegs.meNet(friend(x), friend(y), 30000L, kept))
        assertEquals(
            -10000L,
            IouLegs.meNet(friend(x), friend(y), 30000L, kept.filterNot { it.debtor == f(y) && it.creditor == IouParty.Me })
        )
    }
}
