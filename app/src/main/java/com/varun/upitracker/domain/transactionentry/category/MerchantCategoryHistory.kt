package com.varun.upitracker.domain.transactionentry.category

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.model.MerchantCategoryUse
import com.varun.upitracker.ui.ActorType

/**
 * Which category a merchant's transactions can be filed under without asking: the one it has always
 * been filed under, in that direction, when there has only ever been one.
 *
 * Pure so the entry screen's pre-selection and the notification's "Save as ..." cannot disagree
 * about what a merchant's habit is.
 */
object MerchantCategoryHistory {

    /**
     * Keyed by merchant and direction. Splits are unique per (transaction, category), so a single
     * distinct category also means no transaction was ever split across two.
     */
    fun singleCategories(uses: List<MerchantCategoryUse>): Map<Pair<Long, CategoryKind>, Long> =
        uses.groupBy { it.merchantId to it.kind }
            .mapNotNull { (key, rows) -> rows.map { it.categoryId }.distinct().singleOrNull()?.let { key to it } }
            .toMap()

    /**
     * The merchant a transaction of this shape is categorised against, and in which direction:
     * paying a merchant is an expense, money from one is income. Null when there is no merchant at
     * exactly one end.
     */
    fun merchantSide(
        payerActorType: String,
        payeeActorType: String,
        payerMerchantId: Long?,
        payeeMerchantId: Long?
    ): Pair<Long, CategoryKind>? = when {
        payeeActorType == ActorType.MERCHANT && payerActorType != ActorType.MERCHANT ->
            payeeMerchantId?.let { it to CategoryKind.EXPENSE }
        payerActorType == ActorType.MERCHANT && payeeActorType != ActorType.MERCHANT ->
            payerMerchantId?.let { it to CategoryKind.INCOME }
        else -> null
    }
}
