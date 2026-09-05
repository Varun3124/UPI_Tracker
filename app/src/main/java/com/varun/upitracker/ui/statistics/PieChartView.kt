package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import com.varun.upitracker.domain.statistics.CategoryPalette
import com.varun.upitracker.domain.statistics.PieGeometry

/**
 * A donut of expense by category.
 *
 * The app's first custom View, so it is deliberately minimal: onMeasure and onDraw, no custom
 * attributes, no animation, no touch handling.
 *
 * Slices are tappable. Only the DOWN is claimed, which is enough for a tap and still leaves the
 * swipe container first refusal on every MOVE, so a horizontal drag begun on the pie still steps
 * the period rather than being swallowed.
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
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CategoryPalette.NEUTRAL
        alpha = 70
        strokeWidth = dp(18f)
    }

    /** Index into the slice list the values were set from. */
    var onSliceTapped: ((Int) -> Unit)? = null

    private val oval = RectF()
    private var downX = 0f
    private var downY = 0f
    private var colors = IntArray(0)
    private var sweeps = FloatArray(0)

    /** [values] and [sliceColors] are parallel and already ordered; nothing is sorted here. */
    fun setSlices(values: List<Long>, sliceColors: List<Int>) {
        require(values.size == sliceColors.size) { "One colour per slice." }
        colors = sliceColors.toIntArray()
        sweeps = PieGeometry.sweeps(values).toFloatArray()
        isClickable = onSliceTapped != null && sweeps.isNotEmpty()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(DEFAULT_DIAMETER_DP).toInt(), widthMeasureSpec)
        // Square, but capped: a match_parent pie on a tablet would be half a screen tall.
        val h = minOf(w, dp(MAX_DIAMETER_DP).toInt())
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    /**
     * Centre and radius, derived once so hit-testing cannot drift from drawing. The view is not
     * square -- match_parent wide, height capped -- so the diameter follows the smaller side while
     * the centre follows the box, and cx != cy.
     */
    private fun geometry(): Triple<Float, Float, Float>? {
        val usableW = width - paddingLeft - paddingRight
        val usableH = height - paddingTop - paddingBottom
        val diameter = minOf(usableW, usableH).toFloat()
        if (diameter <= 0f) return null
        return Triple(paddingLeft + usableW / 2f, paddingTop + usableH / 2f, diameter / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val (cx, cy, radius) = geometry() ?: return
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

        canvas.drawCircle(cx, cy, radius * HOLE_RATIO, holePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onSliceTapped == null || sweeps.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    sliceAt(downX, downY)?.let {
                        performClick()
                        onSliceTapped?.invoke(it)
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
     * Walks the same sweep array the draw used rather than recomputing percentages -- its last
     * element absorbs the rounding, so a recomputed one would disagree near twelve o'clock.
     */
    private fun sliceAt(x: Float, y: Float): Int? {
        val (cx, cy, radius) = geometry() ?: return null
        val dx = x - cx
        val dy = y - cy
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < radius * HOLE_RATIO || distance > radius) return null

        val degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val fromStart = ((degrees - PieGeometry.START_ANGLE) % 360f + 360f) % 360f

        var accumulated = 0f
        sweeps.forEachIndexed { index, sweep ->
            accumulated += sweep
            if (fromStart < accumulated) return index
        }
        return sweeps.lastIndex
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_DIAMETER_DP = 220f
        const val MAX_DIAMETER_DP = 260f
        const val HOLE_RATIO = 0.56f
    }
}
