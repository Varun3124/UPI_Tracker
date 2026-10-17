package com.varun.upitracker.domain.statistics

import kotlin.math.abs
import kotlin.math.atan2

/**
 * The stretch of the balance graph on screen: time across, in epoch millis, and balance up, in
 * paise. Doubles, so a drag or a pinch moves it by less than a pixel's worth.
 *
 * Immutable: every gesture makes a new one, which is what lets the maths here be tested without a
 * screen. Pixel arguments are measured from the plot's top-left corner, y growing downward the way
 * the screen does, while balance grows upward the way a graph does.
 */
data class GraphViewport(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double
) {
    val xSpan: Double get() = xMax - xMin
    val ySpan: Double get() = yMax - yMin

    fun xToPx(x: Double, widthPx: Float): Float = ((x - xMin) / xSpan * widthPx).toFloat()

    fun yToPx(y: Double, heightPx: Float): Float = ((yMax - y) / ySpan * heightPx).toFloat()

    fun pxToX(px: Float, widthPx: Float): Double = xMin + px.toDouble() / widthPx * xSpan

    fun pxToY(px: Float, heightPx: Float): Double = yMax - px.toDouble() / heightPx * ySpan

    /**
     * Follows a finger that moved [dxPx] right and [dyPx] down: what was under it stays under it, so
     * the graph moves the opposite way through time and balance.
     */
    fun panBy(dxPx: Float, dyPx: Float, widthPx: Float, heightPx: Float): GraphViewport {
        // In Double from the start: a Float ratio loses a millisecond a pixel at a year's width.
        val dx = dxPx.toDouble() / widthPx * xSpan
        val dy = dyPx.toDouble() / heightPx * ySpan
        return GraphViewport(xMin - dx, xMax - dx, yMin + dy, yMax + dy)
    }

    /**
     * Zooms in by [scaleX] across and [scaleY] up -- above one is closer -- keeping the point under
     * the fingers ([focusXPx], [focusYPx]) where it is, the way a pinch on a map does.
     */
    fun zoomAt(
        focusXPx: Float,
        focusYPx: Float,
        scaleX: Double,
        scaleY: Double,
        widthPx: Float,
        heightPx: Float
    ): GraphViewport {
        val fx = pxToX(focusXPx, widthPx)
        val fy = pxToY(focusYPx, heightPx)
        return GraphViewport(
            xMin = fx - (fx - xMin) / scaleX,
            xMax = fx + (xMax - fx) / scaleX,
            yMin = fy - (fy - yMin) / scaleY,
            yMax = fy + (yMax - fy) / scaleY
        )
    }

    /**
     * Held to [bounds]: neither zoomed past what can be read nor so far out the line is a speck, and
     * never moved so far off the data that it is lost. A span over its limit is brought back about
     * its own centre, so a pinch that runs into the limit stops rather than drifts.
     */
    fun clamped(bounds: GraphBounds): GraphViewport {
        val xs = xSpan.coerceIn(bounds.minXSpan, bounds.maxXSpan)
        val ys = ySpan.coerceIn(bounds.minYSpan, bounds.maxYSpan)
        val cx = ((xMin + xMax) / 2).coerceIn(bounds.dataXMin - xs, bounds.dataXMax + xs)
        val cy = ((yMin + yMax) / 2).coerceIn(bounds.dataYMin - ys, bounds.dataYMax + ys)
        return GraphViewport(cx - xs / 2, cx + xs / 2, cy - ys / 2, cy + ys / 2)
    }

    companion object {
        /**
         * `[fromEpoch, toEpoch]` across, and up the balances shown there with [padding] above and
         * below: the points inside, plus the balance already in force at [fromEpoch], since a step
         * line is drawn at that height from the left edge until its first point.
         */
        fun fit(points: BalancePoints, fromEpoch: Long, toEpoch: Long, padding: Double = 0.1): GraphViewport {
            var lo = points.balanceAt(fromEpoch)
            var hi = lo
            val first = points.indexAtOrBefore(fromEpoch) + 1
            for (index in first until points.size) {
                if (points.epochs[index] > toEpoch) break
                lo = minOf(lo, points.balances[index])
                hi = maxOf(hi, points.balances[index])
            }
            // A flat stretch still needs some height, or the line would sit on a zero-height axis.
            val span = maxOf((hi - lo).toDouble(), maxOf(abs(hi.toDouble()) * 0.1, MIN_FIT_SPAN_PAISE))
            val mid = (hi + lo) / 2.0
            val half = span * (0.5 + padding)
            return GraphViewport(fromEpoch.toDouble(), toEpoch.toDouble(), mid - half, mid + half)
        }

        /**
         * Which axes a pinch zooms, from where the two fingers sat when it began: side by side
         * zooms time alone, one above the other zooms balance alone, and anything between zooms
         * both -- the rule Desmos uses. Decided once per pinch, so a pinch that rotates a little as it
         * goes does not start zooming the other axis halfway through.
         */
        fun pinchAxes(dxPx: Float, dyPx: Float): PinchAxes {
            val degrees = Math.toDegrees(atan2(abs(dyPx).toDouble(), abs(dxPx).toDouble()))
            return when {
                degrees <= AXIS_LOCK_DEGREES -> PinchAxes.X
                degrees >= 90 - AXIS_LOCK_DEGREES -> PinchAxes.Y
                else -> PinchAxes.BOTH
            }
        }

        /** Within this many degrees of an axis, a pinch is taken to mean that axis alone. */
        const val AXIS_LOCK_DEGREES = 25.0

        /** ₹100: how tall the window is when every balance in it is the same. */
        private const val MIN_FIT_SPAN_PAISE = 10_000.0
    }
}

enum class PinchAxes { X, Y, BOTH }

/**
 * Where the data is, and therefore how far the graph may be moved and zoomed.
 *
 * Time zooms from an hour across to four times the whole history, and at least two months, so a
 * new account's few days of data can still be seen in context. Balance zooms from ₹10 tall to fifty
 * times the range the data covers.
 */
data class GraphBounds(
    val dataXMin: Double,
    val dataXMax: Double,
    val dataYMin: Double,
    val dataYMax: Double
) {
    val minXSpan: Double get() = HOUR_MILLIS
    val maxXSpan: Double get() = maxOf(4 * (dataXMax - dataXMin), 60 * DAY_MILLIS)
    val minYSpan: Double get() = MIN_Y_SPAN_PAISE
    val maxYSpan: Double get() = maxOf(50 * (dataYMax - dataYMin), 50 * abs(dataYMax), MIN_MAX_Y_SPAN_PAISE)

    companion object {
        fun of(points: BalancePoints, nowEpoch: Long): GraphBounds =
            GraphBounds(
                dataXMin = points.epochs.first().toDouble(),
                dataXMax = maxOf(nowEpoch, points.epochs.last()).toDouble(),
                dataYMin = points.balances.min().toDouble(),
                dataYMax = points.balances.max().toDouble()
            )

        private const val HOUR_MILLIS = 3_600_000.0
        private const val DAY_MILLIS = 86_400_000.0

        /** ₹10. */
        private const val MIN_Y_SPAN_PAISE = 1_000.0

        /** ₹10,000: the most a flat or empty history can be zoomed out to. */
        private const val MIN_MAX_Y_SPAN_PAISE = 1_000_000.0
    }
}
