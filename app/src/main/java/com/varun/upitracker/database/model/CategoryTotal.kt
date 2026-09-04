package com.varun.upitracker.database.model

/** One slice of a category breakdown: the category, its name, and its net total for a window. */
data class CategoryTotal(
    val categoryId: Long,
    val categoryName: String,
    val netPaise: Long
)
