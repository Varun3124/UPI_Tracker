package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a chapter refuses to take, and what it quietly accepts. */
class ChapterEligibilityTest {

    private val rahul = 1L
    private val priya = 2L

    private val goa = Chapter(id = 10L, name = "Goa trip", createdEpoch = 0L)
    private val closed = goa.copy(id = 11L, name = "Ski trip", state = ChapterState.CLOSED)

    private fun tx(
        payerType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payerRawLabel: String? = null,
        payeeType: String = ActorType.MERCHANT,
        payeeFriendId: Long? = null,
        payeeRawLabel: String? = null,
        refundsTransactionId: Long? = null,
        chapterId: Long? = null,
        isPending: Boolean = false,
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT
    ) = Transaction(
        id = 1L,
        amountPaise = 60_000L,
        payerActorType = payerType,
        payerFriendId = payerFriendId,
        payerRawLabel = payerRawLabel,
        payeeActorType = payeeType,
        payeeFriendId = payeeFriendId,
        payeeRawLabel = payeeRawLabel,
        dateEpoch = 0L,
        source = "MANUAL",
        isPending = isPending,
        refundsTransactionId = refundsTransactionId,
        ledgerEffect = ledgerEffect,
        chapterId = chapterId
    )

    private fun share(side: String?, friendId: Long?, rawLabel: String? = null) = TransactionShare(
        transactionId = 1L,
        side = side,
        participantType = ActorType.FRIEND,
        friendId = friendId,
        amountPaise = 30_000L,
        rawLabel = rawLabel
    )

    private fun reason(
        tx: Transaction,
        shares: List<TransactionShare> = emptyList(),
        chapter: Chapter = goa,
        originalOfRefund: Transaction? = null
    ) = ChapterEligibility.blockedReason(tx, shares, chapter, originalOfRefund)

    // --- refusals -------------------------------------------------------------------------------

    @Test
    fun `a closed chapter takes nothing`() {
        assertEquals(
            "Chapter is closed",
            reason(tx(payerType = ActorType.ME, payeeType = ActorType.FRIEND, payeeFriendId = rahul), chapter = closed)
        )
    }

    @Test
    fun `money between your own accounts is not a chapter's business`() {
        assertEquals(
            "Between your own accounts",
            reason(tx(payerType = ActorType.ME, payeeType = ActorType.ME))
        )
    }

    @Test
    fun `someone whose name was never mapped has to be mapped first`() {
        assertEquals(
            "Pick who ‘Ali’ is first",
            reason(tx(payerType = ActorType.FRIEND, payerFriendId = null, payerRawLabel = "Ali"))
        )
        assertEquals(
            "Pick who ‘Ali’ is first",
            reason(
                tx(payerType = ActorType.ME, payeeType = ActorType.MERCHANT),
                shares = listOf(share("PAYER", friendId = null, rawLabel = "Ali"))
            )
        )
    }

    @Test
    fun `an unmapped person with no name at all still gets a usable reason`() {
        assertEquals(
            "Pick who this person is first",
            reason(tx(payerType = ActorType.FRIEND, payerFriendId = null, payerRawLabel = null))
        )
    }

    @Test
    fun `a refund cannot be separated from the purchase it reverses`() {
        val refund = tx(
            payerType = ActorType.MERCHANT,
            payeeType = ActorType.ME,
            refundsTransactionId = 99L
        )
        // Original in no chapter at all.
        assertEquals(
            "Follows its original purchase",
            reason(refund, originalOfRefund = tx(chapterId = null))
        )
        // Original in a different chapter.
        assertEquals(
            "Follows its original purchase",
            reason(refund, originalOfRefund = tx(chapterId = 99L))
        )
        // Original already here: fine.
        assertNull(reason(refund, originalOfRefund = tx(chapterId = goa.id)))
    }

    // --- what is allowed -------------------------------------------------------------------------

    @Test
    fun `a split with everyone identified is fine`() {
        assertNull(
            reason(
                tx(payerType = ActorType.ME, payeeType = ActorType.MERCHANT),
                shares = listOf(share("PAYER", rahul), share("PAYER", priya))
            )
        )
    }

    /** Decision 1: a chapter is a record of a trip, not only of its debts. */
    @Test
    fun `a solo transaction with no friend in it is allowed`() {
        assertNull(reason(tx(payerType = ActorType.ME, payeeType = ActorType.MERCHANT)))
    }

    @Test
    fun `a pending transaction is allowed, and counts for nothing until reviewed`() {
        assertNull(
            reason(tx(payerType = ActorType.ME, payeeType = ActorType.FRIEND, payeeFriendId = rahul, isPending = true))
        )
    }

    @Test
    fun `a gift is allowed, and counts for nothing`() {
        assertNull(
            reason(
                tx(
                    payerType = ActorType.ME,
                    payeeType = ActorType.FRIEND,
                    payeeFriendId = rahul,
                    ledgerEffect = LedgerEffect.NONE
                )
            )
        )
    }

    /** A sideless share produces no legs, so it cannot be a reason to refuse the row. */
    @Test
    fun `a legacy sideless share with nobody mapped to it does not block tagging`() {
        assertNull(
            reason(
                tx(payerType = ActorType.ME, payeeType = ActorType.FRIEND, payeeFriendId = rahul),
                shares = listOf(share(side = null, friendId = null, rawLabel = "Ali"))
            )
        )
    }

    // --- missing members --------------------------------------------------------------------------

    @Test
    fun `tagging reports everyone it would have to add`() {
        val split = tx(payerType = ActorType.ME, payeeType = ActorType.MERCHANT)
        val shares = listOf(share("PAYER", rahul), share("PAYER", priya))
        assertEquals(setOf(rahul, priya), ChapterEligibility.missingMembers(split, shares, emptySet()))
        assertEquals(setOf(priya), ChapterEligibility.missingMembers(split, shares, setOf(rahul)))
        assertEquals(emptySet<Long>(), ChapterEligibility.missingMembers(split, shares, setOf(rahul, priya)))
    }

    @Test
    fun `both ends count towards membership, and a sideless share does not`() {
        val between = tx(
            payerType = ActorType.FRIEND,
            payerFriendId = rahul,
            payeeType = ActorType.FRIEND,
            payeeFriendId = priya
        )
        assertEquals(
            setOf(rahul, priya),
            ChapterEligibility.missingMembers(between, emptyList(), emptySet())
        )
        assertEquals(
            emptySet<Long>(),
            ChapterEligibility.missingMembers(
                tx(payerType = ActorType.ME, payeeType = ActorType.MERCHANT),
                listOf(share(side = null, friendId = 55L)),
                emptySet()
            )
        )
    }
}
