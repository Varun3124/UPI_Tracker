package com.varun.upitracker.database.model

import com.varun.upitracker.database.entity.CategoryKind

/** One category a merchant's confirmed transactions have been filed under, in one direction. */
data class MerchantCategoryUse(
    val merchantId: Long,
    val kind: CategoryKind,
    val categoryId: Long
)
