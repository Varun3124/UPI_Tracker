package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A (the exporter) writes; B (the importer) reads. In A's database B is friend 7 and a third party
 * Charlie is friend 9; in B's database A is friend 3.
 */
class ParcelPerspectiveTest {

    private val bInAsBook = 7L
    private val charlieInAsBook = 9L
    private val aInBsBook = 3L

    private val friendNames = mapOf(bInAsBook to "Bob", charlieInAsBook to "Charlie")
    private val merchantNames = mapOf(11L to "Swiggy")

    private fun flip(tx: Transaction, shares: List<TransactionShare> = emptyList()) =
        ParcelPerspective.flipForRecipient(
            transaction = tx,
            shares = shares,
            recipientFriendId = bInAsBook,
            friendName = { friendNames[it] },
            merchantName = { merchantNames[it] }
        )

    private fun toLocal(
        row: ParcelTransaction,
        mapPerson: (String) -> Long? = { null },
        resolveShop: (String) -> Long? = { null },
        carryUpiRefId: Boolean = true
    ) = ParcelPerspective.toLocal(row, aInBsBook, "tok3n", mapPerson, resolveShop, carryUpiRefId)

    private fun tx(
        id: Long = 41L,
        amountPaise: Long = 30000L,
        payerActorType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payerMerchantId: Long? = null,
        payeeActorType: String = ActorType.FRIEND,
        payeeFriendId: Long? = bInAsBook,
        payeeMerchantId: Long? = null,
        upiRefId: String? = null,
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT
    ) = Transaction(
        id = id,
        amountPaise = amountPaise,
        payerActorType = payerActorType,
        payerFriendId = payerFriendId,
        payerMerchantId = payerMerchantId,
        payeeActorType = payeeActorType,
        payeeFriendId = payeeFriendId,
        payeeMerchantId = payeeMerchantId,
        reason = "Dinner",
        upiRefId = upiRefId,
        statementRefNo = "000123",
        myAccountId = "account-uuid",
        dateEpoch = 1_700_000_000_000L,
        source = "SMS",
        isPending = false,
        refundsTransactionId = null,
        ledgerEffect = ledgerEffect
    )

    private fun share(side: String, participantType: String, amountPaise: Long, friendId: Long? = null) =
        TransactionShare(
            transactionId = 41L,
            side = side,
            participantType = participantType,
            friendId = friendId,
            amountPaise = amountPaise
        )

    // --- the swap itself ---------------------------------------------------------------------

    @Test
    fun `the writer becomes the sender and the recipient becomes me`() {
        val row = flip(tx())
        assertEquals(ParcelActor.Sender, row.payer)
        assertEquals(ParcelActor.Me, row.payee)
    }

    @Test
    fun `the swap works in the other direction too`() {
        val row = flip(
            tx(payerActorType = ActorType.FRIEND, payerFriendId = bInAsBook,
                payeeActorType = ActorType.ME, payeeFriendId = null)
        )
        assertEquals(ParcelActor.Me, row.payer)
        assertEquals(ParcelActor.Sender, row.payee)
    }

    @Test
    fun `a third party stays a named person and never becomes me or the sender`() {
        val row = flip(
            tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L),
            listOf(
                share("PAYER", ActorType.ME, 10000L),
                share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
                share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
            )
        )
        assertEquals(
            listOf(ParcelActor.Sender, ParcelActor.Me, ParcelActor.Person("Charlie")),
            row.shares.map { it.participant }
        )
        assertEquals(listOf("PAYER", "PAYER", "PAYER"), row.shares.map { it.side })
        assertEquals(30000L, row.shares.sumOf { it.amountPaise })
    }

    @Test
    fun `a merchant keeps its kind and travels by name`() {
        val row = flip(tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L))
        assertEquals(ParcelActor.Shop("Swiggy"), row.payee)
    }

    @Test
    fun `a merchant with no id falls back to its raw label`() {
        val source = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = null)
            .copy(payeeRawLabel = "CORNER STORE")
        assertEquals(ParcelActor.Shop("CORNER STORE"), flip(source).payee)
    }

    @Test
    fun `a share the sender already carried a label for keeps that label`() {
        val row = flip(
            tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L),
            listOf(
                share("PAYER", ActorType.ME, 20000L),
                TransactionShare(
                    transactionId = 41L, side = "PAYER", participantType = ActorType.FRIEND,
                    friendId = null, amountPaise = 10000L, rawLabel = "Deepa"
                )
            )
        )
        assertEquals(ParcelActor.Person("Deepa"), row.shares[1].participant)
    }

    @Test
    fun `a legacy share with no side is dropped rather than guessed at`() {
        val row = flip(tx(), listOf(share(side = "PAYER", ActorType.ME, 30000L).copy(side = null)))
        assertTrue(row.shares.isEmpty())
    }

    // --- what must not travel ----------------------------------------------------------------

    @Test
    fun `the writer's private columns do not leave the device`() {
        val local = toLocal(flip(tx())).transaction
        assertNull(local.myAccountId)
        assertNull(local.statementRefNo)
        assertNull(local.refundsTransactionId)
        assertEquals(0L, local.id)
    }

    @Test
    fun `a shared reference is carried for a direct transfer between the two of them`() {
        assertEquals("UPI123", flip(tx(upiRefId = "UPI123")).upiRefId)
        assertEquals(
            "UPI123",
            flip(
                tx(upiRefId = "UPI123", payerActorType = ActorType.FRIEND, payerFriendId = bInAsBook,
                    payeeActorType = ActorType.ME, payeeFriendId = null)
            ).upiRefId
        )
    }

    @Test
    fun `a reference the reader could never hold is dropped`() {
        // A's payment to a shop, and A's payment to someone else entirely.
        assertNull(
            flip(tx(upiRefId = "UPI123", payeeActorType = ActorType.MERCHANT,
                payeeFriendId = null, payeeMerchantId = 11L)).upiRefId
        )
        assertNull(flip(tx(upiRefId = "UPI123", payeeFriendId = charlieInAsBook)).upiRefId)
    }

    @Test
    fun `the reference is left off when the reader already holds it`() {
        assertNull(toLocal(flip(tx(upiRefId = "UPI123")), carryUpiRefId = false).transaction.upiRefId)
    }

    // --- landing on the reader's side ---------------------------------------------------------

    @Test
    fun `an imported row is pending and marked as coming from a parcel`() {
        val local = toLocal(flip(tx())).transaction
        assertTrue(local.isPending)
        assertEquals("SHARED_PARCEL", local.source)
        assertEquals("tok3n.41", local.sharedRefId)
    }

    @Test
    fun `the sender resolves to the friend the reader picked`() {
        val local = toLocal(flip(tx())).transaction
        assertEquals(ActorType.FRIEND, local.payerActorType)
        assertEquals(aInBsBook, local.payerFriendId)
        assertEquals(ActorType.ME, local.payeeActorType)
    }

    @Test
    fun `an unmapped third party keeps their name, gets no id, and still balances the split`() {
        val row = flip(
            tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L),
            listOf(
                share("PAYER", ActorType.ME, 10000L),
                share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
                share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
            )
        )
        val local = toLocal(row)
        val charlie = local.shares.single { it.rawLabel == "Charlie" }
        assertNull(charlie.friendId)
        assertEquals(ActorType.FRIEND, charlie.participantType)
        assertEquals(
            local.transaction.amountPaise,
            local.shares.filter { it.side == "PAYER" }.sumOf { it.amountPaise }
        )
    }

    @Test
    fun `a name is only given an id when the reader says so`() {
        val row = flip(
            tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L),
            listOf(
                share("PAYER", ActorType.ME, 20000L),
                share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
            )
        )
        assertNull(toLocal(row).shares.single { it.rawLabel == "Charlie" }.friendId)
        assertEquals(
            55L,
            toLocal(row, mapPerson = { name -> 55L.takeIf { name == "Charlie" } })
                .shares.single { it.rawLabel == "Charlie" }.friendId
        )
    }

    @Test
    fun `a shop the reader knows is linked, one they do not keeps its name`() {
        val row = flip(tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L))
        val unknown = toLocal(row).transaction
        assertEquals(ActorType.MERCHANT, unknown.payeeActorType)
        assertNull(unknown.payeeMerchantId)
        assertEquals("Swiggy", unknown.payeeRawLabel)

        val known = toLocal(row, resolveShop = { 88L }).transaction
        assertEquals(88L, known.payeeMerchantId)
    }

    @Test
    fun `a gift stays a gift`() {
        assertEquals(LedgerEffect.NONE, flip(tx(ledgerEffect = LedgerEffect.NONE)).ledgerEffect)
        assertEquals(
            LedgerEffect.NONE,
            toLocal(flip(tx(ledgerEffect = LedgerEffect.NONE))).transaction.ledgerEffect
        )
    }

    @Test
    fun `a whole parcel survives the flip, the wire and the trip back`() {
        val row = flip(
            tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeMerchantId = 11L),
            listOf(
                share("PAYER", ActorType.ME, 10000L),
                share("PAYER", ActorType.FRIEND, 10000L, bInAsBook),
                share("PAYER", ActorType.FRIEND, 10000L, charlieInAsBook)
            )
        )
        val encoded = ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "tok3n", listOf(row)))
        val decoded = (ParcelCodec.decode(encoded) as ParcelDecodeResult.Ok).parcel
        assertEquals(row, decoded.transactions.single())

        val local = toLocal(decoded.transactions.single())
        assertEquals(aInBsBook, local.transaction.payerFriendId)
        assertEquals(ActorType.MERCHANT, local.transaction.payeeActorType)
        assertEquals(30000L, local.shares.sumOf { it.amountPaise })
    }
}
