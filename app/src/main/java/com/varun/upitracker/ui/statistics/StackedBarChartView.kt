package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.varun.upitracker.domain.statistics.CategoryPalette

/**
 * Day columns, each a stack of the same categories in the same order, scaled to the busiest day.
 *
 * Takes its colours from [CategoryPalette] and its segments in the pie's own order, which is the
 * only reason the two charts read as one picture: a colour means the same category in both, and
 * the largest category is the base of every column so the eye can follow it across the week.
 *
 * Y is scaled to the tallest column rather than to a rounded maximum, because there is no axis to
 * label -- the peak is written above the chart as ordinary text.
 */
class StackedBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEEEEEE.toInt() // the same inactive grey the pills use
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE0E0E0.toInt()
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt() // the app's caption grey
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    private var labels: List<String> = emptyList()
    private var columns: List<List<Long>> = emptyList() // columns[day][segment]
    private var segmentColors = IntArray(0)
    private var maxColumnPaise = 0L

    fun setColumns(dayLabels: List<String>, dayColumns: List<List<Long>>, colors: List<Int>) {
        labels = dayLabels
        columns = dayColumns
        segmentColors = colors.toIntArray()
        maxColumnPaise = dayColumns.maxOfOrNull { column -> column.sum() } ?: 0L
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(280f).toInt(), widthMeasureSpec),
            resolveSize(dp(DEFAULT_HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return

        val labelBand = sp(16f)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val baseline = height - paddingBottom - labelBand
        val plotHeight = baseline - paddingTop
        if (plotHeight <= 0f || right <= left) return

        canvas.drawLine(left, baseline, right, baseline, baselinePaint)

        val slot = (right - left) / labels.size
        val barWidth = minOf(slot * BAR_WIDTH_RATIO, dp(36f))
        val minSegment = dp(1.5f)

        labels.forEachIndexed { day, label ->
            val centre = left + slot * (day + 0.5f)
            val barLeft = centre - barWidth / 2f
            val barRight = centre + barWidth / 2f
            val segments = columns.getOrNull(day).orEmpty()

            if (segments.sum() <= 0L || maxColumnPaise <= 0L) {
                // A stub rather than nothing: an empty Tuesday still has to hold its slot, or the
                // week silently reads as six days.
                canvas.drawRect(barLeft, baseline - dp(2f), barRight, baseline, stubPaint)
            } else {
                // Each edge comes from the running total, never from summing rounded heights, so a
                // segment's bottom is exactly the previous one's top and the column's full height
                // stays exact however many hairline segments it contains.
                var running = 0L
                segments.forEachIndexed { index, value ->
                    if (value <= 0L) return@forEachIndexed
                    val yBottom = baseline - plotHeight * (running / maxColumnPaise.toFloat())
                    running += value
                    val yTop = baseline - plotHeight * (running / maxColumnPaise.toFloat())
                    barPaint.color = segmentColors.getOrElse(index) { CategoryPalette.NEUTRAL }
                    // A sub-pixel segment is nudged to a visible sliver; drawing bottom-up means
                    // the next segment paints back over the overshoot.
                    canvas.drawRect(
                        barLeft, minOf(yTop, yBottom - minSegment), barRight, yBottom, barPaint
                    )
                }
            }

            canvas.drawText(label, centre, height - paddingBottom - sp(3f), labelPaint)
        }
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_HEIGHT_DP = 180f
        const val BAR_WIDTH_RATIO = 0.62f
    }
}
