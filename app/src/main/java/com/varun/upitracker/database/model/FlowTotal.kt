package com.varun.upitracker.database.model

/**
 * Money in and money out of a set of accounts over one window, from the transaction rows alone.
 *
 * Deliberately not the category or share totals: those count only what has been reviewed, and an
 * SMS-imported credit has neither until someone opens it. This counts what actually moved, so
 * [inPaise] minus [outPaise] is the same figure the balance line moves by over the same window.
 */
data class FlowTotal(
    val inPaise: Long,
    val outPaise: Long
)
