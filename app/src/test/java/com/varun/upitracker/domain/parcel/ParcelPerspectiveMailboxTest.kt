package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mailbox half of [ParcelPerspectiveTest]. A (the writer) sends to B and to Charlie at once. In
 * A's book B is friend 7 and Charlie friend 9; in B's book A is friend 3 and Charlie, once B has
 * linked them, friend 12.
 */
class ParcelPerspectiveMailboxTest {

    private val bInAsBook = 7L
    private val charlieInAsBook = 9L
    private val aInBsBook = 3L
    private val charlieInBsBook = 12L

    private val aUid = "aUid00000000000000000000000"
    private val bUid = "bUid00000000000000000000000"
    private val charlieUid = "charlieUid00000000000000000"

    private val friendNames = mapOf(bInAsBook to "Bob", charlieInAsBook to "Charlie")

    /** A paid a shop 300 and split it three ways. */
    private val source = Transaction(
        id = 41L,
        amountPaise = 30000L,
        payerActorType = ActorType.ME,
        payeeActorType = ActorType.MERCHANT,
        payeeMerchantId = 11L,
        reason = "Dinner",
        dateEpoch = 1_700_000_000_000L,
        source = "MANUAL"
    )

    private val shares = listOf(
        TransactionShare(transactionId = 41L, side = "PAYER", participantType = ActorType.ME, amountPaise = 10000L),
        TransactionShare(transactionId = 41L, side = "PAYER", participantType = ActorType.FRIEND, friendId = bInAsBook, amountPaise = 10000L),
        TransactionShare(transactionId = 41L, side = "PAYER", participantType = ActorType.FRIEND, friendId = charlieInAsBook, amountPaise = 10000L)
    )

    private fun flip(linkedUidOf: (Long) -> String? = { null }) = ParcelPerspective.flipForRecipient(
        transaction = source,
        shares = shares,
        recipientFriendId = bInAsBook,
        friendName = { friendNames[it] },
        merchantName = { "Swiggy" },
        linkedUidOf = linkedUidOf
    )

    /** Charlie paid a shop, split three ways, as it reaches B. */
    private fun mailboxRow(thirdParty: String = charlieUid) = ParcelTransaction(
        sourceId = 0L,
        dateEpoch = 1_700_000_000_000L,
        amountPaise = 30000L,
        payer = ParcelActor.Linked(thirdParty, "Charlie"),
        payee = ParcelActor.Shop("Swiggy"),
        ledgerEffect = LedgerEffect.DEBT,
        upiRefId = null,
        reason = "Dinner",
        shares = listOf(
            ParcelShare("PAYER", ParcelActor.Sender, 10000L),
            ParcelShare("PAYER", ParcelActor.Me, 10000L),
            ParcelShare("PAYER", ParcelActor.Linked(thirdParty, "Charlie"), 10000L)
        ),
        shareRef = "shareRef001",
        iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
    )

    private fun land(
        row: ParcelTransaction,
        resolveLinked: (String) -> Long? = { null },
        mapPerson: (String) -> Long? = { null }
    ) = ParcelPerspective.toLocalFromMailbox(
        row = row,
        senderFriendId = aInBsBook,
        senderUid = aUid,
        mapPerson = mapPerson,
        resolveLinked = resolveLinked,
        resolveShop = { null },
        carryUpiRefId = true
    )

    @Test
    fun `a co-recipient is named by account and anyone else by name only`() {
        assertEquals(ParcelActor.Person("Charlie"), flip().shares[2].participant)

        val together = flip { if (it == charlieInAsBook) charlieUid else null }
        assertEquals(ParcelActor.Sender, together.payer)
        assertEquals(ParcelActor.Sender, together.shares[0].participant)
        assertEquals(ParcelActor.Me, together.shares[1].participant)
        assertEquals(ParcelActor.Linked(charlieUid, "Charlie"), together.shares[2].participant)
    }

    @Test
    fun `the reader is never named by account, even when the resolver knows them`() {
        val row = flip { friendId -> if (friendId == bInAsBook) bUid else null }
        assertEquals(ParcelActor.Me, row.shares[1].participant)
    }

    @Test
    fun `an account gets an id only through the reader's own links`() {
        val unlinked = land(mailboxRow())
        assertNull(unlinked.transaction.payerFriendId)
        assertEquals("Charlie", unlinked.transaction.payerRawLabel)
        assertNull(unlinked.shares.single { it.rawLabel == "Charlie" }.friendId)

        val linked = land(mailboxRow(), resolveLinked = { uid -> charlieInBsBook.takeIf { uid == charlieUid } })
        assertEquals(charlieInBsBook, linked.transaction.payerFriendId)
        assertEquals(charlieInBsBook, linked.shares.single { it.rawLabel == "Charlie" }.friendId)
    }

    @Test
    fun `an unlinked account can still be mapped by hand, and a link beats a hand mapping`() {
        val byHand = land(mailboxRow(), mapPerson = { name -> 55L.takeIf { name == "Charlie" } })
        assertEquals(55L, byHand.transaction.payerFriendId)

        val both = land(
            mailboxRow(),
            resolveLinked = { charlieInBsBook },
            mapPerson = { 55L }
        )
        assertEquals(charlieInBsBook, both.transaction.payerFriendId)
    }

    @Test
    fun `a mailbox row lands pending, under its verified sender`() {
        val local = land(mailboxRow()).transaction
        assertEquals("mbx:$aUid:shareRef001", local.sharedRefId)
        assertTrue(local.sharedRefId!!.startsWith(ParcelPerspective.mailboxRefPrefixFor(aUid)))
        assertFalse(local.sharedRefId!!.startsWith(ParcelPerspective.mailboxRefPrefixFor("aUid0")))
        assertTrue(local.isPending)
        assertEquals(ParcelPerspective.SOURCE_SHARED_PARCEL, local.source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mailbox row without a share reference cannot land`() {
        land(mailboxRow().copy(shareRef = null))
    }

    @Test
    fun `a parcel naming its own sender or reader as an account is refused`() {
        fun naming(uid: String) = Parcel(ParcelFormat.MAILBOX_VERSION, null, listOf(mailboxRow(thirdParty = uid)))
        assertTrue(ParcelPerspective.linkedUidsAreThirdParties(naming(charlieUid), aUid, bUid))
        assertFalse(ParcelPerspective.linkedUidsAreThirdParties(naming(aUid), aUid, bUid))
        assertFalse(ParcelPerspective.linkedUidsAreThirdParties(naming(bUid), aUid, bUid))
    }

    @Test
    fun `the whole mailbox route survives the flip, the format and the landing`() {
        val sent = flip { if (it == charlieInAsBook) charlieUid else null }
            .copy(sourceId = 0L, shareRef = "shareRef001")
        val body = ParcelFormat.format(Parcel(ParcelFormat.MAILBOX_VERSION, null, listOf(sent)))
        val received = (ParcelFormat.parse(body, ParcelFormat.MAILBOX_VERSION) as ParcelDecodeResult.Ok)
            .parcel.transactions.single()
        assertEquals(sent, received)

        val local = land(received, resolveLinked = { uid -> charlieInBsBook.takeIf { uid == charlieUid } })
        assertEquals(aInBsBook, local.transaction.payerFriendId)
        assertEquals(ActorType.MERCHANT, local.transaction.payeeActorType)
        assertEquals(
            listOf(aInBsBook, null, charlieInBsBook),
            local.shares.map { it.friendId }
        )
        assertTrue(PendingReviewRules.sharesAreValid(local.transaction, local.shares))
        assertEquals(IouRecovery.FROM_SECONDARY_PAYERS, local.transaction.iouRecovery)
    }
}
