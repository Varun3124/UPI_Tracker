package com.varun.upitracker.domain.statistics

/**
 * A Y axis with a rounded step, spanning [minPaise] to [maxPaise].
 *
 * The span is guaranteed positive, so [fractionOf] can divide without a guard at every call site.
 */
data class ValueAxis(val minPaise: Long, val maxPaise: Long, val stepPaise: Long) {

    init {
        require(maxPaise > minPaise) { "Degenerate axis: $minPaise..$maxPaise." }
        require(stepPaise > 0L) { "Step must be positive: $stepPaise." }
    }

    /** Every gridline including both ends, ascending. */
    val gridlines: List<Long>
        get() = generateSequence(minPaise) { it + stepPaise }.takeWhile { it <= maxPaise }.toList()

    /** 0 at the axis floor, 1 at its top. The chart flips it, since canvas Y grows downward. */
    fun fractionOf(paise: Long): Float =
        (paise - minPaise).toFloat() / (maxPaise - minPaise).toFloat()
}

/**
 * Fits a Y axis to the data.
 *
 * Fitted rather than fixed like [BarAxis], which anchors the weekly bars at a rupee figure daily
 * spending stays under so heights compare across weeks. A balance has no such typical magnitude --
 * it is whatever the accounts hold -- and a fixed axis would either flatten it to a line or run off
 * the top.
 *
 * Whether zero is on the axis is the **caller's** decision, made by what it passes as the minimum:
 * bars have to start at zero or their heights lie, while a balance line that never approaches zero
 * reads better fitted to its own range.
 */
object TrendAxis {

    /** Gridlines to aim for. The rounding can land one either side of this. */
    const val TARGET_LINES = 4

    /** One rupee. Rounding the axis finer than the currency displays would be noise. */
    const val MIN_STEP_PAISE = 100L

    fun fit(minPaise: Long, maxPaise: Long, targetLines: Int = TARGET_LINES): ValueAxis {
        require(targetLines > 0) { "targetLines must be positive." }
        val lo = minOf(minPaise, maxPaise)
        val hi = maxOf(minPaise, maxPaise)

        val step = niceStep((hi - lo + targetLines - 1) / targetLines)
        val axisMin = Math.floorDiv(lo, step) * step
        var axisMax = -Math.floorDiv(-hi, step) * step
        // A flat series rounds to a zero-height axis. Grow upward only: growing downward would push
        // a bar chart's floor below zero and make every bar float.
        if (axisMax == axisMin) axisMax += step

        return ValueAxis(axisMin, axisMax, step)
    }

    /**
     * The smallest 1, 2 or 5 times a power of ten that is at least [rawStep].
     *
     * Integer arithmetic throughout: `log10` on a paise figure rounds unpredictably at the powers of
     * ten, which is exactly where a step is chosen.
     */
    private fun niceStep(rawStep: Long): Long {
        val target = maxOf(rawStep, MIN_STEP_PAISE)
        var unit = 1L
        while (unit <= target / 10) unit *= 10
        return when {
            unit >= target -> unit
            2 * unit >= target -> 2 * unit
            5 * unit >= target -> 5 * unit
            else -> 10 * unit
        }
    }
}
