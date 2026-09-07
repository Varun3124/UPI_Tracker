package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.varun.upitracker.domain.statistics.BarAxis
import com.varun.upitracker.R
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.dpF
import com.varun.upitracker.ui.theme.spF

/**
 * Day columns, each a stack of the same categories in the same order, against a fixed Y axis.
 *
 * Takes its colours from the caller and its segments in the pie's own order, which is the
 * only reason the two charts read as one picture: a colour means the same category in both, and
 * the largest category is the base of every column so the eye can follow it across the week.
 *
 * The axis is [BarAxis]-fixed rather than fitted to the week, so a bar of a given height means the
 * same amount whichever week is on screen.
 */
class StackedBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Column index of the tapped day. */
    var onDayTapped: ((Int) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.themeColor(ThemeAttr.chartTrack) // the same inactive fill the pickers use
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartZeroLine)
        strokeWidth = dpF(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartGrid)
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

    /** Resolved once: onDraw must not touch the theme per segment. */
    private val neutralColor = context.themeColor(ThemeAttr.chartNeutral)

    private var labels: List<String> = emptyList()
    private var dateLabels: List<String?> = emptyList()
    private var columns: List<List<Long>> = emptyList() // columns[day][segment]
    private var segmentColors = IntArray(0)
    private var axisMaxPaise = BarAxis.FLOOR_PAISE

    private var downX = 0f
    private var downY = 0f

    /**
     * [dateLabels] is parallel to [dayLabels]; a null entry draws no second line. Only the first
     * and last carry one, so the week's span is readable without labelling every column.
     */
    fun setColumns(
        dayLabels: List<String>,
        dateLabels: List<String?>,
        dayColumns: List<List<Long>>,
        colors: List<Int>
    ) {
        labels = dayLabels
        this.dateLabels = dateLabels
        columns = dayColumns
        segmentColors = colors.toIntArray()
        axisMaxPaise = BarAxis.axisMaxPaise(dayColumns.maxOfOrNull { column -> column.sum() } ?: 0L)
        isClickable = onDayTapped != null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dpF(280f).toInt(), widthMeasureSpec),
            resolveSize(dpF(DEFAULT_HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return

        val gridlines = BarAxis.gridlinesPaise(axisMaxPaise)
        val stride = BarAxis.labelStride(gridlines.size)

        // Measured, not guessed: the axis grows past 3000 when a day does, and the gutter has to
        // grow with the widest label rather than clipping it.
        val gutter = gridlines.maxOf { axisLabelPaint.measureText(axisLabel(it)) } + dpF(6f)

        val labelBand = if (dateLabels.any { it != null }) spF(28f) else spF(16f)
        val left = paddingLeft + gutter
        val right = (width - paddingRight).toFloat()
        val baseline = height - paddingBottom - labelBand
        val plotHeight = baseline - paddingTop
        if (plotHeight <= 0f || right <= left) return

        gridlines.forEachIndexed { index, value ->
            val y = baseline - plotHeight * (value / axisMaxPaise.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
            if (index % stride == 0) {
                canvas.drawText(axisLabel(value), left - dpF(4f), y + spF(3f), axisLabelPaint)
            }
        }
        canvas.drawLine(left, baseline, right, baseline, baselinePaint)

        val slot = (right - left) / labels.size
        val barWidth = minOf(slot * BAR_WIDTH_RATIO, dpF(36f))
        val minSegment = dpF(1.5f)

        labels.forEachIndexed { day, label ->
            val centre = left + slot * (day + 0.5f)
            val barLeft = centre - barWidth / 2f
            val barRight = centre + barWidth / 2f
            val segments = columns.getOrNull(day).orEmpty()

            if (segments.sum() <= 0L) {
                // A stub rather than nothing: an empty Tuesday still has to hold its slot, or the
                // week silently reads as six days.
                canvas.drawRect(barLeft, baseline - dpF(2f), barRight, baseline, stubPaint)
            } else {
                // Each edge comes from the running total, never from summing rounded heights, so a
                // segment's bottom is exactly the previous one's top and the column's full height
                // stays exact however many hairline segments it contains.
                var running = 0L
                segments.forEachIndexed { index, value ->
                    if (value <= 0L) return@forEachIndexed
                    val yBottom = baseline - plotHeight * (running / axisMaxPaise.toFloat())
                    running += value
                    val yTop = baseline - plotHeight * (running / axisMaxPaise.toFloat())
                    barPaint.color = segmentColors.getOrElse(index) { neutralColor }
                    // A sub-pixel segment is nudged to a visible sliver; drawing bottom-up means
                    // the next segment paints back over the overshoot.
                    canvas.drawRect(
                        barLeft, minOf(yTop, yBottom - minSegment), barRight, yBottom, barPaint
                    )
                }
            }

            val dayLabelY = baseline + spF(12f)
            canvas.drawText(label, centre, dayLabelY, labelPaint)
            dateLabels.getOrNull(day)?.let { canvas.drawText(it, centre, dayLabelY + spF(11f), labelPaint) }
        }
    }

    /**
     * Claiming the DOWN is what makes a tap reach [performClick]. The swipe container still gets
     * first refusal on every MOVE, so a horizontal drag begun on a bar still changes period, and
     * the ScrollView still steals a vertical one via ACTION_CANCEL.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onDayTapped == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfigurationSlop
                if (kotlin.math.abs(event.x - downX) <= slop &&
                    kotlin.math.abs(event.y - downY) <= slop
                ) {
                    columnAt(downX)?.let { index ->
                        performClick()
                        onDayTapped?.invoke(index)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Whole slots, not the drawn bar: slots tile the width with no gutter, and a 26dp-wide bar is
     * a poor target when the row is 42dp wide.
     */
    private fun columnAt(x: Float): Int? {
        if (labels.isEmpty()) return null
        val gridlines = BarAxis.gridlinesPaise(axisMaxPaise)
        val gutter = gridlines.maxOf { axisLabelPaint.measureText(axisLabel(it)) } + dpF(6f)
        val left = paddingLeft + gutter
        val right = (width - paddingRight).toFloat()
        if (x < left || x > right || right <= left) return null
        val slot = (right - left) / labels.size
        return ((x - left) / slot).toInt().coerceIn(0, labels.size - 1)
    }

    private fun axisLabel(paise: Long): String = AmountFormat.axis(paise)

    private val ViewConfigurationSlop: Float
        get() = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()



    private companion object {
        const val DEFAULT_HEIGHT_DP = 180f
        const val BAR_WIDTH_RATIO = 0.62f
    }
}
