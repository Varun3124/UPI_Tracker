package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bob (friend 7) is the one being sent to; Charlie (friend 9) is someone else in the split. */
class ParcelEligibilityTest {

    private val bob = 7L
    private val charlie = 9L

    private fun tx(
        payerActorType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payeeActorType: String = ActorType.FRIEND,
        payeeFriendId: Long? = bob,
        isPending: Boolean = false,
        sharedRefId: String? = null
    ) = Transaction(
        id = 41L,
        amountPaise = 30000L,
        payerActorType = payerActorType,
        payerFriendId = payerFriendId,
        payeeActorType = payeeActorType,
        payeeFriendId = payeeFriendId,
        dateEpoch = 1_700_000_000_000L,
        source = "MANUAL",
        isPending = isPending,
        sharedRefId = sharedRefId
    )

    private fun share(friendId: Long?, side: String? = "PAYER", participantType: String = ActorType.FRIEND) =
        TransactionShare(
            transactionId = 41L,
            side = side,
            participantType = participantType,
            friendId = friendId,
            amountPaise = 10000L
        )

    private val charliePaidAShop = tx(
        payerActorType = ActorType.FRIEND,
        payerFriendId = charlie,
        payeeActorType = ActorType.MERCHANT,
        payeeFriendId = null
    )

    @Test
    fun `a friend at either end can be sent it`() {
        assertNull(ParcelEligibility.blockedReason(tx(), emptyList(), bob))
        assertNull(
            ParcelEligibility.blockedReason(
                tx(payerActorType = ActorType.FRIEND, payerFriendId = bob,
                    payeeActorType = ActorType.ME, payeeFriendId = null),
                emptyList(),
                bob
            )
        )
    }

    @Test
    fun `a friend only in the split can be sent it, even when you are at neither end`() {
        val shares = listOf(share(null, participantType = ActorType.ME), share(bob), share(charlie))
        assertNull(ParcelEligibility.blockedReason(charliePaidAShop, shares, bob))
    }

    @Test
    fun `a friend who is not in it at all cannot be`() {
        assertEquals("Nothing here to share", ParcelEligibility.blockedReason(charliePaidAShop, listOf(share(charlie)), bob))
        // Paying is no longer enough on its own: the flip would have nobody to make them.
        val youPaidAShop = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null)
        assertEquals("Nothing here to share", ParcelEligibility.blockedReason(youPaidAShop, emptyList(), bob))
    }

    @Test
    fun `a sideless legacy share does not put them in it`() {
        assertEquals(
            "Nothing here to share",
            ParcelEligibility.blockedReason(charliePaidAShop, listOf(share(bob, side = null)), bob)
        )
    }

    @Test
    fun `pending, imported, half-known and own-account rows stay blocked`() {
        assertEquals("Not reviewed yet", ParcelEligibility.blockedReason(tx(isPending = true), emptyList(), bob))
        assertEquals("Came from a parcel", ParcelEligibility.blockedReason(tx(sharedRefId = "tok.1"), emptyList(), bob))
        assertEquals(
            "Needs a payer and payee",
            ParcelEligibility.blockedReason(tx(payeeActorType = ActorType.UNKNOWN, payeeFriendId = null), listOf(share(bob)), bob)
        )
        assertEquals(
            "Between your own accounts",
            ParcelEligibility.blockedReason(tx(payeeActorType = ActorType.ME, payeeFriendId = null), listOf(share(bob)), bob)
        )
    }

    @Test
    fun `a pasted parcel can go to anyone, in it or not`() {
        assertNull(ParcelEligibility.pasteBlockedReason(charliePaidAShop))
        assertNull(ParcelEligibility.pasteBlockedReason(tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null)))
        assertNull(ParcelEligibility.pasteBlockedReason(tx()))
    }

    @Test
    fun `a pasted parcel still refuses pending, imported, half-known and own-account rows`() {
        assertEquals("Not reviewed yet", ParcelEligibility.pasteBlockedReason(tx(isPending = true)))
        assertEquals("Came from a parcel", ParcelEligibility.pasteBlockedReason(tx(sharedRefId = "tok.1")))
        assertEquals(
            "Needs a payer and payee",
            ParcelEligibility.pasteBlockedReason(tx(payeeActorType = ActorType.UNKNOWN, payeeFriendId = null))
        )
        assertEquals(
            "Between your own accounts",
            ParcelEligibility.pasteBlockedReason(tx(payeeActorType = ActorType.ME, payeeFriendId = null))
        )
    }
}
