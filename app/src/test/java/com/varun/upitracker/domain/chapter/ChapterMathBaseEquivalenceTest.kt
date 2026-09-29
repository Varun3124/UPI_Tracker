package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tagging a transaction that only involves ME and one friend must not move that friend's balance.
 *
 * This is the guarantee that makes chapters safe to adopt: the base ledger holds ME-to-friend debts
 * and a chapter holds the same debt as a contribution, so for any shape the base ledger can already
 * express, the two have to agree to the paisa. Where they disagree, tagging silently rewrites a
 * balance the user never touched.
 *
 * Rather than restate what the base ledger would post, each case runs the real
 * [LedgerPostingService] into a recorder -- the same trick `ParcelImportRepository.balanceDeltas`
 * uses -- so a change to posting policy breaks this test instead of quietly parting the two.
 */
class ChapterMathBaseEquivalenceTest {

    private val rahul = 7L

    /**
     * What [LedgerPostingService] does to each friend's balance, by the sign
     * [com.varun.upitracker.database.entity.IouEntry.amountPaise] uses: positive means they owe ME.
     */
    private class DeltaRecorder : LedgerPort {
        val deltas = linkedMapOf<Long, Long>()

        override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) =
            add(friendId, deltaPaise)

        /** They paid ME, so what they owe falls. */
        override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) =
            add(friendId, -creditAmountPaise)

        /** ME paid them, so what they owe rises. */
        override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) =
            add(friendId, debitAmountPaise)

        override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) =
            add(friendId, amountPaise)

        private fun add(friendId: Long, deltaPaise: Long) {
            deltas[friendId] = (deltas[friendId] ?: 0L) + deltaPaise
        }
    }

    private val postingService = LedgerPostingService()

    /** Both books, from one transaction, so the two can never be set up differently. */
    private fun assertAgree(tagged: TaggedTx) {
        val tx = tagged.transaction
        val recorder = DeltaRecorder()
        runBlocking {
            postingService.postLedger(
                recorder,
                tx.id,
                tx.payerActorRef(),
                tx.payeeActorRef(),
                tagged.shares,
                tx.amountPaise,
                tx.ledgerEffect,
                IouLegs.resolve(tx, tagged.shares)
            )
        }
        val base = recorder.deltas.filterValues { it != 0L }
        val chapter = ChapterMath.compute(listOf(tagged)).contributions
        assertEquals("the chapter and the base ledger must agree", base, chapter)
    }

    private fun tx(
        payerType: String,
        payerFriendId: Long? = null,
        payeeType: String,
        payeeFriendId: Long? = null,
        amountPaise: Long = 60_000L,
        shares: List<TransactionShare> = emptyList(),
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT
    ) = TaggedTx(
        Transaction(
            id = 1L,
            amountPaise = amountPaise,
            payerActorType = payerType,
            payerFriendId = payerFriendId,
            payerRawLabel = payerFriendId?.let { "Friend $it" },
            payeeActorType = payeeType,
            payeeFriendId = payeeFriendId,
            payeeRawLabel = payeeFriendId?.let { "Friend $it" },
            dateEpoch = 0L,
            source = "MANUAL",
            ledgerEffect = ledgerEffect,
            iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        ),
        shares.map { it.copy(transactionId = 1L) }
    )

    private fun friendShare(side: String?, friendId: Long, amountPaise: Long) = TransactionShare(
        transactionId = 1L,
        side = side,
        participantType = ActorType.FRIEND,
        friendId = friendId,
        amountPaise = amountPaise,
        rawLabel = "Friend $friendId"
    )

    private fun meShare(side: String?, amountPaise: Long) = TransactionShare(
        transactionId = 1L,
        side = side,
        participantType = ActorType.ME,
        amountPaise = amountPaise
    )

    @Test
    fun `ME paid a shop and split it with a friend`() {
        assertAgree(
            tx(
                payerType = ActorType.ME,
                payeeType = ActorType.MERCHANT,
                shares = listOf(meShare("PAYER", 30_000L), friendShare("PAYER", rahul, 30_000L))
            )
        )
    }

    @Test
    fun `a friend paid a shop and split it with ME`() {
        assertAgree(
            tx(
                payerType = ActorType.FRIEND,
                payerFriendId = rahul,
                payeeType = ActorType.MERCHANT,
                shares = listOf(friendShare("PAYER", rahul, 30_000L), meShare("PAYER", 30_000L))
            )
        )
    }

    @Test
    fun `ME paid a friend directly`() {
        assertAgree(tx(payerType = ActorType.ME, payeeType = ActorType.FRIEND, payeeFriendId = rahul))
    }

    @Test
    fun `a friend paid ME directly`() {
        assertAgree(tx(payerType = ActorType.FRIEND, payerFriendId = rahul, payeeType = ActorType.ME))
    }

    @Test
    fun `a split with the friend's IOU left out`() {
        assertAgree(
            tx(
                payerType = ActorType.ME,
                payeeType = ActorType.MERCHANT,
                shares = listOf(
                    meShare("PAYER", 30_000L),
                    friendShare("PAYER", rahul, 30_000L).copy(keepPayerLeg = false, keepPayeeLeg = false)
                )
            )
        )
    }

    @Test
    fun `a gift in either direction`() {
        assertAgree(
            tx(
                payerType = ActorType.ME,
                payeeType = ActorType.FRIEND,
                payeeFriendId = rahul,
                ledgerEffect = LedgerEffect.NONE
            )
        )
        assertAgree(
            tx(
                payerType = ActorType.FRIEND,
                payerFriendId = rahul,
                payeeType = ActorType.ME,
                ledgerEffect = LedgerEffect.NONE
            )
        )
    }

    /**
     * The case the whole `shares.isEmpty()` decision turns on.
     *
     * A share row from before sides existed says nothing about who owes what, so `IouLegs` derives
     * no legs from it and the base ledger posts nothing. A chapter reading "no share carries a side"
     * as a direct payment -- which is how docs/chapters-design.md R13 words it -- would invent a
     * whole-amount leg here and move a balance that tagging should never have touched.
     */
    @Test
    fun `a legacy sideless split posts nothing on either side`() {
        val legacy = tx(
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.ME,
            shares = listOf(friendShare(side = null, friendId = rahul, amountPaise = 60_000L))
        )
        assertEquals(emptyMap<Long, Long>(), ChapterMath.compute(listOf(legacy)).contributions)
        assertAgree(legacy)
    }

    @Test
    fun `the other side of a sided split, where only one side carries rows`() {
        assertAgree(
            tx(
                payerType = ActorType.ME,
                payeeType = ActorType.FRIEND,
                payeeFriendId = rahul,
                shares = listOf(meShare("PAYEE", 20_000L), friendShare("PAYEE", rahul, 40_000L))
            )
        )
    }
}
