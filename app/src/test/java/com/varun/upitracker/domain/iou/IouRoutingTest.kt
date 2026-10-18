package com.varun.upitracker.domain.iou

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.chapter.ChapterEligibility
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterParty
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.declaration.Opening
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ledger.LedgerReplayer
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing a debt through ME, and keeping someone outside a chapter, on the case they exist for:
 * Asha pays a shop 1000 for ME and Bob, half each. Bob and Asha have nothing to do with each other,
 * so Bob should owe ME his 500 and ME owe Asha all 1000.
 */
class IouRoutingTest {

    private val asha = 10L
    private val bob = 11L
    private val payers = IouRecovery.FROM_SECONDARY_PAYERS

    private val ashaPays = ActorRef(ActorType.FRIEND, friendId = asha, rawLabel = "Asha")
    private val shop = ActorRef(ActorType.MERCHANT, merchantId = 50L, rawLabel = "Cafe")

    private val mine = TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.ME, amountPaise = 500L)
    private val bobs = TransactionShare(
        transactionId = 1L, side = "PAYER", participantType = ActorType.FRIEND, friendId = bob, amountPaise = 500L
    )
    private val straight = listOf(mine, bobs)

    /** Bob's debt to the payer -- Asha -- running through ME instead. */
    private val throughMe = listOf(mine, IouLegs.withVia(bobs, owesPayer = true, via = IouParty.Me))

    private fun legs(shares: List<TransactionShare>) =
        IouLegs.legs(ashaPays, shop, shares, 1000L, LedgerEffect.DEBT, payers)

    private fun lines(shares: List<TransactionShare>) = IouLegs.lines(ashaPays, shop, shares, 1000L, payers)

    private fun tx(chapterId: Long? = null) = Transaction(
        id = 1L,
        amountPaise = 1000L,
        payerActorType = ActorType.FRIEND,
        payerFriendId = asha,
        payerRawLabel = "Asha",
        payeeActorType = ActorType.MERCHANT,
        payeeMerchantId = 50L,
        payeeRawLabel = "Cafe",
        dateEpoch = 1_000L,
        source = "MANUAL",
        iouRecovery = payers,
        chapterId = chapterId
    )

    // --- routing ---------------------------------------------------------------------------------

    @Test
    fun straight_bobsDebtIsAshasAndNeverReachesMyBooks() {
        assertEquals(mapOf(asha to -500L), IouLegs.netByFriend(legs(straight)))
    }

    @Test
    fun throughMe_bobOwesMeAndIOweAshaBothShares() {
        assertEquals(mapOf(asha to -1000L, bob to 500L), IouLegs.netByFriend(legs(throughMe)))
    }

    @Test
    fun throughMe_leavesWhatIAmLeftWithUnchanged() {
        // What ME categorises: ME's own 500 either way.
        assertEquals(
            IouLegs.meNet(ashaPays, shop, 1000L, legs(straight)),
            IouLegs.meNet(ashaPays, shop, 1000L, legs(throughMe))
        )
    }

    @Test
    fun straight_aDebtBetweenTwoOthersCanBeRoutedThroughMe() {
        val bobToAsha = lines(straight).single { it.debtor == IouParty.Friend(bob) }
        assertEquals(IouParty.Friend(asha), bobToAsha.creditor)
        assertTrue(bobToAsha.routable)
        assertTrue(bobToAsha.routed.isEmpty())
    }

    @Test
    fun throughMe_linesSayWhoseDebtIsBeingPassedOn() {
        val all = lines(throughMe)
        val bobToMe = all.single { it.debtor == IouParty.Friend(bob) }
        assertEquals(IouParty.Me, bobToMe.creditor)
        assertEquals(500L, bobToMe.amountPaise)
        assertEquals(listOf(IouParty.Friend(asha)), bobToMe.routed.map { it.farEnd })

        // All 1000 owed to Asha, as two lines: my own 500, and the 500 passed on for Bob.
        val meToAsha = all.filter { it.debtor == IouParty.Me && it.creditor == IouParty.Friend(asha) }
        assertEquals(1000L, meToAsha.sumOf { it.amountPaise })
        val passedOn = meToAsha.single { it.routed.isNotEmpty() }
        assertEquals(listOf(500L to IouParty.Friend(bob)), passedOn.routed.map { it.amountPaise to it.farEnd })
        assertTrue(meToAsha.none { it.routable })
    }

    @Test
    fun throughMe_leavingTheRoutedDebtOutDropsBothHalvesAndNothingElse() {
        val leftOut = listOf(mine, IouLegs.withVia(bobs.copy(keepPayerLeg = false), owesPayer = true, via = IouParty.Me))

        assertEquals(mapOf(asha to -500L), IouLegs.netByFriend(legs(leftOut)))
        // My own 500 to Asha is still kept, and shows apart from Bob's that is not.
        val meToAsha = lines(leftOut).filter { it.debtor == IouParty.Me }
        assertEquals(setOf(true to 500L, false to 500L), meToAsha.map { it.kept to it.amountPaise }.toSet())
    }

    // --- keeping someone outside a chapter -------------------------------------------------------

    @Test
    fun outsiders_routingKeepsThemToDealingWithMeAlone() {
        val routed = IouLegs.routeOutsidersThroughMe(ashaPays, shop, straight, 1000L, payers, setOf(IouParty.Friend(asha)))

        assertEquals(throughMe, routed)
        assertEquals(
            setOf(IouParty.Friend(asha)),
            IouLegs.outsidersOwingInside(ashaPays, shop, straight, 1000L, LedgerEffect.DEBT, payers, setOf(IouParty.Friend(asha)))
        )
        assertTrue(
            IouLegs.outsidersOwingInside(ashaPays, shop, throughMe, 1000L, LedgerEffect.DEBT, payers, setOf(IouParty.Friend(asha)))
                .isEmpty()
        )
    }

    @Test
    fun outsiders_aWholeAmountPaidStraightCannotBeRouted() {
        val bobPays = ActorRef(ActorType.FRIEND, friendId = bob, rawLabel = "Bob")
        assertNull(IouLegs.routeOutsidersThroughMe(bobPays, ashaPays, emptyList(), 300L, payers, setOf(IouParty.Friend(asha))))
    }

    @Test
    fun chapter_countsOnlyTheMembersPartOfARowWithSomeoneOutside() {
        val result = ChapterMath.compute(listOf(TaggedTx(tx(chapterId = 3L), throughMe, outsiders = setOf(asha))))

        assertEquals(mapOf(bob to 500L), result.contributions)
        assertFalse(result.nets.containsKey(ChapterParty.Friend(asha)))
    }

    @Test
    fun chapter_eligibilityRefusesAnOutsiderStillOwedByAMember() {
        val names = mapOf(asha to "Asha")
        assertNotNull(ChapterEligibility.outsiderConflict(tx(), straight, setOf(asha)) { names[it] })
        assertNull(ChapterEligibility.outsiderConflict(tx(), throughMe, setOf(asha)) { names[it] })
    }

    @Test
    fun chapter_anOutsiderIsNeverAMissingMember() {
        assertEquals(setOf(bob), ChapterEligibility.missingMembers(tx(), throughMe, memberIds = emptySet(), outsiders = setOf(asha)))
    }

    @Test
    fun friendsIn_countsSomeoneADebtRunsThrough() {
        val viaSender = listOf(IouLegs.withVia(bobs, owesPayer = true, via = IouParty.Friend(99L)))
        assertTrue(99L in ChapterMath.friendsIn(tx(), viaSender))
    }

    @Test
    fun replay_postsATaggedRowForItsOutsidersAlone() = runBlocking {
        val calls = mutableListOf<String>()
        val ledger = object : LedgerPort {
            override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
                calls += "balance:$friendId:$deltaPaise"
            }
            override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
                calls += "repayment:$friendId"
            }
            override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
                calls += "settlement:$friendId"
            }
            override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) = Unit
        }
        val source = object : LedgerReplayer.Source {
            override suspend fun openingsFor(friendIds: Set<Long>): Map<Long, Opening> = emptyMap()
            override suspend fun rowsFor(friendIds: Set<Long>, afterEpoch: Long?) =
                listOf(TaggedTx(tx(chapterId = 3L), throughMe, outsiders = setOf(asha)))
            override suspend fun clearEntriesFor(friendIds: Set<Long>) = Unit
        }

        LedgerReplayer(source, ledger).replay(setOf(asha, bob))

        // Bob's 500 is the chapter's; only what ME owes Asha is the base ledger's.
        assertEquals(listOf("balance:$asha:-1000"), calls)
    }

    @Test
    fun sanity_theRowIsShapedAsDescribed() {
        assertEquals(ashaPays.friendId, tx().payerActorRef().friendId)
        assertEquals(ActorType.MERCHANT, tx().payeeActorRef().actorType)
        assertTrue(Chapter(name = "Goa", createdEpoch = 0L).isOwn)
    }
}
