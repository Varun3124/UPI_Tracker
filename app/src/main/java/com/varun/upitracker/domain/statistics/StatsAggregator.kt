package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.model.CategoryTotal
import com.varun.upitracker.database.model.PayeeTotal

/** One category's share of a window, carrying the colour it is drawn in on both charts. */
data class CategorySlice(
    val categoryId: Long,
    val name: String,
    val paise: Long,
    val color: Int
)

/** One column of the weekly bar. [segments] is parallel to the slice list, in the same order. */
data class DayStack(
    val dayStartEpoch: Long,
    val label: String,
    val segments: List<Long>,
    val totalPaise: Long
)

/** A window's breakdown. [days] is empty for every period except the weekly one. */
data class Breakdown(
    val slices: List<CategorySlice>,
    val days: List<DayStack> = emptyList()
) {
    val totalPaise: Long get() = slices.sumOf { it.paise }
}

/** The arc maths behind the pie, kept out of the View so it can be asserted without a device. */
object PieGeometry {

    /** Twelve o'clock. Canvas angles run clockwise from three o'clock. */
    const val START_ANGLE = -90f

    /**
     * Sweeps summing to exactly 360.
     *
     * The last slice takes whatever the running total left rather than its own rounded share, so
     * float error lands as a fraction of a degree on one interior boundary instead of leaving a
     * hairline wedge of background at twelve o'clock.
     */
    fun sweeps(valuesPaise: List<Long>): List<Float> {
        val total = valuesPaise.sumOf { maxOf(it, 0L) }
        if (total <= 0L) return List(valuesPaise.size) { 0f }
        var used = 0f
        return valuesPaise.mapIndexed { index, value ->
            if (index == valuesPaise.lastIndex) {
                360f - used
            } else {
                (360f * (maxOf(value, 0L).toFloat() / total.toFloat())).also { used += it }
            }
        }
    }
}

object StatsAggregator {

    /**
     * The DAO already orders by net descending and drops zero nets, but both are redone here so
     * the folded weekly path and the direct single-query path cannot order differently, and so the
     * tie-break is deterministic.
     */
    fun toSlices(totals: List<CategoryTotal>): List<CategorySlice> =
        totals.asSequence()
            .filter { it.netPaise > 0L }
            .sortedWith(compareByDescending<CategoryTotal> { it.netPaise }.thenBy { it.categoryId })
            .map {
                CategorySlice(
                    categoryId = it.categoryId,
                    name = it.categoryName,
                    paise = it.netPaise,
                    color = CategoryPalette.colorFor(it.categoryId)
                )
            }
            .toList()

    /**
     * The same shape as [toSlices], for the counterparties inside one category.
     *
     * [CategorySlice.categoryId] carries the **palette key** here rather than a category id:
     * a merchant id, or a friend id negated so the two id spaces cannot collide. That keeps a
     * payee's colour stable across periods for the same reason category colours are.
     */
    fun toPayeeSlices(totals: List<PayeeTotal>): List<CategorySlice> =
        totals.asSequence()
            .filter { it.netPaise > 0L }
            .map { total ->
                val key = total.merchantId ?: total.friendId?.let { -it } ?: 0L
                CategorySlice(key, total.payeeName, total.netPaise, CategoryPalette.colorFor(key))
            }
            .sortedWith(compareByDescending<CategorySlice> { it.paise }.thenBy { it.categoryId })
            .toList()

    /**
     * Folds per-day breakdowns into the week's, re-expressing each day against that same ordered
     * category list so the largest category is the base of every column and a colour can be
     * tracked across the week.
     *
     * The weekly pie is **derived from the day buckets** rather than queried separately. The two
     * then cannot disagree, and it saves a database round trip. It is provably the same number:
     * the seven day windows tile the week exactly, a purchase is bucketed by its own date and a
     * refund by its original purchase's, so every counted row lands in exactly one bucket.
     */
    fun foldDays(
        dayStartEpochs: List<Long>,
        dayLabels: List<String>,
        perDay: List<List<CategoryTotal>>
    ): Breakdown {
        require(dayLabels.size == perDay.size) { "One label per day bucket." }
        require(dayStartEpochs.size == perDay.size) { "One epoch per day bucket." }

        val weekTotals = perDay.flatten()
            .groupBy { it.categoryId }
            .map { (id, rows) ->
                CategoryTotal(id, rows.first().categoryName, rows.sumOf { it.netPaise })
            }

        val slices = toSlices(weekTotals)
        val order = slices.map { it.categoryId }

        val days = perDay.mapIndexed { index, day ->
            val byId = day.associate { it.categoryId to it.netPaise }
            val segments = order.map { maxOf(byId[it] ?: 0L, 0L) }
            DayStack(dayStartEpochs[index], dayLabels[index], segments, segments.sum())
        }
        return Breakdown(slices, days)
    }
}

/**
 * The weekly bar chart's Y axis.
 *
 * Fixed rather than fitted to each week: a quiet week and a heavy one otherwise draw identical
 * bars, so height means nothing across weeks. Anchored at a rupee amount typical daily spending
 * stays under, and grown only when a day actually exceeds it.
 */
object BarAxis {

    /** Gridline spacing, in paise. */
    const val STEP_PAISE = 50_000L

    /** The axis top unless a day exceeds it. */
    const val FLOOR_PAISE = 300_000L

    /** Above this many lines, label every second one so they do not collide. */
    private const val DENSE_LINE_COUNT = 6

    fun axisMaxPaise(maxColumnPaise: Long): Long {
        if (maxColumnPaise <= FLOOR_PAISE) return FLOOR_PAISE
        return ((maxColumnPaise + STEP_PAISE - 1) / STEP_PAISE) * STEP_PAISE
    }

    /** Gridline values above zero, ascending. The baseline is drawn separately. */
    fun gridlinesPaise(axisMaxPaise: Long): List<Long> =
        generateSequence(STEP_PAISE) { it + STEP_PAISE }
            .takeWhile { it <= axisMaxPaise }
            .toList()

    /** Label every nth gridline. */
    fun labelStride(lineCount: Int): Int = if (lineCount > DENSE_LINE_COUNT) 2 else 1
}
