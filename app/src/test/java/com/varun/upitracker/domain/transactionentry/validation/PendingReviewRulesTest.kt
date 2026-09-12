package com.varun.upitracker.domain.transactionentry.validation

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These two rules decide whether a pending transaction can turn into money moving without anyone
 * looking at it. They lived inside an Android object until the parcel importer needed to predict
 * their answer, and were untested the whole time.
 */
class PendingReviewRulesTest {

    private fun tx(
        amountPaise: Long = 30000L,
        payerActorType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payerRawLabel: String? = null,
        payeeActorType: String = ActorType.FRIEND,
        payeeFriendId: Long? = 7L,
        payeeRawLabel: String? = null
    ) = Transaction(
        amountPaise = amountPaise,
        payerActorType = payerActorType,
        payerFriendId = payerFriendId,
        payerRawLabel = payerRawLabel,
        payeeActorType = payeeActorType,
        payeeFriendId = payeeFriendId,
        payeeRawLabel = payeeRawLabel,
        dateEpoch = 1_700_000_000_000L,
        source = "SHARED_PARCEL",
        isPending = true
    )

    private fun share(side: String?, participantType: String, amountPaise: Long, friendId: Long? = null) =
        TransactionShare(
            transactionId = 1L, side = side, participantType = participantType,
            friendId = friendId, amountPaise = amountPaise
        )

    // --- sharesAreValid ------------------------------------------------------------------------

    @Test
    fun `no shares at all is fine`() {
        assertTrue(PendingReviewRules.sharesAreValid(tx(), emptyList()))
    }

    @Test
    fun `a payer side that does not add up to the amount is rejected`() {
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 5000L, 7L)
        )
        assertFalse(PendingReviewRules.sharesAreValid(tx(), shares))
    }

    @Test
    fun `an unidentified participant still counts towards the total`() {
        // The whole reason a parcel keeps a third party's share rather than dropping it.
        val shares = listOf(
            share("PAYER", ActorType.ME, 10000L),
            share("PAYER", ActorType.FRIEND, 10000L, 7L),
            share("PAYER", ActorType.FRIEND, 10000L, friendId = null)
        )
        val merchantPayee = tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeRawLabel = "Swiggy")
        assertTrue(PendingReviewRules.sharesAreValid(merchantPayee, shares))
        assertFalse(PendingReviewRules.sharesAreValid(merchantPayee, shares.dropLast(1)))
    }

    @Test
    fun `a merchant side is exempt from having to add up`() {
        val shares = listOf(
            share("PAYER", ActorType.ME, 20000L),
            share("PAYER", ActorType.FRIEND, 10000L, 7L)
        )
        // Payee is the shop, so only the payer side is checked.
        assertTrue(
            PendingReviewRules.sharesAreValid(
                tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeRawLabel = "Swiggy"),
                shares
            )
        )
    }

    @Test
    fun `legacy shares with no side only have to fit inside the amount`() {
        assertTrue(PendingReviewRules.sharesAreValid(tx(), listOf(share(null, ActorType.ME, 10000L))))
        assertFalse(PendingReviewRules.sharesAreValid(tx(), listOf(share(null, ActorType.ME, 40000L))))
    }

    // --- canAutoReview -------------------------------------------------------------------------

    @Test
    fun `money arriving from a friend always waits for the user`() {
        // Nothing in the row says whether this settled a debt or was a gift, and guessing wrong
        // wipes out debt that is still owed.
        assertFalse(
            PendingReviewRules.canAutoReview(
                tx(payerActorType = ActorType.FRIEND, payerFriendId = 7L,
                    payeeActorType = ActorType.ME, payeeFriendId = null)
            )
        )
    }

    @Test
    fun `money going out to a friend does not`() {
        assertTrue(PendingReviewRules.canAutoReview(tx()))
    }

    @Test
    fun `an unknown side is never auto reviewed`() {
        assertFalse(
            PendingReviewRules.canAutoReview(tx(payeeActorType = ActorType.UNKNOWN, payeeFriendId = null))
        )
    }

    @Test
    fun `a shop needs a name but not an id`() {
        // How an imported parcel names a shop this database has never heard of.
        assertTrue(
            PendingReviewRules.canAutoReview(
                tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeRawLabel = "Swiggy")
            )
        )
        assertFalse(
            PendingReviewRules.canAutoReview(
                tx(payeeActorType = ActorType.MERCHANT, payeeFriendId = null, payeeRawLabel = null)
            )
        )
    }

    @Test
    fun `a friend named only by a label is enough`() {
        assertTrue(
            PendingReviewRules.canAutoReview(tx(payeeFriendId = null, payeeRawLabel = "Asha"))
        )
    }
}
