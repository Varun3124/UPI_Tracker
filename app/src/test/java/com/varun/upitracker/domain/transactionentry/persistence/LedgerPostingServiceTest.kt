package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NONE guard is the reason this seam exists: before it, a gift from a friend went down the
 * repayment branch and settled debt that was still genuinely owed.
 */
class LedgerPostingServiceTest {

    private class RecordingLedger : LedgerPort {
        val calls = mutableListOf<String>()
        override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
            calls += "balance:$friendId:$deltaPaise"
        }
        override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
            calls += "repayment:$friendId:$creditAmountPaise"
        }
        override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
            calls += "settlement:$friendId:$debitAmountPaise"
        }
    }

    private val service = LedgerPostingService()
    private val me = ActorRef(ActorType.ME, rawLabel = "Me")
    private val friend = ActorRef(ActorType.FRIEND, friendId = 7L, rawLabel = "Asha")

    private fun post(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare> = emptyList(),
        effect: LedgerEffect
    ): List<String> {
        val ledger = RecordingLedger()
        runBlocking { service.postLedger(ledger, 1L, payer, payee, shares, 30000L, effect) }
        return ledger.calls
    }

    private fun share(side: String, participantType: String, amountPaise: Long, friendId: Long? = null) =
        TransactionShare(
            transactionId = 1L,
            side = side,
            participantType = participantType,
            friendId = friendId,
            amountPaise = amountPaise
        )

    @Test
    fun giftReceived_doesNotSettleDebt() {
        assertTrue(post(friend, me, effect = LedgerEffect.NONE).isEmpty())
    }

    @Test
    fun giftGiven_doesNotCreateDebt() {
        assertTrue(post(me, friend, effect = LedgerEffect.NONE).isEmpty())
    }

    @Test
    fun repayment_stillSettlesWhenEffectIsDebt() {
        assertEquals(listOf("repayment:7:30000"), post(friend, me, effect = LedgerEffect.DEBT))
    }

    @Test
    fun outgoingSettlement_stillPostsWhenEffectIsDebt() {
        assertEquals(listOf("settlement:7:30000"), post(me, friend, effect = LedgerEffect.DEBT))
    }

    @Test
    fun sharedMerchantBill_chargesFriendTheirShare() {
        val merchant = ActorRef(ActorType.MERCHANT, merchantId = 3L, rawLabel = "Swiggy")
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 10000L, friendId = 7L)
        )
        assertEquals(listOf("balance:7:10000"), post(me, merchant, shares, LedgerEffect.DEBT))
    }

    @Test
    fun ledgerNeutralIsHonouredEvenWithShares() {
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 10000L, friendId = 7L)
        )
        assertTrue(post(me, friend, shares, LedgerEffect.NONE).isEmpty())
    }
}
