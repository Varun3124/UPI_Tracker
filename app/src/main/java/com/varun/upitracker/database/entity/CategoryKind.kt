package com.varun.upitracker.database.entity

/**
 * Which direction a category measures money in.
 *
 * Only [EXPENSE] categories enter the spend total and the statistics pie: an [INCOME] category
 * (a gift received, a dividend, an unlinked merchant credit) is money arriving, not money consumed.
 *
 * A name is unique per kind, not globally — "Gift" exists in both directions.
 */
enum class CategoryKind {
    EXPENSE,
    INCOME
}
