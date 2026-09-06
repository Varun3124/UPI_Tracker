package com.varun.upitracker.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.varun.upitracker.domain.statistics.PanMath
import kotlin.math.abs

/**
 * A chart that slides through time under a horizontal drag.
 *
 * The axis arbitration is the same rule [SwipeableFrameLayout] uses on the Categories side -- the
 * first axis past slop wins the whole gesture, and horizontal has to dominate by [DOMINANCE] to
 * take it -- so a diagonal flick down a long page still scrolls rather than jumping the window.
 * Written again here rather than shared with that class because the two solve opposite problems: it
 * is a parent deciding *before* its children see a gesture, this is a child claiming one *from* its
 * parent.
 *
 * Reports the drag **cumulatively**, not as increments. The window is then always computed from
 * where it stood when the finger went down, so a drag that runs into the end of the data and comes
 * back moves again immediately, instead of first unwinding the steps the clamp refused.
 */
abstract class PannableChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** The finger has claimed the horizontal axis; the window start is captured here. */
    var onPanStart: (() -> Unit)? = null

    /** Buckets moved since [onPanStart], recomputed from scratch on every move. */
    var onPan: ((Int) -> Unit)? = null

    /** The finger has lifted or the gesture was taken away; the captured origin can be dropped. */
    var onPanEnd: (() -> Unit)? = null

    /** False for the periods that already show their whole range, where there is nowhere to go. */
    var isPanEnabled: Boolean = false

    /** How many buckets are on screen. Subclasses set this whenever their series changes. */
    protected var panBucketCount: Int = 0

    /**
     * The drawable width between the axis gutter and the right edge, in pixels.
     *
     * Set from `onDraw`, which is the only place either subclass works it out -- the gutter is
     * measured from the widest axis label rather than fixed. A view has always been drawn before it
     * can be touched, so this is populated by the time a gesture arrives; until then a drag maps to
     * no buckets and does nothing.
     */
    protected var panPlotWidthPx: Float = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axisDecided = false
    private var isHorizontal = false

    final override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isPanEnabled || panBucketCount <= 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                axisDecided = false
                isHorizontal = false
                // Claiming the DOWN is what makes the later moves arrive at all. The ScrollView
                // still gets first refusal on each one until the axis is settled, so a vertical
                // drag is stolen back with an ACTION_CANCEL exactly as it would be otherwise.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!axisDecided) {
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) return true
                    axisDecided = true
                    isHorizontal = abs(dx) > touchSlop && abs(dx) > abs(dy) * DOMINANCE
                    if (isHorizontal) {
                        // Without this the ScrollView takes any drag with a vertical component.
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onPanStart?.invoke()
                    }
                }
                if (isHorizontal) {
                    onPan?.invoke(PanMath.bucketDeltaFor(dx, panPlotWidthPx, panBucketCount))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHorizontal) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onPanEnd?.invoke()
                }
                axisDecided = false
                isHorizontal = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Subclasses draw here; the base class only needs the plot width they worked out. */
    abstract override fun onDraw(canvas: Canvas)

    private companion object {
        /** The ratio SwipeableFrameLayout uses, so both gestures feel the same. */
        const val DOMINANCE = 1.5f
    }
}
