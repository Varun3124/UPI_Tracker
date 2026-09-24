package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money test.
 *
 * For each shape a parcel can carry, this posts the writer's own transaction and the version their
 * friend ends up with, and asserts the two ledgers are exact mirrors: what A records as "Bob owes
 * me 100" B must record as "I owe A 100", to the paisa. A flip that loses a share row, gives a
 * stranger an id, or lands recovering from the other side of the split shows up here as a ledger
 * that does not balance.
 *
 * A (writer) knows B as friend 7, Charlie as friend 9 and Dan as friend 10. B knows A as friend 3.
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
    private val danInAsBook = 10L
    private val aInBsBook = 3L
    private val service = LedgerPostingService()

    private fun post(tx: Transaction, shares: List<TransactionShare>): List<String> {
        val ledger = RecordingLedger()
        runBlocking {
            service.postLedger(
                ledger, tx.id, tx.payerActorRef(), tx.payeeActorRef(),
                shares, tx.amountPaise, tx.ledgerEffect, IouLegs.resolve(tx, shares)
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
            friendName = { mapOf(bInAsBook to "Bob", charlieInAsBook to "Charlie", danInAsBook to "Dan")[it] },
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

    /** What a recording ledger posted against [friendId], signed the way the IOU table is. */
    private fun netFor(calls: List<String>, friendId: Long): Long {
        var net = 0L
        calls.forEach { call ->
            val (kind, id, amount) = call.split(":")
            if (id.toLong() == friendId) net += if (kind == "repayment") -amount.toLong() else amount.toLong()
        }
        return net
    }

    /**
     * Asserts A and B agree about each other to the paisa, and that the imported row is one the app can
     * actually review.
     */
    private fun assertMirrors(
        writerCalls: List<String>,
        readerCalls: List<String>,
        imported: LocalParcelRow
    ) {
        assertEquals(
            "A and B disagree about what B owes A",
            netFor(writerCalls, bInAsBook),
            -netFor(readerCalls, aInBsBook)
        )
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
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT,
        iouRecovery: IouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
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
        ledgerEffect = ledgerEffect,
        iouRecovery = iouRecovery
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
        assertMirrors(writer, reader, imported)
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
        assertMirrors(writer, reader, imported)
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
        assertMirrors(writer, reader, imported)
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

    // (vii) A paid B 300, of which 100 was really Charlie's to borrow.
    private fun paidBForBAndCharlie(recovery: IouRecovery) = tx(iouRecovery = recovery) to listOf(
        share("PAYER", ActorType.ME, 30000L),
        share("PAYEE", ActorType.FRIEND, 20000L, bInAsBook),
        share("PAYEE", ActorType.FRIEND, 10000L, charlieInAsBook)
    )

    @Test
    fun `recovering from co-payees, B owes A their own share and Charlie owes A theirs`() {
        val (source, shares) = paidBForBAndCharlie(IouRecovery.FROM_SECONDARY_PAYEES)
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("balance:$bInAsBook:20000", "balance:$charlieInAsBook:10000"), writer)
        assertEquals(listOf("balance:$aInBsBook:-20000"), reader)
        assertMirrors(writer, reader, imported)
    }

    @Test
    fun `recovering from co-payers, B owes A the lot and the payee split is B's own business`() {
        val (source, shares) = paidBForBAndCharlie(IouRecovery.FROM_SECONDARY_PAYERS)
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertEquals(listOf("balance:$bInAsBook:30000"), writer)
        assertEquals(listOf("balance:$aInBsBook:-30000"), reader)
        assertMirrors(writer, reader, imported)
    }

    @Test
    fun `a name only moves money once B has said who it is`() {
        val (source, shares) = paidBForBAndCharlie(IouRecovery.FROM_SECONDARY_PAYEES)
        val imported = asImported(source, shares)
        assertEquals(listOf("balance:$aInBsBook:-20000"), post(imported.transaction, imported.shares))

        // Once B maps Charlie, B holds Charlie's 100 and owes it on. An automatic name match would have
        // booked that against whichever "Charlie" happened to be in B's list.
        val mapped = imported.shares.map { if (it.rawLabel == "Charlie") it.copy(friendId = 55L) else it }
        assertEquals(
            listOf("balance:$aInBsBook:-20000", "balance:55:-10000"),
            post(imported.transaction, mapped)
        )
    }

    // (viii) A paid B 400 for A and Charlie, and it was for B and Dan: both sides split.
    private fun bothSidesSplit(recovery: IouRecovery) = tx(amountPaise = 40000L, iouRecovery = recovery) to listOf(
        share("PAYER", ActorType.ME, 20000L),
        share("PAYER", ActorType.FRIEND, 20000L, charlieInAsBook),
        share("PAYEE", ActorType.FRIEND, 30000L, bInAsBook),
        share("PAYEE", ActorType.FRIEND, 10000L, danInAsBook)
    )

    @Test
    fun `split on both sides and recovering from co-payers, both books agree`() {
        val (source, shares) = bothSidesSplit(IouRecovery.FROM_SECONDARY_PAYERS)
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        // B owes A their 200 and Charlie theirs; Charlie owes A the 200 A fronted. Dan is B's business.
        assertEquals(listOf("balance:$bInAsBook:20000", "balance:$charlieInAsBook:20000"), writer)
        assertEquals(listOf("balance:$aInBsBook:-20000"), reader)
        assertMirrors(writer, reader, imported)
    }

    @Test
    fun `split on both sides and recovering from co-payees, both books agree`() {
        val (source, shares) = bothSidesSplit(IouRecovery.FROM_SECONDARY_PAYEES)
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        // B owes A their own 300 and Dan owes A 100. Charlie is A's business.
        assertEquals(listOf("balance:$bInAsBook:30000", "balance:$danInAsBook:10000"), writer)
        assertEquals(listOf("balance:$aInBsBook:-30000"), reader)
        assertMirrors(writer, reader, imported)
    }

    // (ix) Charlie paid a shop, and A, B and Charlie split it. A sends it to B and Charlie at once.
    @Test
    fun `a bill a third party paid lands as B owing them, and only once B has linked them`() {
        val aUid = "aUid00000000000000000000000"
        val charlieUid = "charlieUid00000000000000000"
        val charlieInBsBook = 12L

        val source = tx(
            payerActorType = ActorType.FRIEND, payerFriendId = charlieInAsBook,
            payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L
        )
        val shares = listOf(
            share("PAYER", ActorType.ME, 10000L),
            share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
            share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
        )
        // A owes Charlie A's own share, and nothing about B moves in A's ledger.
        assertEquals(listOf("balance:$charlieInAsBook:-10000"), post(source, shares))

        val sent = ParcelPerspective.flipForRecipient(
            transaction = source,
            shares = shares,
            recipientFriendId = bInAsBook,
            friendName = { mapOf(bInAsBook to "Bob", charlieInAsBook to "Charlie")[it] },
            merchantName = { "Swiggy" },
            linkedUidOf = { if (it == charlieInAsBook) charlieUid else null }
        ).copy(sourceId = 0L, shareRef = "shareRef001")
        val body = ParcelFormat.format(Parcel(ParcelFormat.MAILBOX_VERSION, null, listOf(sent)))
        val received = (ParcelFormat.parse(body, ParcelFormat.MAILBOX_VERSION) as ParcelDecodeResult.Ok)
            .parcel.transactions.single()

        fun landed(charlieHere: Long?) = ParcelPerspective.toLocalFromMailbox(
            row = received,
            senderFriendId = aInBsBook,
            senderUid = aUid,
            mapPerson = { null },
            resolveLinked = { uid -> charlieHere.takeIf { uid == charlieUid } },
            resolveShop = { null },
            carryUpiRefId = true
        )

        // B owes Charlie B's share. A's share is A's business with Charlie and never reaches B.
        val linked = landed(charlieHere = charlieInBsBook)
        assertEquals(listOf("balance:$charlieInBsBook:-10000"), post(linked.transaction, linked.shares))
        assertTrue(PendingReviewRules.sharesAreValid(linked.transaction, linked.shares))

        // Unlinked, Charlie is a name: the row still balances, and moves nothing until B says who.
        val unlinked = landed(charlieHere = null)
        assertTrue(post(unlinked.transaction, unlinked.shares).isEmpty())
        assertTrue(PendingReviewRules.sharesAreValid(unlinked.transaction, unlinked.shares))
    }

    // (x) A paid a shop and split it with B, then treated B: B's IOU left out.
    @Test
    fun `an IOU the writer left out is left out of both books`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L)
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 10000L, bInAsBook).copy(keepPayerLeg = false)
        )
        val writer = post(source, shares)
        val imported = asImported(source, shares)
        val reader = post(imported.transaction, imported.shares)

        assertTrue(writer.isEmpty())
        assertTrue(reader.isEmpty())
        assertMirrors(writer, reader, imported)
    }

    // (xi) A paid a shop and split it with Charlie, then pasted it to B, who is in none of it.
    @Test
    fun `a parcel to someone not in it lands with no you in it and moves nothing`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L)
        val shares = listOf(
            share("PAYER", ActorType.ME, 15000L),
            share("PAYER", ActorType.FRIEND, 15000L, charlieInAsBook)
        )
        assertNull(ParcelEligibility.pasteBlockedReason(source))

        val imported = asImported(source, shares)

        assertEquals(ActorType.FRIEND, imported.transaction.payerActorType)
        assertEquals(aInBsBook, imported.transaction.payerFriendId)
        assertTrue(imported.shares.none { it.participantType == ActorType.ME })
        // A's split with Charlie is A's business: B's ledger records nothing about it.
        assertTrue(post(imported.transaction, imported.shares).isEmpty())
        assertTrue(PendingReviewRules.sharesAreValid(imported.transaction, imported.shares))
    }
}
