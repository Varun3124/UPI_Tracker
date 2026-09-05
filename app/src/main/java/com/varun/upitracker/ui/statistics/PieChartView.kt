package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.varun.upitracker.domain.statistics.CategoryPalette
import com.varun.upitracker.domain.statistics.PieGeometry

/**
 * A donut of expense by category.
 *
 * The app's first custom View, so it is deliberately minimal: onMeasure and onDraw, no custom
 * attributes, no animation, no touch handling.
 *
 * No touch handling specifically because this sits inside [SwipeableFrameLayout] and covers the
 * largest target on the screen -- a chart that consumed drags would swallow the period swipe.
 *
 * It draws no text either. The centre figure is a real TextView overlaid by the layout, so it can
 * be formatted the same way every other amount in the app is.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE // the card behind it is #FFFFFF
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CategoryPalette.NEUTRAL
        alpha = 70
        strokeWidth = dp(18f)
    }

    private val oval = RectF()
    private var colors = IntArray(0)
    private var sweeps = FloatArray(0)

    /** [values] and [sliceColors] are parallel and already ordered; nothing is sorted here. */
    fun setSlices(values: List<Long>, sliceColors: List<Int>) {
        require(values.size == sliceColors.size) { "One colour per slice." }
        colors = sliceColors.toIntArray()
        sweeps = PieGeometry.sweeps(values).toFloatArray()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(DEFAULT_DIAMETER_DP).toInt(), widthMeasureSpec)
        // Square, but capped: a match_parent pie on a tablet would be half a screen tall.
        val h = minOf(w, dp(MAX_DIAMETER_DP).toInt())
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val usableW = width - paddingLeft - paddingRight
        val usableH = height - paddingTop - paddingBottom
        val diameter = minOf(usableW, usableH).toFloat()
        if (diameter <= 0f) return

        val cx = paddingLeft + usableW / 2f
        val cy = paddingTop + usableH / 2f
        val radius = diameter / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        if (sweeps.isEmpty()) {
            // A ring rather than blankness, so the screen keeps its shape while stepping back
            // through periods that have nothing in them. The wording is a sibling TextView.
            val inset = emptyPaint.strokeWidth / 2f
            canvas.drawArc(
                oval.left + inset, oval.top + inset, oval.right - inset, oval.bottom - inset,
                0f, 360f, false, emptyPaint
            )
            return
        }

        var angle = PieGeometry.START_ANGLE
        for (i in sweeps.indices) {
            slicePaint.color = colors[i]
            canvas.drawArc(oval, angle, sweeps[i], true, slicePaint)
            angle += sweeps[i]
        }

        // Separators after every fill, so a later slice cannot paint over its neighbour's line.
        if (sweeps.size > 1) {
            angle = PieGeometry.START_ANGLE
            for (sweep in sweeps) {
                val radians = Math.toRadians(angle.toDouble())
                canvas.drawLine(
                    cx, cy,
                    cx + (radius * Math.cos(radians)).toFloat(),
                    cy + (radius * Math.sin(radians)).toFloat(),
                    dividerPaint
                )
                angle += sweep
            }
        }

        canvas.drawCircle(cx, cy, radius * HOLE_RATIO, holePaint)
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_DIAMETER_DP = 220f
        const val MAX_DIAMETER_DP = 260f
        const val HOLE_RATIO = 0.56f
    }
}
