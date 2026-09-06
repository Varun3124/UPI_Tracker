package com.varun.upitracker.domain.statistics

import kotlin.math.roundToInt

/**
 * Sliding the trends window through history.
 *
 * The period fixes how much is on screen; panning changes only where that window sits. Both charts
 * are driven from one window, which is what keeps them in lockstep -- the balance line and the
 * income bars would otherwise disagree about which week the user is looking at.
 *
 * Kept out of the views so the clamping can be asserted without a device, which matters because
 * both ends of the clamp are silent when wrong: panning past today draws empty buckets that look
 * like a run of zero-spend days, and panning before the data draws the opening balance forever.
 */
object PanMath {

    /**
     * Buckets to move for a drag of [dragPixels], or zero if the drag has not crossed one yet.
     *
     * Negated: content follows the finger, so dragging right walks *back* in time. That is the
     * direction `SwipeableFrameLayout` already steps the period in, and the two gestures live on
     * the same screen.
     */
    fun bucketDeltaFor(dragPixels: Float, plotWidthPixels: Float, visibleBuckets: Int): Int {
        if (plotWidthPixels <= 0f || visibleBuckets <= 0) return 0
        val bucketWidth = plotWidthPixels / visibleBuckets
        return -(dragPixels / bucketWidth).roundToInt()
    }

    /**
     * [proposedStart] snapped to a bucket boundary and held inside the data.
     *
     * The right edge is pinned to the bucket containing [nowEpoch] rather than to now itself, so the
     * current partial bucket is always the last column instead of being cut off.
     *
     * When there is less history than fits on screen the window still ends at now, leaving the empty
     * space on the left. Sliding *now* off the right edge to fit the data in would be the stranger
     * result of the two.
     */
    fun clampStart(
        proposedStart: Long,
        bucket: TrendBucket,
        visibleBuckets: Int,
        earliestEpoch: Long,
        nowEpoch: Long
    ): Long {
        require(visibleBuckets > 0) { "A window with no buckets cannot be positioned." }
        val latestStart = TrendsBuckets.addBuckets(
            bucket,
            TrendsBuckets.startOfBucket(bucket, nowEpoch),
            -(visibleBuckets - 1)
        )
        val earliestStart = TrendsBuckets.startOfBucket(bucket, earliestEpoch)
        val floor = minOf(earliestStart, latestStart)
        return TrendsBuckets.startOfBucket(bucket, proposedStart).coerceIn(floor, latestStart)
    }

    /** The window [visibleBuckets] wide that starts at [startEpoch]. */
    fun windowFrom(startEpoch: Long, bucket: TrendBucket, visibleBuckets: Int): TrendWindow {
        require(visibleBuckets > 0) { "A window with no buckets has no span." }
        val start = TrendsBuckets.startOfBucket(bucket, startEpoch)
        return TrendWindow(start, TrendsBuckets.addBuckets(bucket, start, visibleBuckets))
    }

    /**
     * Whether there is anywhere left to go in either direction.
     *
     * All time and a hand-picked range already show their whole span, so panning them is not
     * disabled by policy so much as by there being nothing outside the window.
     */
    fun canPan(
        startEpoch: Long,
        bucket: TrendBucket,
        visibleBuckets: Int,
        earliestEpoch: Long,
        nowEpoch: Long
    ): Boolean {
        val current = clampStart(startEpoch, bucket, visibleBuckets, earliestEpoch, nowEpoch)
        val back = clampStart(
            TrendsBuckets.addBuckets(bucket, current, -1),
            bucket, visibleBuckets, earliestEpoch, nowEpoch
        )
        val forward = clampStart(
            TrendsBuckets.addBuckets(bucket, current, 1),
            bucket, visibleBuckets, earliestEpoch, nowEpoch
        )
        return back != current || forward != current
    }
}
