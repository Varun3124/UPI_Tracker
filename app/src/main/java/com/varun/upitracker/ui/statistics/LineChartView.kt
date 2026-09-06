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
 * One series over time, drawn against an axis fitted to its own range.
 *
 * Fitted rather than [com.varun.upitracker.domain.statistics.BarAxis]-fixed, which is what the
 * weekly stacked bar uses so that a bar of a given height means the same amount whichever week is
 * showing. A balance has no typical magnitude to anchor to -- it is whatever the accounts hold --
 * so a fixed axis would either flatten the line or run off the top of it.
 *
 * Follows the other two charts here: no custom attributes, no text for amounts beyond the axis
 * itself, and a gutter measured from the widest label rather than guessed at.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PannableChartView(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF006064.toInt() // the app's accent, as on the pills and the chevrons
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF006064.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFF0F0F0.toInt()
        strokeWidth = dp(1f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE0E0E0.toInt()
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt() // the app's caption grey
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = sp(9f)
        textAlign = Paint.Align.RIGHT
    }

    private var values: List<Long> = emptyList()
    private var labels: List<String?> = emptyList()
    private var axis: ValueAxis? = null
    private val path = Path()

    /** [labels] is parallel to [values]; a null entry draws no label under that point. */
    fun setSeries(values: List<Long>, labels: List<String?>) {
        this.values = values
        this.labels = labels
        axis = if (values.isEmpty()) null else axisFor(values)
        panBucketCount = values.size
        invalidate()
    }

    /**
     * [TrendAxis.fit] grows a flat range upward only, so that a bar chart's floor stays at zero.
     * A line has no such constraint, and taking that here would draw an unmoved balance along the
     * very bottom edge, where it reads as "almost nothing left". Padded around itself instead.
     */
    private fun axisFor(values: List<Long>): ValueAxis {
        val lo = values.min()
        val hi = values.max()
        if (lo != hi) return TrendAxis.fit(lo, hi)
        val pad = maxOf(kotlin.math.abs(lo) / FLAT_PAD_DIVISOR, TrendAxis.MIN_STEP_PAISE)
        return TrendAxis.fit(lo - pad, hi + pad)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(280f).toInt(), widthMeasureSpec),
            resolveSize(dp(DEFAULT_HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val axis = this.axis ?: return
        if (values.isEmpty()) return

        val gridlines = axis.gridlines
        // Measured, not guessed: a balance runs to five or six digits and the gutter has to grow
        // with the widest label rather than clip it.
        val gutter = gridlines.maxOf { axisLabelPaint.measureText(axisLabel(it)) } + dp(6f)

        val labelBand = if (labels.any { it != null }) sp(16f) else 0f
        val left = paddingLeft + gutter
        val right = (width - paddingRight).toFloat()
        val bottom = height - paddingBottom - labelBand
        // Half a dot of headroom at each end, so a point sitting on the axis top is not clipped.
        val top = paddingTop + DOT_RADIUS_DP.let { dp(it) }
        val plotHeight = bottom - top
        if (plotHeight <= 0f || right <= left) return
        panPlotWidthPx = right - left

        gridlines.forEach { value ->
            val y = bottom - plotHeight * axis.fractionOf(value)
            // Zero gets the darker line: on an overdraft it is the only meaningful crossing, and
            // without it a balance below zero looks like any other low point.
            canvas.drawLine(left, y, right, y, if (value == 0L) zeroPaint else gridPaint)
            canvas.drawText(axisLabel(value), left - dp(4f), y + sp(3f), axisLabelPaint)
        }

        // Slot centres rather than edge to edge, because this chart is read against the income and
        // expense one below it, whose bars can only sit in slots. Aligning them is what lets a peak
        // in one be read directly above the peak in the other. It costs half a slot of inset at
        // each end, and a lone bucket lands in the middle instead of collapsing onto the left edge.
        val slot = (right - left) / values.size
        fun xAt(index: Int) = left + slot * (index + 0.5f)
        fun yAt(index: Int) = bottom - plotHeight * axis.fractionOf(values[index])

        if (values.size > 1) {
            path.reset()
            path.moveTo(xAt(0), yAt(0))
            for (index in 1 until values.size) path.lineTo(xAt(index), yAt(index))
            canvas.drawPath(path, linePaint)
        }

        // Dots only while they stay apart; a month of them reads as a thick line.
        if (values.size <= MAX_DOTS) {
            values.indices.forEach { canvas.drawCircle(xAt(it), yAt(it), dp(DOT_RADIUS_DP), dotPaint) }
        }

        labels.forEachIndexed { index, label ->
            if (label == null || index >= values.size) return@forEachIndexed
            // Nudged inside the plot at the ends, so the first and last labels are not half cut off.
            val x = xAt(index).coerceIn(
                left + labelPaint.measureText(label) / 2f,
                right - labelPaint.measureText(label) / 2f
            )
            canvas.drawText(label, x, bottom + sp(12f), labelPaint)
        }
    }

    /** Whole rupees, the same form the stacked bar's axis uses. */
    private fun axisLabel(paise: Long): String = (paise / 100L).toString()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_HEIGHT_DP = 180f
        const val DOT_RADIUS_DP = 2.5f
        const val MAX_DOTS = 32

        /** A flat window is padded by a twentieth of its own magnitude, so the line sits clear. */
        const val FLAT_PAD_DIVISOR = 20L
    }
}
