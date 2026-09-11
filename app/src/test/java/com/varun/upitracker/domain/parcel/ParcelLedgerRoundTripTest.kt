package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money test.
 *
 * For each shape a parcel can carry, this posts the writer's own transaction and the version their
 * friend ends up with, and asserts the two ledgers are exact mirrors: what A records as "Bob owes
 * me 100" B must record as "I owe A 100", to the paisa. A flip that loses a share row, or that
 * gives a stranger an id, shows up here as a ledger that does not balance.
 *
 * A (writer) knows B as friend 7 and Charlie as friend 9. B knows A as friend 3.
 */
class ParcelLedgerRoundTripTest {

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

    private val bInAsBook = 7L
    private val charlieInAsBook = 9L
    private val aInBsBook = 3L
    private val service = LedgerPostingService()

    private fun post(tx: Transaction, shares: List<TransactionShare>): List<String> {
        val ledger = RecordingLedger()
        runBlocking {
            service.postLedger(
                ledger, tx.id, tx.payerActorRef(), tx.payeeActorRef(),
                shares, tx.amountPaise, tx.ledgerEffect
            )
        }
        return ledger.calls
    }

    /** What the writer's row becomes once it has been through the wire and back. */
    private fun asImported(tx: Transaction, shares: List<TransactionShare>): LocalParcelRow {
        val row = ParcelPerspective.flipForRecipient(
            transaction = tx,
            shares = shares,
            recipientFriendId = bInAsBook,
            friendName = { mapOf(bInAsBook to "Bob", charlieInAsBook to "Charlie")[it] },
            merchantName = { "Swiggy" }
        )
        val decoded = ParcelCodec.decode(ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "tok", listOf(row))))
        assertTrue("parcel did not survive the wire: $decoded", decoded is ParcelDecodeResult.Ok)
        return ParcelPerspective.toLocal(
            row = (decoded as ParcelDecodeResult.Ok).parcel.transactions.single(),
            senderFriendId = aInBsBook,
            originToken = "tok",
            mapPerson = { null },
            resolveShop = { null },
            carryUpiRefId = true
        )
    }

    /** Asserts both sides agree, and that the imported row is one the app can actually review. */
    private fun assertMirrors(
        writerCalls: List<String>,
        readerCalls: List<String>,
        imported: LocalParcelRow
    ) {
        assertEquals(writerCalls.size, readerCalls.size)
        assertTrue(
            "shares do not add up on the imported row",
            PendingReviewRules.sharesAreValid(imported.transaction, imported.shares)
        )
    }

    private fun tx(
        amountPaise: Long = 30000L,
        payerActorType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payerMerchantId: Long? = null,
        payeeActorType: String = ActorType.FRIEND,
        payeeFriendId: Long? = bInAsBook,
        payeeMerchantId: Long? = null,
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT
    ) = Transaction(
        id = 41L,
        amountPaise = amountPaise,
        payerActorType = payerActorType,
        payerFriendId = payerFriendId,
        payerMerchantId = payerMerchantId,
        payerRawLabel = if (payerActorType == ActorType.MERCHANT) "Swiggy" else null,
        payeeActorType = payeeActorType,
        payeeFriendId = payeeFriendId,
        payeeMerchantId = payeeMerchantId,
        payeeRawLabel = if (payeeActorType == ActorType.MERCHANT) "Swiggy" else null,
        reason = "Dinner",
        dateEpoch = 1_700_000_000_000L,
        source = "MANUAL",
        ledgerEffect = ledgerEffect
    )

    private fun share(side: String, participantType: String, amountPaise: Long, friendId: Long? = null) =
        TransactionShare(
            transactionId = 41L, side = side, participantType = participantType,
            friendId = friendId, amountPaise = amountPaise
        )

    // (i) A paid a shop 300, split with B 200/100.
    @Test
    fun `a bill A paid and split with B lands as B owing A their share`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L)
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 10000L, bInAsBook)
        )
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("balance:$bInAsBook:10000"), writer)
        assertEquals(listOf("balance:$aInBsBook:-10000"), reader)
        assertMirrors(writer, reader, imported)
        assertTrue(PendingReviewRules.canAutoReview(imported.transaction))
    }

    // (ii) The same bill, split three ways with Charlie.
    @Test
    fun `a third party in the split moves nothing in B's ledger`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L)
        val shares = listOf(
            share("PAYER", ActorType.ME, 10000L),
            share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
            share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
        )
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("balance:$bInAsBook:10000", "balance:$charlieInAsBook:10000"), writer)
        // Charlie owes A, not B. B's ledger names only A.
        assertEquals(listOf("balance:$aInBsBook:-10000"), reader)
        assertTrue(PendingReviewRules.sharesAreValid(imported.transaction, imported.shares))
        assertTrue(PendingReviewRules.canAutoReview(imported.transaction))
    }

    // (iii) A paid B 500 directly.
    @Test
    fun `money A sent B reads as a repayment on B's side`() {
        val source = tx(amountPaise = 50000L)
        val writer = post(source, emptyList())
        val imported = asImported(source, emptyList())
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("settlement:$bInAsBook:50000"), writer)
        assertEquals(listOf("repayment:$aInBsBook:50000"), reader)
        // Deliberately not auto-reviewable: only B can say whether this settled a debt or was a gift.
        assertFalse(PendingReviewRules.canAutoReview(imported.transaction))
    }

    // (iv) B paid A 500 directly.
    @Test
    fun `money B sent A reads as an outgoing settlement on B's side`() {
        val source = tx(
            amountPaise = 50000L,
            payerActorType = ActorType.FRIEND, payerFriendId = bInAsBook,
            payeeActorType = ActorType.ME, payeeFriendId = null
        )
        val writer = post(source, emptyList())
        val imported = asImported(source, emptyList())
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("repayment:$bInAsBook:50000"), writer)
        assertEquals(listOf("settlement:$aInBsBook:50000"), reader)
        assertTrue(PendingReviewRules.canAutoReview(imported.transaction))
    }

    // (v) A gift, in both directions.
    @Test
    fun `a gift moves no money on either side`() {
        listOf(
            tx(amountPaise = 50000L, ledgerEffect = LedgerEffect.NONE),
            tx(
                amountPaise = 50000L, ledgerEffect = LedgerEffect.NONE,
                payerActorType = ActorType.FRIEND, payerFriendId = bInAsBook,
                payeeActorType = ActorType.ME, payeeFriendId = null
            )
        ).forEach { source ->
            val imported = asImported(source, emptyList())
            assertTrue(post(source, emptyList()).isEmpty())
            assertTrue(post(imported.transaction, imported.shares).isEmpty())
            assertEquals(LedgerEffect.NONE, imported.transaction.ledgerEffect)
        }
    }

    // (vi) A shop refunded A, and B is owed part of it.
    @Test
    fun `a refund A received on B's behalf lands as A owing B`() {
        val source = tx(
            payerActorType = ActorType.MERCHANT, payerFriendId = null, payerMerchantId = 11L,
            payeeActorType = ActorType.ME, payeeFriendId = null
        )
        val shares = listOf(
            share("PAYEE", ActorType.ME, 20000L),
            share("PAYEE", ActorType.FRIEND, 10000L, bInAsBook)
        )
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("balance:$bInAsBook:-10000"), writer)
        assertEquals(listOf("balance:$aInBsBook:10000"), reader)
        assertMirrors(writer, reader, imported)
    }

    @Test
    fun `dropping the third party's share would break the row, which is why it is kept`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L)
        val shares = listOf(
            share("PAYER", ActorType.ME, 10000L),
            share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
            share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
        )
        val imported = asImported(source, shares)
        val withoutCharlie = imported.shares.filterNot { it.rawLabel == "Charlie" }
        assertFalse(PendingReviewRules.sharesAreValid(imported.transaction, withoutCharlie))
    }

    @Test
    fun `giving a stranger an id would post a debt B does not owe`() {
        // The payee-side split that makes the unmapped default load-bearing: A paid B 300, of
        // which 100 was really Charlie's.
        val source = tx()
        val shares = listOf(
            share("PAYEE", ActorType.FRIEND, 20000L, bInAsBook),
            share("PAYEE", ActorType.FRIEND, 10000L, charlieInAsBook)
        )
        val imported = asImported(source, shares)
        // B owes A their own 200, and nothing at all to Charlie -- that part was A's to collect.
        assertEquals(listOf("balance:$aInBsBook:-20000"), post(imported.transaction, imported.shares))

        val ifResolved = imported.shares.map {
            if (it.rawLabel == "Charlie") it.copy(friendId = 55L) else it
        }
        assertTrue(
            "resolving a name by string match books a debt to a stranger",
            post(imported.transaction, ifResolved).any { it == "balance:55:-10000" }
        )
    }
}
