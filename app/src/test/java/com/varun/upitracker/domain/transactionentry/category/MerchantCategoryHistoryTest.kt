package com.varun.upitracker.domain.transactionentry.category

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.model.MerchantCategoryUse
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantCategoryHistoryTest {

    @Test
    fun singleCategories_keepsAMerchantFiledUnderOneCategory() {
        val result = MerchantCategoryHistory.singleCategories(
            listOf(MerchantCategoryUse(merchantId = 7, kind = CategoryKind.EXPENSE, categoryId = 3))
        )

        assertEquals(mapOf((7L to CategoryKind.EXPENSE) to 3L), result)
    }

    @Test
    fun singleCategories_dropsAMerchantEverFiledUnderTwo() {
        val result = MerchantCategoryHistory.singleCategories(
            listOf(
                MerchantCategoryUse(7, CategoryKind.EXPENSE, 3),
                MerchantCategoryUse(7, CategoryKind.EXPENSE, 4),
                MerchantCategoryUse(8, CategoryKind.EXPENSE, 3)
            )
        )

        assertEquals(mapOf((8L to CategoryKind.EXPENSE) to 3L), result)
    }

    @Test
    fun singleCategories_judgesEachDirectionOnItsOwn() {
        // Paying the merchant and being paid by it are separate habits.
        val result = MerchantCategoryHistory.singleCategories(
            listOf(
                MerchantCategoryUse(7, CategoryKind.EXPENSE, 3),
                MerchantCategoryUse(7, CategoryKind.INCOME, 10),
                MerchantCategoryUse(7, CategoryKind.INCOME, 11)
            )
        )

        assertEquals(mapOf((7L to CategoryKind.EXPENSE) to 3L), result)
    }

    @Test
    fun singleCategories_ofNothingIsEmpty() {
        assertTrue(MerchantCategoryHistory.singleCategories(emptyList()).isEmpty())
    }

    @Test
    fun merchantSide_paymentToAMerchantIsAnExpense() {
        assertEquals(
            5L to CategoryKind.EXPENSE,
            MerchantCategoryHistory.merchantSide(ActorType.ME, ActorType.MERCHANT, null, 5L)
        )
    }

    @Test
    fun merchantSide_moneyFromAMerchantIsIncome() {
        assertEquals(
            5L to CategoryKind.INCOME,
            MerchantCategoryHistory.merchantSide(ActorType.MERCHANT, ActorType.FRIEND, 5L, null)
        )
    }

    @Test
    fun merchantSide_needsExactlyOneKnownMerchant() {
        assertNull(MerchantCategoryHistory.merchantSide(ActorType.ME, ActorType.MERCHANT, null, null))
        assertNull(MerchantCategoryHistory.merchantSide(ActorType.MERCHANT, ActorType.MERCHANT, 1L, 2L))
        assertNull(MerchantCategoryHistory.merchantSide(ActorType.ME, ActorType.FRIEND, null, null))
    }
}
