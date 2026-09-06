package com.varun.upitracker.ui.statistics

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Turns a horizontal drag anywhere over its child into a period step, while leaving vertical drags
 * to the ScrollView inside it.
 *
 * The app's only other gesture -- the month swipe on the All Transactions header -- is a raw
 * OnTouchListener on one small TextView with nothing competing for the drag, and no bounds guard.
 * Neither property survives here, so this uses a different mechanism: the axis is decided in
 * [onInterceptTouchEvent], which is the one place a parent sees a gesture before its children.
 * Returning true from there also hands the ScrollView an ACTION_CANCEL for free, which does the
 * job the other implementation does by re-dispatching one by hand.
 */
class SwipeableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** -1 for the previous period, +1 for the next. */
    var onSwipe: ((Int) -> Unit)? = null

    /** False for All time and Custom, which have no neighbouring period to step to. */
    var isSwipeEnabled: Boolean = true

    /** False in the period containing now, so a forward drag is inert rather than silently wrong. */
    var canSwipeForward: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axisDecided = false
    private var isHorizontal = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isSwipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                axisDecided = false
                isHorizontal = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (axisDecided) return isHorizontal
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) < touchSlop && abs(dy) < touchSlop) return false
                // The first axis past slop wins the whole gesture. Requiring dominance rather
                // than mere horizontal movement is what stops a diagonal flick down a long legend
                // from turning the page under the user's thumb.
                axisDecided = true
                isHorizontal = abs(dx) > touchSlop && abs(dx) > abs(dy) * DOMINANCE
                return isHorizontal
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSwipeEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            // Only reached when no child claimed the DOWN, e.g. a drag begun on card padding.
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                // Total displacement at finger-up, sign only: no velocity and no minimum distance
                // beyond slop, so a slow deliberate drag works. Same acceptance rule as the month
                // swipe, which rejected a fling detector for exactly this reason.
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * DOMINANCE) {
                    // A finger travelling right walks back in time, the way a pager reads.
                    val direction = if (dx < 0) FORWARD else BACKWARD
                    if (direction == BACKWARD || canSwipeForward) onSwipe?.invoke(direction)
                }
                reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * A scrolling child that has claimed the gesture asks its parent for this. Honouring it before
     * the axis is settled would lock the horizontal decision out entirely. A plain ScrollView does
     * not ask, but a RecyclerView dropped onto this screen later would -- and would silently kill
     * the swipe -- so the guard lives here rather than in a comment.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Disabled means fully transparent to gestures -- onInterceptTouchEvent already declines
        // outright, so there is no axis decision left to protect and dropping the request would
        // only strand a child that legitimately needs the horizontal axis.
        if (isSwipeEnabled && !axisDecided) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun reset() {
        axisDecided = false
        isHorizontal = false
    }

    private companion object {
        const val DOMINANCE = 1.5f
        const val FORWARD = 1
        const val BACKWARD = -1
    }
}
