package com.varun.upitracker.ui.statistics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.varun.upitracker.domain.statistics.BalancePoints
import com.varun.upitracker.domain.statistics.GraphBounds
import com.varun.upitracker.domain.statistics.GraphTicks
import com.varun.upitracker.domain.statistics.GraphViewport
import com.varun.upitracker.domain.statistics.PinchAxes
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.dpF
import com.varun.upitracker.ui.theme.spF
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.util.AmountFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The balance over time as a graph you move around in, after the Desmos mobile app.
 *
 * One finger drags the graph in any direction and flings it on. Two fingers zoom about the point
 * between them: side by side they stretch time alone, one above the other the balance alone, and
 * anything between stretches both ([GraphViewport.pinchAxes]). A double tap zooms in there. Tapping
 * the line names the balance change nearest the finger.
 *
 * Drawn as steps: a balance holds until the next transaction, then jumps, so the height under any
 * moment is what the accounts really held then. No dots -- only the point tapped is marked.
 *
 * Gridlines sit on round values ([GraphTicks]) and slide with the graph. Balance labels run down the
 * left edge; time labels run along the ₹0 line when it is on screen and along the bottom when not,
 * the way Desmos pins axis numbers that would otherwise scroll away.
 *
 * Where the graph is looking belongs to this view and to nobody else: it is kept across a rotation,
 * but moving around never reaches the app's back history.
 */
class BalanceGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.primary)
        strokeWidth = dpF(2f)
        strokeJoin = Paint.Join.MITER
    }
    private val speculativePaint = Paint(linePaint).apply { color = context.themeColor(ThemeAttr.speculative) }
    private val majorGridPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartGrid)
        strokeWidth = dpF(1f)
    }
    private val minorGridPaint = Paint(majorGridPaint).apply {
        strokeWidth = dpF(0.5f)
        alpha = 90
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.chartZeroLine)
        strokeWidth = dpF(1.5f)
    }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.speculative)
        strokeWidth = dpF(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(4f), dpF(4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(ThemeAttr.chartAxisLabel)
        textSize = spF(10f)
    }
    private val labelBackPaint = Paint().apply {
        color = context.themeColor(ThemeAttr.background)
        alpha = 210
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(ThemeAttr.primary) }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(ThemeAttr.surface) }
    private val tipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(ThemeAttr.outlineVariant)
        strokeWidth = dpF(1f)
    }
    private val tipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(ThemeAttr.onSurface)
        textSize = spF(12f)
    }
    private val tipSubPaint = Paint(tipTextPaint).apply {
        color = context.themeColor(ThemeAttr.onSurfaceVariant)
        textSize = spF(11f)
    }

    private val path = Path()
    private val speculativePath = Path()
    private val tipRect = RectF()
    private val tipDateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    private var points: BalancePoints? = null
    private var certainFromEpoch: Long? = null
    private var nowEpoch = System.currentTimeMillis()
    private var bounds: GraphBounds? = null
    private var dataKey: String? = null

    private var viewport: GraphViewport? = null
    /** Brought back by a rotation before the data arrives, and used in place of the first view. */
    private var restored: GraphViewport? = null
    private var restoredKey: String? = null

    private var selected: Int? = null
    private var animator: ValueAnimator? = null

    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var flingLastY = 0
    private var pinchAxes = PinchAxes.BOTH

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            stopMotion()
            // Decided once, from where the fingers first sat, so a pinch that rotates as it goes
            // does not start stretching the other axis halfway through.
            pinchAxes = GraphViewport.pinchAxes(detector.currentSpanX, detector.currentSpanY)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = viewport ?: return false
            val sx = if (pinchAxes == PinchAxes.Y) 1.0 else ratio(detector.currentSpanX, detector.previousSpanX)
            val sy = if (pinchAxes == PinchAxes.X) 1.0 else ratio(detector.currentSpanY, detector.previousSpanY)
            move(current.zoomAt(detector.focusX, detector.focusY, sx, sy, width.toFloat(), height.toFloat()))
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopMotion()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress || e2.pointerCount > 1) return false
            val current = viewport ?: return false
            move(current.panBy(-distanceX, -distanceY, width.toFloat(), height.toFloat()))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            flingLastX = 0
            flingLastY = 0
            scroller.fling(0, 0, velocityX.toInt(), velocityY.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            selected = nearestPoint(e.x, e.y)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val current = viewport ?: return false
            animateTo(current.zoomAt(e.x, e.y, 2.0, 2.0, width.toFloat(), height.toFloat()))
            return true
        }
    })

    /**
     * The graph's data. [key] says whose it is -- the account scope -- and a new key starts on the
     * first view again, since another scope's balances may sit at a different height altogether.
     * The same key keeps the view where it is.
     */
    fun setPoints(points: BalancePoints?, certainFromEpoch: Long?, nowEpoch: Long, key: String) {
        this.points = points
        this.certainFromEpoch = certainFromEpoch
        this.nowEpoch = nowEpoch
        bounds = points?.let { GraphBounds.of(it, nowEpoch) }
        selected = null
        val keyChanged = key != dataKey
        dataKey = key
        if (points != null) {
            val keep = restored?.takeIf { restoredKey == key }
            restored = null
            when {
                keep != null -> viewport = keep.clampedToData()
                viewport == null || keyChanged -> viewport = recentView()
            }
        }
        invalidate()
    }

    /**
     * Back to the last month; pressed again while already there, out to the whole history -- the
     * two views anyone looking at a balance starts from.
     */
    fun goHome() {
        val recent = recentView() ?: return
        val current = viewport
        animateTo(if (current != null && closeTo(current, recent)) wholeView() ?: recent else recent)
    }

    /** Zooms both axes about the middle of the screen, for the + and − buttons. */
    fun zoomBy(factor: Double) {
        val current = viewport ?: return
        animateTo(current.zoomAt(width / 2f, height / 2f, factor, factor, width.toFloat(), height.toFloat()))
    }

    private fun recentView(): GraphViewport? {
        val points = points ?: return null
        val from = nowEpoch - RECENT_DAYS * DAY_MILLIS
        return padRight(GraphViewport.fit(points, from, nowEpoch)).clampedToData()
    }

    private fun wholeView(): GraphViewport? {
        val points = points ?: return null
        val from = points.epochs.first()
        val fitted = GraphViewport.fit(points, from, nowEpoch)
        val pad = fitted.xSpan * 0.03
        return GraphViewport(fitted.xMin - pad, fitted.xMax + pad, fitted.yMin, fitted.yMax).clampedToData()
    }

    /** A little room after now, so the latest balance is not drawn against the edge. */
    private fun padRight(view: GraphViewport): GraphViewport =
        view.copy(xMax = view.xMax + view.xSpan * 0.04)

    private fun GraphViewport.clampedToData(): GraphViewport = bounds?.let { clamped(it) } ?: this

    private fun closeTo(a: GraphViewport, b: GraphViewport): Boolean =
        abs(a.xMin - b.xMin) < b.xSpan * 0.02 && abs(a.xMax - b.xMax) < b.xSpan * 0.02 &&
            abs(a.yMin - b.yMin) < b.ySpan * 0.05 && abs(a.yMax - b.yMax) < b.ySpan * 0.05

    private fun ratio(current: Float, previous: Float): Double =
        if (previous < MIN_PINCH_SPAN_PX || current < MIN_PINCH_SPAN_PX) 1.0 else (current / previous).toDouble()

    private fun move(next: GraphViewport) {
        viewport = next.clampedToData()
        invalidate()
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        animator?.cancel()
        animator = null
    }

    private fun animateTo(target: GraphViewport) {
        val from = viewport ?: return move(target)
        val to = target.clampedToData()
        stopMotion()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = (it.animatedValue as Float).toDouble()
                viewport = GraphViewport(
                    from.xMin + (to.xMin - from.xMin) * t,
                    from.xMax + (to.xMax - from.xMax) * t,
                    from.yMin + (to.yMin - from.yMin) * t,
                    from.yMax + (to.yMax - from.yMax) * t
                )
                invalidate()
            }
            start()
        }
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val current = viewport ?: return
        val dx = scroller.currX - flingLastX
        val dy = scroller.currY - flingLastY
        flingLastX = scroller.currX
        flingLastY = scroller.currY
        val next = current.panBy(dx.toFloat(), dy.toFloat(), width.toFloat(), height.toFloat()).clampedToData()
        // Run into the edge of the data in both directions: nothing more for the fling to do.
        if (next == current) scroller.forceFinished(true)
        viewport = next
        if (!scroller.isFinished) postInvalidateOnAnimation()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points == null) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    /** The balance change nearest the finger, if one is near enough to have been meant. */
    private fun nearestPoint(xPx: Float, yPx: Float): Int? {
        val points = points ?: return null
        val view = viewport ?: return null
        val w = width.toFloat()
        val h = height.toFloat()
        val from = (points.indexAtOrBefore(view.xMin.toLong())).coerceAtLeast(0)
        val to = (points.indexAtOrBefore(view.xMax.toLong()) + 1).coerceAtMost(points.size - 1)
        var best: Int? = null
        var bestDistance = dpF(TAP_RADIUS_DP)
        for (index in from..to) {
            val x = view.xToPx(points.epochs[index].toDouble(), w)
            val y = view.yToPx(points.balances[index].toDouble(), h)
            val distance = hypot(x - xPx, y - yPx)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        val points = points ?: return
        val view = viewport ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val yTicks = GraphTicks.valueTicks(view.yMin, view.yMax, h, dpF(MAJOR_SPACING_DP))
        val xTicks = GraphTicks.timeTicks(
            view.xMin.toLong(), view.xMax.toLong(), w / view.xSpan, dpF(MAJOR_SPACING_DP)
        )

        // Grid first, minors under majors, so everything after reads on top of it.
        yTicks.minor.forEach { canvas.drawLine(0f, view.yToPx(it, h), w, view.yToPx(it, h), minorGridPaint) }
        xTicks.minor.forEach { tick ->
            val x = view.xToPx(tick.epoch.toDouble(), w)
            canvas.drawLine(x, 0f, x, h, minorGridPaint)
        }
        yTicks.major.forEach { canvas.drawLine(0f, view.yToPx(it, h), w, view.yToPx(it, h), majorGridPaint) }
        xTicks.major.forEach { tick ->
            val x = view.xToPx(tick.epoch.toDouble(), w)
            canvas.drawLine(x, 0f, x, h, majorGridPaint)
        }

        // ₹0 is the graph's x-axis: on an overdraft it is the only crossing that matters.
        val zeroY = view.yToPx(0.0, h)
        val zeroVisible = zeroY in 0f..h
        if (zeroVisible) canvas.drawLine(0f, zeroY, w, zeroY, zeroPaint)

        certainFromEpoch?.let { certain ->
            if (certain > view.xMin && certain < view.xMax) {
                val x = view.xToPx(certain.toDouble(), w)
                canvas.drawLine(x, 0f, x, h, checkpointPaint)
            }
        }

        drawSteps(canvas, points, view, w, h)
        drawValueLabels(canvas, yTicks.major, view, h)
        drawTimeLabels(canvas, xTicks.major.map { it.epoch to it.label }, view, w, h, zeroY, zeroVisible)
        selected?.let { drawSelection(canvas, points, it, view, w, h) }
    }

    /**
     * The visible stretch as steps: across at each balance until the next change, then up or down
     * to it. Two paths, so the stretch before the first reconciliation reads as the guess it is.
     */
    private fun drawSteps(canvas: Canvas, points: BalancePoints, view: GraphViewport, w: Float, h: Float) {
        val from = points.indexAtOrBefore(view.xMin.toLong()).coerceAtLeast(0)
        val to = (points.indexAtOrBefore(view.xMax.toLong()) + 1).coerceAtMost(points.size - 1)
        val certain = certainFromEpoch ?: Long.MAX_VALUE
        // Off-screen ends are pulled in to just past the edge: a Path handed coordinates in the
        // millions, as a deep zoom produces, draws unreliably.
        fun x(epoch: Long) = view.xToPx(epoch.toDouble(), w).coerceIn(-w, 2 * w)
        fun y(index: Int) = view.yToPx(points.balances[index].toDouble(), h).coerceIn(-h, 2 * h)

        path.reset()
        speculativePath.reset()
        for (index in from..to) {
            val start = points.epochs[index]
            val end = if (index < points.size - 1) points.epochs[index + 1] else maxOf(nowEpoch, start)
            val yHere = y(index)
            // Across: split where the first reconciliation falls, so each side takes its own colour.
            if (start < certain && end > certain) {
                segment(speculativePath, x(start), yHere, x(certain), yHere)
                segment(path, x(certain), yHere, x(end), yHere)
            } else {
                segment(if (start < certain) speculativePath else path, x(start), yHere, x(end), yHere)
            }
            // Then the jump to the next balance, coloured by when it happened.
            if (index < points.size - 1) {
                val next = index + 1
                segment(if (points.epochs[next] < certain) speculativePath else path, x(end), yHere, x(end), y(next))
            }
        }
        canvas.drawPath(speculativePath, speculativePaint)
        canvas.drawPath(path, linePaint)
    }

    private fun segment(target: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        target.moveTo(x1, y1)
        target.lineTo(x2, y2)
    }

    /** Down the left edge, each just above its line, on a backing the graph can pass under. */
    private fun drawValueLabels(canvas: Canvas, values: List<Double>, view: GraphViewport, h: Float) {
        val pad = dpF(3f)
        values.forEach { value ->
            val y = view.yToPx(value, h)
            if (y < labelPaint.textSize + pad || y > h - pad) return@forEach
            val text = AmountFormat.axis(value.toLong())
            val textWidth = labelPaint.measureText(text)
            val left = dpF(4f)
            canvas.drawRect(left - pad, y - labelPaint.textSize - pad, left + textWidth + pad, y - pad / 2, labelBackPaint)
            canvas.drawText(text, left, y - pad, labelPaint)
        }
    }

    /**
     * Along ₹0 while it is on screen, pinned to the bottom edge while it is not -- where Desmos
     * keeps an axis's numbers once the axis itself has scrolled away.
     */
    private fun drawTimeLabels(
        canvas: Canvas,
        ticks: List<Pair<Long, String>>,
        view: GraphViewport,
        w: Float,
        h: Float,
        zeroY: Float,
        zeroVisible: Boolean
    ) {
        val pad = dpF(3f)
        val lineHeight = labelPaint.textSize + 2 * pad
        val baseline = if (zeroVisible && zeroY + lineHeight < h) zeroY + labelPaint.textSize + pad else h - pad * 2
        var lastRight = Float.NEGATIVE_INFINITY
        ticks.forEach { (epoch, label) ->
            val textWidth = labelPaint.measureText(label)
            val x = view.xToPx(epoch.toDouble(), w) + pad
            // Skipped rather than overlapped when two marks fall too close for both labels.
            if (x < lastRight + pad || x + textWidth > w) return@forEach
            canvas.drawRect(x - pad, baseline - labelPaint.textSize - pad / 2, x + textWidth + pad, baseline + pad, labelBackPaint)
            canvas.drawText(label, x, baseline, labelPaint)
            lastRight = x + textWidth
        }
    }

    /** The one marked point, and what happened there: when, the balance after, and the change. */
    private fun drawSelection(canvas: Canvas, points: BalancePoints, index: Int, view: GraphViewport, w: Float, h: Float) {
        val x = view.xToPx(points.epochs[index].toDouble(), w)
        val y = view.yToPx(points.balances[index].toDouble(), h)
        if (x < 0f || x > w || y < 0f || y > h) return
        canvas.drawCircle(x, y, dpF(4f), dotPaint)

        val title = AmountFormat.rupeesExact(points.balances[index])
        val change = if (index > 0) points.balances[index] - points.balances[index - 1] else null
        val sub = buildString {
            append(tipDateFormat.format(Date(points.epochs[index])))
            if (change != null) {
                append("  ")
                append(if (change >= 0) "+" else "−")
                append(AmountFormat.rupeesExact(abs(change)))
            }
        }
        val pad = dpF(8f)
        val boxWidth = maxOf(tipTextPaint.measureText(title), tipSubPaint.measureText(sub)) + 2 * pad
        val boxHeight = tipTextPaint.textSize + tipSubPaint.textSize + 2.5f * pad
        // Above and to the right of the point, turned back inside wherever it would leave the view.
        var left = x + pad
        var top = y - boxHeight - pad
        if (left + boxWidth > w) left = x - pad - boxWidth
        if (top < 0f) top = y + pad
        left = left.coerceIn(0f, (w - boxWidth).coerceAtLeast(0f))
        tipRect.set(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(tipRect, pad, pad, tipPaint)
        canvas.drawRoundRect(tipRect, pad, pad, tipBorderPaint)
        canvas.drawText(title, left + pad, top + pad + tipTextPaint.textSize, tipTextPaint)
        canvas.drawText(sub, left + pad, top + 1.5f * pad + tipTextPaint.textSize + tipSubPaint.textSize, tipSubPaint)
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        viewport?.let {
            putDoubleArray(KEY_VIEWPORT, doubleArrayOf(it.xMin, it.xMax, it.yMin, it.yMax))
            putString(KEY_DATA, dataKey)
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) return super.onRestoreInstanceState(state)
        @Suppress("DEPRECATION")
        super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
        state.getDoubleArray(KEY_VIEWPORT)?.let { (xMin, xMax, yMin, yMax) ->
            restored = GraphViewport(xMin, xMax, yMin, yMax)
            restoredKey = state.getString(KEY_DATA)
        }
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val RECENT_DAYS = 30L
        const val DAY_MILLIS = 86_400_000L
        const val ANIMATION_MS = 300L

        /** Majors at least this far apart; minors fall between. */
        const val MAJOR_SPACING_DP = 72f

        /** How close a tap has to land to a point to mean it. */
        const val TAP_RADIUS_DP = 28f

        /** Below this a finger span is noise, and dividing by it would leap the zoom. */
        const val MIN_PINCH_SPAN_PX = 24f

        const val KEY_SUPER = "super"
        const val KEY_VIEWPORT = "viewport"
        const val KEY_DATA = "data"
    }
}
