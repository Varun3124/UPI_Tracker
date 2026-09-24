package com.varun.upitracker.database.entity

/**
 * Which side of a transaction's split decides who pays whom back.
 *
 * Each share row X on the chosen side reads as two legs: the payee owes X their share, and X owes the
 * payer their share -- dropping whichever leg would have X owe themselves. So the primary payer and
 * payee settle the whole amount between them, and everyone else on the chosen side passes their
 * share straight through. See [com.varun.upitracker.domain.iou.IouLegs].
 *
 * Stored rather than inferred. It used to be read off where ME sat -- recovering from the payer side
 * when ME paid and from the payee side when ME was paid -- which meant the two people a transaction
 * was shared between could each infer a different answer and disagree about who owes what.
 */
enum class IouRecovery {
    /** The payee owes every payer-side person their share; each co-payer owes the payer theirs. */
    FROM_SECONDARY_PAYERS,

    /** Every payee-side person owes the payer their share; the payee owes each co-payee theirs. */
    FROM_SECONDARY_PAYEES
}
