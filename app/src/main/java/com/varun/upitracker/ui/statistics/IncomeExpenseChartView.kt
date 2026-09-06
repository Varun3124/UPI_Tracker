package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import com.varun.upitracker.domain.statistics.TrendAxis
import com.varun.upitracker.domain.statistics.ValueAxis

/**
 * Income as bars, expense as a line, on **one** axis.
 *
 * The shared axis is the whole point: comparing what came in against what went out is the question
 * this chart answers, and two axes fitted independently would draw a small income the same height
 * as a large one.
 *
 * Green for income and red for expense, the pair the app already uses for that exact distinction
 * on the category settings screen.
 */
class IncomeExpenseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PannableChartView(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = INCOME_COLOR
    }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEEEEEE.toInt() // the same inactive grey the pickers use
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = EXPENSE_COLOR
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = EXPENSE_COLOR
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFF0F0F0.toInt()
        strokeWidth = dp(1f)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE0E0E0.toInt()
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = sp(9f)
        textAlign = Paint.Align.RIGHT
    }

    private var income: List<Long> = emptyList()
    private var expense: List<Long> = emptyList()
    private var labels: List<String?> = emptyList()
    private var axis: ValueAxis? = null
    private val path = Path()

    /** All three lists are parallel, one entry per bucket. */
    fun setSeries(income: List<Long>, expense: List<Long>, labels: List<String?>) {
        this.income = income
        this.expense = expense
        this.labels = labels
        val all = income + expense
        // Anchored at zero because a bar measured from anywhere else lies about its height. The
        // floor only drops below zero if a bucket's net ever does, which the refund cap in
        // TransactionValidator.validateRefundCoverage should prevent -- but drawing it honestly
        // costs nothing and beats clipping it out of sight.
        axis = if (all.isEmpty()) {
            null
        } else {
            TrendAxis.fit(minOf(0L, all.min()), maxOf(0L, all.max()))
        }
        panBucketCount = maxOf(income.size, expense.size)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(280f).toInt(), widthMeasureSpec),
            resolveSize(dp(DEFAULT_HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val axis = this.axis ?: return
        val count = maxOf(income.size, expense.size)
        if (count == 0) return

        val gridlines = axis.gridlines
        val gutter = gridlines.maxOf { axisLabelPaint.measureText(axisLabel(it)) } + dp(6f)
        val labelBand = if (labels.any { it != null }) sp(16f) else 0f
        val left = paddingLeft + gutter
        val right = (width - paddingRight).toFloat()
        val bottom = height - paddingBottom - labelBand
        val top = paddingTop + dp(DOT_RADIUS_DP)
        val plotHeight = bottom - top
        if (plotHeight <= 0f || right <= left) return
        panPlotWidthPx = right - left

        fun yOf(value: Long) = bottom - plotHeight * axis.fractionOf(value)

        gridlines.forEach { value ->
            canvas.drawLine(left, yOf(value), right, yOf(value), gridPaint)
            canvas.drawText(axisLabel(value), left - dp(4f), yOf(value) + sp(3f), axisLabelPaint)
        }
        val zeroY = yOf(0L)
        canvas.drawLine(left, zeroY, right, zeroY, baselinePaint)

        // Slot centres, the same layout the weekly stacked bar uses -- and the same one
        // LineChartView uses, so a peak in one chart sits directly above the peak in the other.
        val slot = (right - left) / count
        val barWidth = minOf(slot * BAR_WIDTH_RATIO, dp(36f))
        fun xAt(index: Int) = left + slot * (index + 0.5f)

        income.forEachIndexed { index, value ->
            val centre = xAt(index)
            if (value == 0L) {
                // A stub rather than nothing: a month with no income still has to hold its slot,
                // or the axis silently reads as one bucket short.
                canvas.drawRect(centre - barWidth / 2f, zeroY - dp(2f), centre + barWidth / 2f, zeroY, stubPaint)
            } else {
                val y = yOf(value)
                canvas.drawRect(centre - barWidth / 2f, minOf(y, zeroY), centre + barWidth / 2f, maxOf(y, zeroY), barPaint)
            }
        }

        if (expense.size > 1) {
            path.reset()
            path.moveTo(xAt(0), yOf(expense[0]))
            for (index in 1 until expense.size) path.lineTo(xAt(index), yOf(expense[index]))
            canvas.drawPath(path, linePaint)
        }
        if (expense.size <= MAX_DOTS) {
            expense.forEachIndexed { index, value ->
                canvas.drawCircle(xAt(index), yOf(value), dp(DOT_RADIUS_DP), dotPaint)
            }
        }

        labels.forEachIndexed { index, label ->
            if (label != null && index < count) {
                canvas.drawText(label, xAt(index), bottom + sp(12f), labelPaint)
            }
        }
    }

    /** Whole rupees, the same form the other two charts' axes use. */
    private fun axisLabel(paise: Long): String = (paise / 100L).toString()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        /** The pair CategorySettingsActivity already uses for exactly this distinction. */
        val INCOME_COLOR = 0xFF2E7D32.toInt()
        val EXPENSE_COLOR = 0xFFC62828.toInt()

        private const val DEFAULT_HEIGHT_DP = 180f
        private const val DOT_RADIUS_DP = 2.5f
        private const val MAX_DOTS = 32
        private const val BAR_WIDTH_RATIO = 0.62f
    }
}
