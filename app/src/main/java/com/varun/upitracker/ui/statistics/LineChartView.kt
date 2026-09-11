package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import com.varun.upitracker.domain.statistics.TrendAxis
import com.varun.upitracker.domain.statistics.ValueAxis
import com.varun.upitracker.R
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.dpF
import com.varun.upitracker.ui.theme.spF

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
        color = context.themeColor(ThemeAttr.primary) // the accent, as on the pills and the chevrons
        strokeWidth = dpF(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.themeColor(ThemeAttr.primary)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartGrid)
        strokeWidth = dpF(1f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartZeroLine)
        strokeWidth = dpF(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(ThemeAttr.chartAxisLabel)
        textSize = spF(10f)
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(ThemeAttr.chartAxisLabel)
        textSize = spF(9f)
        textAlign = Paint.Align.RIGHT
    }

    private val speculativePaint = Paint(linePaint).apply {
        color = context.themeColor(ThemeAttr.speculative)
    }
    private val speculativeDotPaint = Paint(dotPaint).apply {
        color = context.themeColor(ThemeAttr.speculative)
    }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.speculative)
        strokeWidth = dpF(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dpF(4f), dpF(4f)), 0f)
    }

    private var values: List<Long> = emptyList()
    private var labels: List<String?> = emptyList()
    private var axis: ValueAxis? = null
    private val path = Path()

    /** Leading points drawn as speculation; see [com.varun.upitracker.domain.BalanceConfidence]. */
    private var speculativeCount: Int = 0
    private var checkpointFraction: Float? = null

    /**
     * [labels] is parallel to [values]; a null entry draws no label under that point.
     *
     * @param speculativeCount how many leading points were reconstructed rather than derived from a
     *   reconciliation. Those are drawn in the speculative colour.
     * @param checkpointFraction where across the plot the first reconciliation falls, 0 to 1, or
     *   null to draw no checkpoint.
     */
    fun setSeries(
        values: List<Long>,
        labels: List<String?>,
        speculativeCount: Int = 0,
        checkpointFraction: Float? = null
    ) {
        this.values = values
        this.labels = labels
        this.speculativeCount = speculativeCount.coerceIn(0, values.size)
        this.checkpointFraction = checkpointFraction
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
            resolveSize(dpF(280f).toInt(), widthMeasureSpec),
            resolveSize(dpF(DEFAULT_HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val axis = this.axis ?: return
        if (values.isEmpty()) return

        val gridlines = axis.gridlines
        // Measured, not guessed: a balance runs to five or six digits and the gutter has to grow
        // with the widest label rather than clip it.
        val gutter = gridlines.maxOf { axisLabelPaint.measureText(axisLabel(it)) } + dpF(6f)

        val labelBand = if (labels.any { it != null }) spF(16f) else 0f
        val left = paddingLeft + gutter
        val right = (width - paddingRight).toFloat()
        val bottom = height - paddingBottom - labelBand
        // Half a dot of headroom at each end, so a point sitting on the axis top is not clipped.
        val top = paddingTop + DOT_RADIUS_DP.let { dpF(it) }
        val plotHeight = bottom - top
        if (plotHeight <= 0f || right <= left) return
        panPlotWidthPx = right - left

        gridlines.forEach { value ->
            val y = bottom - plotHeight * axis.fractionOf(value)
            // Zero gets the darker line: on an overdraft it is the only meaningful crossing, and
            // without it a balance below zero looks like any other low point.
            canvas.drawLine(left, y, right, y, if (value == 0L) zeroPaint else gridPaint)
            canvas.drawText(axisLabel(value), left - dpF(4f), y + spF(3f), axisLabelPaint)
        }

        // Slot centres rather than edge to edge, because this chart is read against the income and
        // expense one below it, whose bars can only sit in slots. Aligning them is what lets a peak
        // in one be read directly above the peak in the other. It costs half a slot of inset at
        // each end, and a lone bucket lands in the middle instead of collapsing onto the left edge.
        val slot = (right - left) / values.size
        fun xAt(index: Int) = left + slot * (index + 0.5f)
        fun yAt(index: Int) = bottom - plotHeight * axis.fractionOf(values[index])

        // Drawn before the line so the line sits on top of it rather than being interrupted.
        checkpointFraction?.let { fraction ->
            val x = left + (right - left) * fraction
            canvas.drawLine(x, top, x, bottom, checkpointPaint)
        }

        if (values.size > 1) {
            // Two passes rather than one, so the run before the first reconciliation reads as the
            // guess it is. They overlap by one segment on purpose: the segment spanning the
            // checkpoint belongs to both, and drawing the certain pass second leaves it solid.
            drawRun(canvas, 0, speculativeCount, ::xAt, ::yAt, speculativePaint)
            drawRun(canvas, (speculativeCount - 1).coerceAtLeast(0), values.size, ::xAt, ::yAt, linePaint)
        }

        // Dots only while they stay apart; a month of them reads as a thick line.
        if (values.size <= MAX_DOTS) {
            values.indices.forEach { index ->
                canvas.drawCircle(
                    xAt(index), yAt(index), dpF(DOT_RADIUS_DP),
                    if (index < speculativeCount) speculativeDotPaint else dotPaint
                )
            }
        }

        labels.forEachIndexed { index, label ->
            if (label == null || index >= values.size) return@forEachIndexed
            // Nudged inside the plot at the ends, so the first and last labels are not half cut off.
            val x = xAt(index).coerceIn(
                left + labelPaint.measureText(label) / 2f,
                right - labelPaint.measureText(label) / 2f
            )
            canvas.drawText(label, x, bottom + spF(12f), labelPaint)
        }
    }

    /** One contiguous stretch of the series, `[from, toExclusive)`. */
    private fun drawRun(
        canvas: Canvas,
        from: Int,
        toExclusive: Int,
        xAt: (Int) -> Float,
        yAt: (Int) -> Float,
        paint: Paint
    ) {
        if (toExclusive - from < 2) return
        path.reset()
        path.moveTo(xAt(from), yAt(from))
        for (index in from + 1 until toExclusive) path.lineTo(xAt(index), yAt(index))
        canvas.drawPath(path, paint)
    }

    /** Whole rupees, the same form the stacked bar's axis uses. */
    private fun axisLabel(paise: Long): String = AmountFormat.axis(paise)



    private companion object {
        const val DEFAULT_HEIGHT_DP = 180f
        const val DOT_RADIUS_DP = 2.5f
        const val MAX_DOTS = 32

        /** A flat window is padded by a twentieth of its own magnitude, so the line sits clear. */
        const val FLAT_PAD_DIVISOR = 20L
    }
}
