package com.varun.upitracker.database.model

/** A per-category paise total, used by refund validation and the statistics breakdown. */
data class CategoryAmount(
    val categoryId: Long,
    val amountPaise: Long
)
