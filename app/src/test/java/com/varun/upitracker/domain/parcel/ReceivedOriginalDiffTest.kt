package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.ReceivedOriginal
import com.varun.upitracker.database.entity.ReceivedOriginalShare
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender (friend 3 here) paid 300 for dinner and split it with me, 150 each. That is what
 * arrived; the tests change the row it became and ask whether it still says the same thing.
 */
class ReceivedOriginalDiffTest {

    private val sender = 3L
    private val merchants = mapOf(11L to "Swiggy")

    private val original = ReceivedOriginal(
        transactionId = 41L,
        senderName = "Asha",
        receivedEpoch = 1_700_000_100_000L,
        amountPaise = 30000L,
        dateEpoch = 1_700_000_000_000L,
        payerActorType = ActorType.FRIEND,
        payerFriendId = sender,
        payerRawLabel = "Asha",
        payeeActorType = ActorType.MERCHANT,
        payeeRawLabel = "Swiggy",
        reason = "Dinner",
        ledgerEffect = LedgerEffect.DEBT,
        iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
    )

    private val originalShares = listOf(
        ReceivedOriginalShare(transactionId = 41L, side = "PAYEE", participantType = ActorType.FRIEND, friendId = sender, rawLabel = "Asha", amountPaise = 15000L),
        ReceivedOriginalShare(transactionId = 41L, side = "PAYEE", participantType = ActorType.ME, amountPaise = 15000L)
    )

    /** The row exactly as it landed: pending, with nothing of the receiver's own on it yet. */
    private val landed = Transaction(
        id = 41L,
        amountPaise = 30000L,
        payerActorType = ActorType.FRIEND,
        payerFriendId = sender,
        payeeActorType = ActorType.MERCHANT,
        payeeRawLabel = "Swiggy",
        reason = "Dinner",
        sharedRefId = "tok3n.41",
        dateEpoch = 1_700_000_000_000L,
        source = ParcelPerspective.SOURCE_SHARED_PARCEL,
        isPending = true,
        ledgerEffect = LedgerEffect.DEBT,
        iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
    )

    private val landedShares = listOf(
        TransactionShare(transactionId = 41L, side = "PAYEE", participantType = ActorType.ME, amountPaise = 15000L),
        TransactionShare(transactionId = 41L, side = "PAYEE", participantType = ActorType.FRIEND, friendId = sender, amountPaise = 15000L)
    )

    private fun differs(tx: Transaction = landed, shares: List<TransactionShare> = landedShares) =
        ReceivedOriginalDiff.differs(original, originalShares, tx, shares) { merchants[it] }

    @Test
    fun `the row as it landed says what was sent`() {
        assertFalse(differs())
    }

    @Test
    fun `confirming it with the receiver's own account, chapter and a found shop is not an edit`() {
        val confirmed = landed.copy(isPending = false, myAccountId = "savings", chapterId = 5L, payeeMerchantId = 11L, payeeRawLabel = null)
        assertFalse(differs(confirmed))
    }

    @Test
    fun `ME labelled Me is still me`() {
        val original = original.copy(payerActorType = ActorType.ME, payerFriendId = null, payerRawLabel = null)
        val tx = landed.copy(payerActorType = ActorType.ME, payerFriendId = null, payerRawLabel = "Me")
        assertFalse(ReceivedOriginalDiff.differs(original, originalShares, tx, landedShares) { merchants[it] })
    }

    @Test
    fun `a row that moves nobody's balance keeps no lines, however its legs were left`() {
        val original = original.copy(ledgerEffect = LedgerEffect.NONE)
        val tx = landed.copy(ledgerEffect = LedgerEffect.NONE)
        val cleared = landedShares.map { it.copy(keepPayeeLeg = false, keepPayerLeg = false) }
        assertFalse(ReceivedOriginalDiff.differs(original, originalShares, tx, cleared) { merchants[it] })
    }

    @Test
    fun `a changed amount, date or note is an edit`() {
        assertTrue(differs(landed.copy(amountPaise = 31000L)))
        assertTrue(differs(landed.copy(dateEpoch = landed.dateEpoch + 1)))
        assertTrue(differs(landed.copy(reason = "Lunch")))
        assertFalse(differs(landed.copy(reason = "  Dinner ")))
    }

    @Test
    fun `a different split is an edit`() {
        assertTrue(differs(shares = landedShares.map { it.copy(amountPaise = if (it.participantType == ActorType.ME) 10000L else 20000L) }))
    }

    @Test
    fun `leaving out a debt line is an edit`() {
        assertTrue(differs(shares = landedShares.map { if (it.participantType == ActorType.ME) it.copy(keepPayerLeg = false) else it }))
    }

    @Test
    fun `another shop or another payer is an edit`() {
        assertTrue(differs(landed.copy(payeeRawLabel = "Zomato")))
        assertTrue(differs(landed.copy(payerFriendId = 4L)))
    }

    @Test
    fun `saying who a name in the split is counts as an edit, so going back undoes it`() {
        val namedOnly = originalShares + ReceivedOriginalShare(
            transactionId = 41L, side = "PAYEE", participantType = ActorType.FRIEND, rawLabel = "Neha", amountPaise = 0L
        )
        val mapped = landedShares + TransactionShare(
            transactionId = 41L, side = "PAYEE", participantType = ActorType.FRIEND, friendId = 8L, rawLabel = "Neha", amountPaise = 0L
        )
        assertTrue(ReceivedOriginalDiff.differs(original, namedOnly, landed, mapped) { merchants[it] })
    }

    @Test
    fun `going back lays the original over the row and leaves it pending`() {
        val edited = landed.copy(amountPaise = 99L, isPending = false, myAccountId = "savings", chapterId = 5L, refundsTransactionId = 7L)
        val asSent = original.overlayOn(edited)
        assertEquals(30000L, asSent.amountPaise)
        assertTrue(asSent.isPending)
        assertEquals(null, asSent.myAccountId)
        assertEquals(null, asSent.chapterId)
        assertEquals(null, asSent.refundsTransactionId)
        assertEquals("tok3n.41", asSent.sharedRefId)
        assertFalse(ReceivedOriginalDiff.differs(original, originalShares, asSent, originalShares.map { it.toShare() }) { merchants[it] })
    }
}
