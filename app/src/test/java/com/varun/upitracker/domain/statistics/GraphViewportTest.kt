package com.varun.upitracker.domain.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphViewportTest {

    private val w = 1_000f
    private val h = 500f
    private val day = 86_400_000.0

    /** Ten days across, ₹0 to ₹1,000 up. */
    private val view = GraphViewport(0.0, 10 * day, 0.0, 100_000.0)

    @Test
    fun `a drag moves the graph with the finger`() {
        // A tenth of the width right and a fifth of the height down.
        val moved = view.panBy(100f, 100f, w, h)
        assertEquals(-1 * day, moved.xMin, 1.0)
        assertEquals(9 * day, moved.xMax, 1.0)
        assertEquals(20_000.0, moved.yMin, 0.001)
        assertEquals(120_000.0, moved.yMax, 0.001)
    }

    @Test
    fun `a zoom keeps the point between the fingers still`() {
        val focusX = 250f
        val focusY = 400f
        val before = view.pxToX(focusX, w) to view.pxToY(focusY, h)
        val zoomed = view.zoomAt(focusX, focusY, 2.0, 3.0, w, h)
        assertEquals(before.first, zoomed.pxToX(focusX, w), 1.0)
        assertEquals(before.second, zoomed.pxToY(focusY, h), 0.001)
        assertEquals(view.xSpan / 2, zoomed.xSpan, 1.0)
        assertEquals(view.ySpan / 3, zoomed.ySpan, 0.001)
    }

    @Test
    fun `pixels and values go back and forth`() {
        assertEquals(500f, view.xToPx(5 * day, w), 0.01f)
        assertEquals(0f, view.yToPx(100_000.0, h), 0.01f)   // the top is the highest balance
        assertEquals(h, view.yToPx(0.0, h), 0.01f)
    }

    @Test
    fun `fingers side by side zoom time, stacked zoom balance, between zoom both`() {
        assertEquals(PinchAxes.X, GraphViewport.pinchAxes(300f, 0f))
        assertEquals(PinchAxes.X, GraphViewport.pinchAxes(300f, 100f))   // about 18°
        assertEquals(PinchAxes.Y, GraphViewport.pinchAxes(0f, 300f))
        assertEquals(PinchAxes.Y, GraphViewport.pinchAxes(-80f, 300f))   // about 75°
        assertEquals(PinchAxes.BOTH, GraphViewport.pinchAxes(200f, 200f))
        assertEquals(PinchAxes.BOTH, GraphViewport.pinchAxes(-200f, 150f))
    }

    private val bounds = GraphBounds(dataXMin = 0.0, dataXMax = 100 * day, dataYMin = 0.0, dataYMax = 1_000_000.0)

    @Test
    fun `zooming in stops at an hour across and ten rupees up`() {
        val tiny = GraphViewport(5 * day, 5 * day + 1_000.0, 50_000.0, 50_010.0).clamped(bounds)
        assertEquals(3_600_000.0, tiny.xSpan, 0.001)
        assertEquals(1_000.0, tiny.ySpan, 0.001)
    }

    @Test
    fun `zooming out stops at four times the history`() {
        val huge = GraphViewport(-1_000 * day, 1_000 * day, 0.0, 1_000_000.0).clamped(bounds)
        assertEquals(400 * day, huge.xSpan, 1.0)
    }

    @Test
    fun `the graph cannot be dragged so far that the data is lost`() {
        val far = GraphViewport(10_000 * day, 10_010 * day, 0.0, 100_000.0).clamped(bounds)
        val centre = (far.xMin + far.xMax) / 2
        assertTrue(centre <= bounds.dataXMax + far.xSpan)
    }

    @Test
    fun `a clamp leaves a view that is already fine alone`() {
        val ok = GraphViewport(10 * day, 40 * day, 100_000.0, 900_000.0)
        assertEquals(ok, ok.clamped(bounds))
    }

    private val points = BalancePoints(
        epochs = longArrayOf(0L, (10 * day).toLong(), (20 * day).toLong(), (30 * day).toLong()),
        balances = longArrayOf(50_000L, 80_000L, 20_000L, 60_000L)
    )

    @Test
    fun `fitting covers the points shown and the balance carried into the window`() {
        // From day 15: the 80_000 set on day 10 is still in force at the left edge.
        val fitted = GraphViewport.fit(points, (15 * day).toLong(), (40 * day).toLong(), padding = 0.0)
        assertEquals(15 * day, fitted.xMin, 1.0)
        assertEquals(20_000.0, fitted.yMin, 0.001)
        assertEquals(80_000.0, fitted.yMax, 0.001)
    }

    @Test
    fun `fitting a flat stretch still gives it some height`() {
        val flat = BalancePoints(longArrayOf(0L), longArrayOf(50_000L))
        val fitted = GraphViewport.fit(flat, 0L, (30 * day).toLong(), padding = 0.0)
        assertTrue(fitted.ySpan > 0.0)
        assertTrue(fitted.yMin < 50_000.0 && fitted.yMax > 50_000.0)
    }
}
