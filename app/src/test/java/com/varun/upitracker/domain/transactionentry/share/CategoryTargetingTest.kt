package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.domain.transactionentry.category.CategorySplitManager
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTargetingTest {

    private val calculator = ShareCalculator()
    private val splitManager = CategorySplitManager()

    private fun target(
        payer: String,
        payee: String,
        effect: LedgerEffect = LedgerEffect.DEBT,
        isLinkedRefund: Boolean = false,
        payerMe: Long = 0L,
        payeeMe: Long = 0L
    ) = calculator.categoryTargeting(payer, payee, effect, isLinkedRefund, payerMe, payeeMe)

    @Test
    fun merchantPurchase_isExpenseOnThePayerShare() {
        val t = target(ActorType.ME, ActorType.MERCHANT, payerMe = 60000L)
        assertEquals(60000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun plainLoanToFriend_isNotCategorisable() {
        assertEquals(0L, target(ActorType.ME, ActorType.FRIEND, payerMe = 50000L).sharePaise)
    }

    @Test
    fun giftGiven_isExpense() {
        val t = target(ActorType.ME, ActorType.FRIEND, LedgerEffect.NONE, payerMe = 50000L)
        assertEquals(50000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun giftReceived_isIncome() {
        val t = target(ActorType.FRIEND, ActorType.ME, LedgerEffect.NONE, payeeMe = 50000L)
        assertEquals(50000L, t.sharePaise)
        assertEquals(CategoryKind.INCOME, t.kind)
    }

    @Test
    fun unlinkedMerchantCredit_isIncome() {
        val t = target(ActorType.MERCHANT, ActorType.ME, payeeMe = 20000L)
        assertEquals(20000L, t.sharePaise)
        assertEquals(CategoryKind.INCOME, t.kind)
    }

    @Test
    fun linkedRefund_isExpenseDespiteMeBeingOnThePayeeSide() {
        val t = target(ActorType.MERCHANT, ActorType.ME, isLinkedRefund = true, payeeMe = 20000L)
        assertEquals(20000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    /**
     * The "Me" option is offered per side, so ME can hold a share on both. Summing them would
     * double-count and leave the direction ambiguous.
     */
    @Test
    fun meOnBothSides_picksThePayerSideRatherThanSumming() {
        val t = target(ActorType.ME, ActorType.MERCHANT, payerMe = 60000L, payeeMe = 25000L)
        assertEquals(60000L, t.sharePaise)
        assertEquals(CategoryKind.EXPENSE, t.kind)
    }

    @Test
    fun visibility_followsTargeting() {
        val shown = splitManager.visibilityDecision(
            target(ActorType.ME, ActorType.MERCHANT, payerMe = 60000L)
        )
        assertTrue(shown.showCategories)
        assertFalse(shown.shouldClearSelections)
        assertEquals(CategoryKind.EXPENSE, shown.kind)

        val hidden = splitManager.visibilityDecision(
            target(ActorType.ME, ActorType.FRIEND, payerMe = 50000L)
        )
        assertFalse(hidden.showCategories)
        assertTrue(hidden.shouldClearSelections)
    }
}
