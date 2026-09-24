package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.domain.transactionentry.category.CategorySplitManager
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTargetingTest {

    private val calculator = ShareCalculator()
    private val splitManager = CategorySplitManager()

    /** [meNet] is what the transaction leaves ME up (positive) or down (negative) by. */
    private fun target(
        payer: String,
        payee: String,
        meNet: Long = 0L,
        isLinkedRefund: Boolean = false
    ) = calculator.categoryTargeting(payer, payee, isLinkedRefund, meNet)

    @Test
    fun merchantPurchase_isExpenseOnWhatMeIsLeftDown() {
        val t = target(ActorType.ME, ActorType.MERCHANT, meNet = -60000L)
        assertEquals(60000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun plainLoanToFriend_isNotCategorisable() {
        assertEquals(0L, target(ActorType.ME, ActorType.FRIEND, meNet = 0L).sharePaise)
    }

    @Test
    fun giftGiven_isExpense() {
        val t = target(ActorType.ME, ActorType.FRIEND, meNet = -50000L)
        assertEquals(50000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun giftReceived_isIncome() {
        val t = target(ActorType.FRIEND, ActorType.ME, meNet = 50000L)
        assertEquals(50000L, t.sharePaise)
        assertEquals(CategoryKind.INCOME, t.kind)
    }

    @Test
    fun unlinkedMerchantCredit_isIncome() {
        val t = target(ActorType.MERCHANT, ActorType.ME, meNet = 20000L)
        assertEquals(20000L, t.sharePaise)
        assertEquals(CategoryKind.INCOME, t.kind)
    }

    @Test
    fun linkedRefund_isExpenseDespiteMeBeingOnThePayeeSide() {
        val t = target(ActorType.MERCHANT, ActorType.ME, meNet = 20000L, isLinkedRefund = true)
        assertEquals(20000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun visibility_followsTargeting() {
        val shown = splitManager.visibilityDecision(target(ActorType.ME, ActorType.MERCHANT, meNet = -60000L))
        assertTrue(shown.showCategories)
        assertFalse(shown.shouldClearSelections)
        assertEquals(CategoryKind.EXPENSE, shown.kind)

        val hidden = splitManager.visibilityDecision(target(ActorType.ME, ActorType.FRIEND, meNet = 0L))
        assertFalse(hidden.showCategories)
        assertTrue(hidden.shouldClearSelections)
    }

    /**
     * The bug this guards: kind used to be read off which side carried a non-zero share, but
     * amounts are typed after the actors are picked. A fresh merchant purchase therefore offered
     * INCOME categories until the first keystroke, then swapped them for EXPENSE ones.
     */
    @Test
    fun merchantPurchaseWithNoAmountYet_isStillExpense() {
        val t = target(ActorType.ME, ActorType.MERCHANT)
        assertEquals(0L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun kindIsStableAsTheAmountIsTypedIn() {
        val kinds = listOf(0L, -5L, -500L, -60000L).map { target(ActorType.ME, ActorType.MERCHANT, meNet = it).kind }
        assertEquals(List(4) { CategoryKind.EXPENSE }, kinds)
    }

    @Test
    fun giftGivenWithNoAmountYet_isStillExpense() {
        assertEquals(CategoryKind.EXPENSE, target(ActorType.ME, ActorType.FRIEND).kind)
    }

    @Test
    fun giftReceivedWithNoAmountYet_isStillIncome() {
        assertEquals(CategoryKind.INCOME, target(ActorType.FRIEND, ActorType.ME).kind)
    }

    @Test
    fun merchantCreditWithNoAmountYet_isStillIncome() {
        assertEquals(CategoryKind.INCOME, target(ActorType.MERCHANT, ActorType.ME).kind)
    }

    /** ME only in the split of a friend-to-friend transaction: no actor type settles the direction. */
    @Test
    fun meOnlyInTheSplit_followsWhichWayMeCameOut() {
        val down = target(ActorType.FRIEND, ActorType.FRIEND, meNet = -1000L)
        assertEquals(CategoryKind.EXPENSE, down.kind)
        assertEquals(1000L, down.sharePaise)

        val up = target(ActorType.FRIEND, ActorType.FRIEND, meNet = 1000L)
        assertEquals(CategoryKind.INCOME, up.kind)
        assertEquals(1000L, up.sharePaise)

        assertEquals(0L, target(ActorType.FRIEND, ActorType.FRIEND, meNet = 0L).sharePaise)
    }

    /** A half-typed split can briefly leave ME up on a purchase; that is nothing to categorise, not income. */
    @Test
    fun aDirectionTheShapeRulesOut_isNothingToCategorise() {
        val t = target(ActorType.ME, ActorType.MERCHANT, meNet = 5000L)
        assertEquals(CategoryKind.EXPENSE, t.kind)
        assertEquals(0L, t.sharePaise)
    }
}
