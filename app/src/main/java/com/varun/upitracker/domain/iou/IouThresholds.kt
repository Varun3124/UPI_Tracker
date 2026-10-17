package com.varun.upitracker.domain.iou

import kotlin.math.abs

/**
 * How small a balance with someone has to be before the app stops treating it as owed.
 *
 * Two lines, both on what is left either way, both "less than":
 *  - below [settledBelowPaise] it is, for all purposes, settled -- a few rupees of rounding nobody
 *    will chase -- and reads as settled wherever the balance is shown;
 *  - below [insignificantBelowPaise] it is real but not worth a place on the dashboard, which hides
 *    it until asked to show it.
 *
 * Display only. The ledger keeps every paisa, so a balance read as settled here is still exactly
 * what it is everywhere money is worked out, and moving a line changes no figure.
 */
data class IouThresholds(
    val settledBelowPaise: Long = DEFAULT_SETTLED_BELOW_PAISE,
    val insignificantBelowPaise: Long = DEFAULT_INSIGNIFICANT_BELOW_PAISE
) {
    fun isSettled(netPaise: Long): Boolean = abs(netPaise) < settledBelowPaise

    fun isInsignificant(netPaise: Long): Boolean = abs(netPaise) < insignificantBelowPaise

    companion object {
        /** ₹10. */
        const val DEFAULT_SETTLED_BELOW_PAISE = 1_000L

        /** ₹100. */
        const val DEFAULT_INSIGNIFICANT_BELOW_PAISE = 10_000L

        /**
         * Why [settledBelowPaise] and [insignificantBelowPaise] cannot be kept, or null when they
         * can. Settled is the narrower of the two: a balance too small to owe is also too small to
         * show, and the other way round would hide some balances it still calls owed.
         */
        fun problemWith(settledBelowPaise: Long, insignificantBelowPaise: Long): String? = when {
            settledBelowPaise < 0L || insignificantBelowPaise < 0L -> "Neither can be negative."
            settledBelowPaise > insignificantBelowPaise ->
                "The settled limit can't be more than the insignificant one."
            else -> null
        }
    }
}
