package com.varun.upitracker.database.entity

/**
 * Whether a transaction moves what ME and a friend owe each other.
 *
 * Before this existed the answer was inferred purely from shape — a FRIEND -> ME transaction with
 * no shares was assumed to be a repayment and settled real debt — so a monetary gift could not be
 * recorded honestly without corrupting the ledger.
 *
 * A third `SETTLEMENT` value, making the repayment-vs-new-debt call explicit instead of inferring
 * it from `shares.isEmpty()`, is a later change: the column is TEXT, so adding one needs no
 * migration.
 */
enum class LedgerEffect {
    /** Post to the friend ledger, inferring settlement-vs-new-debt from the transaction's shape. */
    DEBT,

    /** Never touches the friend ledger. Gifts in either direction. */
    NONE
}
