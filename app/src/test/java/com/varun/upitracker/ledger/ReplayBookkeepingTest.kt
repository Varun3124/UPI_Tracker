package com.varun.upitracker.ledger

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.declaration.Opening
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balances the base ledger ends up with, not just the calls that reach it: the real settle-oldest-
 * first rules ([SettleMath]) run over an [InMemoryLedger].
 *
 * The first four tests pin down why an edit or a delete has to replay. The rest pin down how a
 * checkpoint's opening behaves once it is in the ledger.
 */
class ReplayBookkeepingTest {

    private val rahul = 7L
    private val posting = LedgerPostingService()

    private val dinner = TaggedTx(
        Transaction(
            id = 1L, amountPaise = 100_000L,
            payerActorType = ActorType.ME, payeeActorType = ActorType.MERCHANT, payeeRawLabel = "Cafe",
            dateEpoch = 100L, source = "MANUAL",
            ledgerEffect = LedgerEffect.DEBT, iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        ),
        listOf(
            TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.ME, amountPaise = 50_000L),
            TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.FRIEND, friendId = rahul, amountPaise = 50_000L, rawLabel = "Rahul")
        )
    )

    private fun repayment(id: Long, dateEpoch: Long, amountPaise: Long) = TaggedTx(
        Transaction(
            id = id, amountPaise = amountPaise,
            payerActorType = ActorType.FRIEND, payerFriendId = rahul, payerRawLabel = "Rahul",
            payeeActorType = ActorType.ME,
            dateEpoch = dateEpoch, source = "MANUAL",
            ledgerEffect = LedgerEffect.DEBT, iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        ),
        emptyList()
    )

    /** One phone's rows, openings and ledger. [rows] can change under it, as a delete changes the table. */
    private class Book(initial: List<TaggedTx>, var openings: Map<Long, Opening> = emptyMap()) {
        var rows: List<TaggedTx> = initial
        private val dates = initial.associate { it.transaction.id to it.transaction.dateEpoch }
        val ledger = InMemoryLedger { id -> dates.getValue(id) }
        val replayer = LedgerReplayer(ListReplaySource({ rows }, { openings }, ledger), ledger)
    }

    private fun post(ledger: LedgerPort, tagged: TaggedTx) = runBlocking {
        val tx = tagged.transaction
        posting.postLedger(
            ledger, tx.id, tx.payerActorRef(), tx.payeeActorRef(), tagged.shares,
            tx.amountPaise, tx.ledgerEffect, IouLegs.resolve(tx, tagged.shares)
        )
    }

    // --- why edits and deletes replay --------------------------------------------------------------

    @Test
    fun `re-saving a repayment by deleting and reposting it counts it twice`() {
        val paid = repayment(id = 2L, dateEpoch = 200L, amountPaise = 50_000L)
        val book = Book(listOf(dinner, paid))
        post(book.ledger, dinner)
        post(book.ledger, paid)
        assertEquals("settled up", 0L, book.ledger.net(rahul))

        // What the save path used to do for an edit: its own entries (it has none) out, then post again.
        book.ledger.cascadeDeleteOf(paid.transaction.id)
        post(book.ledger, paid)
        assertEquals("the old path: Rahul is now owed 500 he never lent", -50_000L, book.ledger.net(rahul))
    }

    @Test
    fun `re-saving a repayment by replaying leaves the balance as it was`() {
        val paid = repayment(id = 2L, dateEpoch = 200L, amountPaise = 50_000L)
        val book = Book(listOf(dinner, paid))
        post(book.ledger, dinner)
        post(book.ledger, paid)

        runBlocking { book.replayer.replay(setOf(rahul)) }
        assertEquals(0L, book.ledger.net(rahul))
    }

    @Test
    fun `deleting a repayment on its own leaves what it settled flagged as settled`() {
        val paid = repayment(id = 2L, dateEpoch = 200L, amountPaise = 30_000L)
        val book = Book(listOf(dinner, paid))
        post(book.ledger, dinner)
        post(book.ledger, paid)
        assertEquals(20_000L, book.ledger.net(rahul))

        book.ledger.cascadeDeleteOf(paid.transaction.id)
        book.rows = listOf(dinner)
        assertEquals("the old path: the 300 repaid is still off his debt", 20_000L, book.ledger.net(rahul))

        runBlocking { book.replayer.replay(setOf(rahul)) }
        assertEquals("replayed: he owes the whole 500 again", 50_000L, book.ledger.net(rahul))
    }

    @Test
    fun `replaying reproduces live posting whatever order the rows were saved in`() {
        val rows = listOf(
            dinner,
            repayment(id = 2L, dateEpoch = 200L, amountPaise = 20_000L),
            repayment(id = 3L, dateEpoch = 300L, amountPaise = 45_000L)
        )
        val live = Book(rows)
        rows.reversed().forEach { post(live.ledger, it) }

        val rebuilt = Book(rows)
        runBlocking { rebuilt.replayer.replay(setOf(rahul)) }
        assertEquals(live.ledger.net(rahul), rebuilt.ledger.net(rahul))
        assertEquals(-15_000L, rebuilt.ledger.net(rahul))
    }

    // --- the opening ----------------------------------------------------------------------------------

    @Test
    fun `a repayment after a checkpoint settles the opening before anything else`() {
        val paid = repayment(id = 2L, dateEpoch = 200L, amountPaise = 30_000L)
        // Agreed at 150: Rahul owes 800. The 1 Sep dinner is before it and counts only through that.
        val book = Book(listOf(dinner, paid), mapOf(rahul to Opening("agreed", rahul, 150L, 80_000L)))
        runBlocking { book.replayer.replay(setOf(rahul)) }

        assertEquals(50_000L, book.ledger.net(rahul))
        val residual = book.ledger.all.single { !it.isSettled }
        assertEquals("the residual stays the declaration's, not the repayment's", "agreed", residual.declarationId)
        assertEquals(null, residual.transactionId)
        assertTrue(book.ledger.all.none { it.transactionId == dinner.transaction.id })
    }

    @Test
    fun `a repayment larger than the opening leaves the rest owed the other way`() {
        val paid = repayment(id = 2L, dateEpoch = 200L, amountPaise = 100_000L)
        val book = Book(listOf(paid), mapOf(rahul to Opening("agreed", rahul, 150L, 80_000L)))
        runBlocking { book.replayer.replay(setOf(rahul)) }
        assertEquals(-20_000L, book.ledger.net(rahul))
    }

    @Test
    fun `a row recorded late but dated before the checkpoint moves nothing`() {
        val lateDinner = TaggedTx(dinner.transaction.copy(id = 9L, dateEpoch = 120L), dinner.shares.map { it.copy(transactionId = 9L) })
        val book = Book(listOf(lateDinner), mapOf(rahul to Opening("agreed", rahul, 150L, 80_000L)))
        runBlocking { book.replayer.replay(setOf(rahul)) }
        assertEquals(80_000L, book.ledger.net(rahul))
    }
}
