package com.varun.upitracker.ui.theme

import android.content.Context
import androidx.annotation.ColorInt
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.CategoryPalette

/**
 * Resolves a category's slot in [CategoryPalette] to an actual colour for the current mode.
 *
 * The split matters: which slot a category owns is a pure, id-keyed decision that has to stay
 * identical forever (see CategoryPalette's own notes), so it stays in `domain` with no Android on
 * it. What that slot *looks like* is a theme question, and lives out here in resources, which is how
 * the same category can be a deep blue on a white card and a light blue on a navy one.
 */
object ChartColors {

    @ColorInt
    fun forCategory(context: Context, categoryId: Long): Int {
        val palette = context.resources.obtainTypedArray(R.array.chart_category_colors)
        try {
            return palette.getColor(CategoryPalette.indexFor(categoryId, palette.length()), 0)
        } finally {
            palette.recycle()
        }
    }

    /** The whole palette in slot order, for a caller colouring a list in one pass. */
    fun all(context: Context): List<Int> {
        val palette = context.resources.obtainTypedArray(R.array.chart_category_colors)
        try {
            return (0 until palette.length()).map { palette.getColor(it, 0) }
        } finally {
            palette.recycle()
        }
    }

    /** The empty-state ring and the bar chart's empty-day stub; never issued to a real category. */
    @ColorInt
    fun neutral(context: Context): Int = context.themeColor(ThemeAttr.chartNeutral)
}
