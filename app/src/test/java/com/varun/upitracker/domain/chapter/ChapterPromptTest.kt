package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a chapter should be offered, pre-selected, or left well alone. */
class ChapterPromptTest {

    private val rahul = 1L
    private val priya = 2L
    private val dan = 3L

    private fun option(
        id: Long = 10L,
        name: String = "Goa trip",
        members: Set<Long>,
        state: ChapterState = ChapterState.OPEN,
        settled: Boolean = false,
        isActive: Boolean = false
    ) = ChapterOption(
        chapter = Chapter(id = id, name = name, createdEpoch = 0L, state = state, isActive = isActive),
        memberIds = members,
        settled = settled
    )

    private fun tx(
        payerType: String = ActorType.ME,
        payerFriendId: Long? = null,
        payeeType: String = ActorType.FRIEND,
        payeeFriendId: Long? = rahul,
        amountPaise: Long = 50_000L,
        chapterId: Long? = null,
        ledgerEffect: LedgerEffect = LedgerEffect.DEBT
    ) = Transaction(
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
        chapterId = chapterId
    )

    private fun share(friendId: Long) = TransactionShare(
        transactionId = 1L,
        side = "PAYER",
        participantType = ActorType.FRIEND,
        friendId = friendId,
        amountPaise = 25_000L,
        rawLabel = "Friend $friendId"
    )

    // --- what counts as a settle-up ----------------------------------------------------------------

    @Test
    fun `money straight between ME and a friend is a settle-up`() {
        assertEquals(setOf(rahul), ChapterPrompt.directPaymentFriends(tx(), emptyList()))
        assertEquals(
            setOf(rahul),
            ChapterPrompt.directPaymentFriends(
                tx(payerType = ActorType.FRIEND, payerFriendId = rahul, payeeType = ActorType.ME, payeeFriendId = null),
                emptyList()
            )
        )
    }

    @Test
    fun `money straight between two friends is a settle-up between both of them`() {
        assertEquals(
            setOf(rahul, priya),
            ChapterPrompt.directPaymentFriends(
                tx(payerType = ActorType.FRIEND, payerFriendId = rahul, payeeFriendId = priya),
                emptyList()
            )
        )
    }

    @Test
    fun `anything split is not a settle-up`() {
        assertNull(ChapterPrompt.directPaymentFriends(tx(), listOf(share(rahul))))
    }

    @Test
    fun `a gift is not a settle-up`() {
        assertNull(ChapterPrompt.directPaymentFriends(tx(ledgerEffect = LedgerEffect.NONE), emptyList()))
    }

    @Test
    fun `money to a shop is not a settle-up`() {
        assertNull(
            ChapterPrompt.directPaymentFriends(
                tx(payeeType = ActorType.MERCHANT, payeeFriendId = null),
                emptyList()
            )
        )
    }

    @Test
    fun `money to someone unidentified is not a settle-up`() {
        assertNull(
            ChapterPrompt.directPaymentFriends(tx(payeeType = ActorType.FRIEND, payeeFriendId = null), emptyList())
        )
    }

    // --- R18 --------------------------------------------------------------------------------------

    @Test
    fun `a chapter both parties are in is offered`() {
        val options = listOf(option(members = setOf(rahul, priya)))
        assertEquals(
            listOf(10L),
            ChapterPrompt.settleUpCandidates(tx(), emptyList(), options).map { it.chapter.id }
        )
    }

    @Test
    fun `a chapter the friend is not in is not offered`() {
        val options = listOf(option(members = setOf(priya, dan)))
        assertTrue(ChapterPrompt.settleUpCandidates(tx(), emptyList(), options).isEmpty())
    }

    @Test
    fun `a friend to friend payment needs both of them in the chapter`() {
        val payment = tx(payerType = ActorType.FRIEND, payerFriendId = rahul, payeeFriendId = priya)
        assertTrue(
            ChapterPrompt.settleUpCandidates(payment, emptyList(), listOf(option(members = setOf(rahul)))).isEmpty()
        )
        assertEquals(
            1,
            ChapterPrompt.settleUpCandidates(payment, emptyList(), listOf(option(members = setOf(rahul, priya)))).size
        )
    }

    @Test
    fun `closed and settled chapters are never offered`() {
        assertTrue(
            ChapterPrompt.settleUpCandidates(
                tx(), emptyList(), listOf(option(members = setOf(rahul), state = ChapterState.CLOSED))
            ).isEmpty()
        )
        assertTrue(
            ChapterPrompt.settleUpCandidates(
                tx(), emptyList(), listOf(option(members = setOf(rahul), settled = true))
            ).isEmpty()
        )
    }

    @Test
    fun `a transaction already in a chapter is not asked about`() {
        assertTrue(
            ChapterPrompt.settleUpCandidates(
                tx(chapterId = 99L), emptyList(), listOf(option(members = setOf(rahul)))
            ).isEmpty()
        )
    }

    // --- R19 --------------------------------------------------------------------------------------

    @Test
    fun `the active chapter is pre-selected when everyone in it is already a member`() {
        val options = listOf(option(members = setOf(rahul), isActive = true))
        assertEquals(10L, ChapterPrompt.preselect(tx(), emptyList(), options, null)?.chapter?.id)
    }

    @Test
    fun `the active chapter is not pre-selected when it would have to add someone`() {
        val split = tx(payeeType = ActorType.MERCHANT, payeeFriendId = null)
        val options = listOf(option(members = setOf(rahul), isActive = true))
        assertNull(ChapterPrompt.preselect(split, listOf(share(priya)), options, null))
    }

    @Test
    fun `nothing is pre-selected when no chapter is active`() {
        assertNull(ChapterPrompt.preselect(tx(), emptyList(), listOf(option(members = setOf(rahul))), null))
    }

    @Test
    fun `a closed chapter is never pre-selected, even when flagged active`() {
        val options = listOf(option(members = setOf(rahul), state = ChapterState.CLOSED, isActive = true))
        assertNull(ChapterPrompt.preselect(tx(), emptyList(), options, null))
    }

    @Test
    fun `an ineligible transaction is not pre-selected`() {
        val transfer = tx(payeeType = ActorType.ME, payeeFriendId = null)
        val options = listOf(option(members = setOf(rahul), isActive = true))
        assertNull(ChapterPrompt.preselect(transfer, emptyList(), options, null))
    }

    // --- the one-tap confirm guard -------------------------------------------------------------------

    @Test
    fun `one-tap confirm refuses anything a chapter would be chosen for`() {
        val active = listOf(option(members = setOf(rahul), isActive = true))
        assertTrue("would be pre-selected", ChapterPrompt.needsEntryScreen(tx(), emptyList(), active, null))

        val offered = listOf(option(members = setOf(rahul)))
        assertTrue("would be asked about", ChapterPrompt.needsEntryScreen(tx(), emptyList(), offered, null))

        assertTrue(
            "already tagged",
            ChapterPrompt.needsEntryScreen(tx(chapterId = 99L), emptyList(), emptyList(), null)
        )
    }

    @Test
    fun `one-tap confirm is left alone when no chapter is involved`() {
        assertFalse(ChapterPrompt.needsEntryScreen(tx(), emptyList(), emptyList(), null))
        // A chapter that does not contain the friend, and is not active, changes nothing.
        assertFalse(
            ChapterPrompt.needsEntryScreen(tx(), emptyList(), listOf(option(members = setOf(priya))), null)
        )
    }
}
