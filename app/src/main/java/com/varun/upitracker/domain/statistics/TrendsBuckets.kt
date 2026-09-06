package com.varun.upitracker.domain.statistics

/**
 * A half-open span, `[startInclusive, endExclusive)`.
 *
 * Deliberately the opposite convention from [DateRange], which is `(fromExclusive, toInclusive]`.
 * Buckets have to tile with no gap and no overlap, and half-open is the shape that does that: one
 * bucket's end is literally the next one's start, with no millisecond to lose. [asDateRange] is the
 * only place the conversion is written, so no caller has to get the -1 right by hand.
 */
data class TrendWindow(val startInclusive: Long, val endExclusive: Long) {

    val spanMillis: Long get() = endExclusive - startInclusive

    val isEmpty: Boolean get() = endExclusive <= startInclusive

    /**
     * This window clipped to [bounds].
     *
     * An edge bucket reaches outside the period it belongs to -- a quarter's first week begins in
     * the previous month, its last ends in the next -- and a bar drawn over the whole of one would
     * count days the period does not contain, leaving the chart's total above the pie's for the
     * same period. Clipping keeps the buckets tiling exactly the window and nothing more.
     */
    fun within(bounds: TrendWindow): TrendWindow = TrendWindow(
        maxOf(startInclusive, bounds.startInclusive),
        minOf(endExclusive, bounds.endExclusive)
    )

    fun asDateRange(): DateRange = DateRange(startInclusive - 1, endExclusive - 1)
}

/** The unit of one point on the trends X axis. Finer than the period, which sets the visible width. */
enum class TrendBucket { HOUR, DAY, WEEK, MONTH }

/**
 * Bucketing for the trends charts.
 *
 * The period sets how much history is on screen; the bucket is the finer unit inside it. Every
 * boundary comes from [StatisticsPeriods] rather than from a millisecond stride, so a DST day of 23
 * or 25 hours cannot slide the later buckets off midnight -- the same reasoning the weekly stacked
 * bar already depends on.
 *
 * Buckets are produced by *tiling*, never by count: a DST day genuinely holds 23 or 25 hour buckets,
 * and a month holds 28 to 31 day buckets. Tiling gets that right without a special case.
 */
object TrendsBuckets {

    /** Past this, a hand-picked range buckets by week -- 90 day columns would be unreadable. */
    const val CUSTOM_WEEK_THRESHOLD_DAYS = 90

    /**
     * X labels beyond this collide at phone widths.
     *
     * Seven because a week is the canonical case and has to label every column, the way the weekly
     * stacked bar already does. A chart whose labels are full dates rather than day names passes a
     * tighter number of its own.
     */
    private const val MAX_X_LABELS = 7

    /**
     * The visible window for a period anchored at [anchorEpoch].
     *
     * Shiftable periods only. [StatisticsPeriods.startOf] and `shift` are silent no-ops for
     * `ALL_TIME` and `CUSTOM`, so asking for their window here would return the anchor's own instant
     * twice over; `rangeFor(ALL_TIME)` additionally answers `Long.MIN_VALUE`, which re-arms the
     * documented overflow the moment anything subtracts one from it. Those two carry their window
     * explicitly instead.
     */
    fun windowFor(period: StatsPeriod, anchorEpoch: Long): TrendWindow {
        require(period.isShiftable) {
            "$period has no period arithmetic; pass its window explicitly."
        }
        val range = StatisticsPeriods.rangeFor(period, anchorEpoch)
        return TrendWindow(range.fromExclusive + 1, range.toInclusive + 1)
    }

    /**
     * The visible window for any period, including the two that carry their own range.
     *
     * `ALL_TIME` is bounded by [earliestEpoch] rather than by the epoch itself: month buckets from
     * 1970 would be six hundred columns of nothing, and the left edge must never be
     * `Long.MIN_VALUE`, which [StatisticsPeriods.rangeFor] answers and which overflows the moment
     * an opening balance is taken one millisecond earlier.
     *
     * @param earliestEpoch the oldest thing the scope knows about; null when it knows nothing, and
     *   `ALL_TIME` then shows the current month alone rather than an empty span.
     */
    fun visibleWindow(
        period: StatsPeriod,
        anchorEpoch: Long,
        customFromInclusive: Long,
        customToInclusive: Long,
        earliestEpoch: Long?,
        nowEpoch: Long
    ): TrendWindow = when (period) {
        StatsPeriod.ALL_TIME -> TrendWindow(
            StatisticsPeriods.startOfMonth(earliestEpoch ?: nowEpoch),
            StatisticsPeriods.addMonths(StatisticsPeriods.startOfMonth(nowEpoch), 1)
        )
        StatsPeriod.CUSTOM -> TrendWindow(
            StatisticsPeriods.startOfDay(customFromInclusive),
            StatisticsPeriods.addDays(StatisticsPeriods.startOfDay(customToInclusive), 1)
        )
        else -> windowFor(period, anchorEpoch)
    }

    /**
     * How finely to divide [window] for [period].
     *
     * [window] is only read for `CUSTOM`, whose span is the user's choice rather than the period's.
     */
    fun bucketFor(period: StatsPeriod, window: TrendWindow): TrendBucket = when (period) {
        StatsPeriod.DAILY -> TrendBucket.HOUR
        StatsPeriod.WEEKLY, StatsPeriod.MONTHLY -> TrendBucket.DAY
        StatsPeriod.QUARTERLY -> TrendBucket.WEEK
        StatsPeriod.ALL_TIME -> TrendBucket.MONTH
        // Nominal days, not calendar ones: an hour of DST slop cannot move a 90-day threshold
        // across a boundary that matters, and the exact count is not what is being decided.
        StatsPeriod.CUSTOM ->
            if (window.spanMillis / StatisticsPeriods.DAY_MILLIS > CUSTOM_WEEK_THRESHOLD_DAYS) {
                TrendBucket.WEEK
            } else {
                TrendBucket.DAY
            }
    }

    fun startOfBucket(bucket: TrendBucket, epoch: Long): Long = when (bucket) {
        TrendBucket.HOUR -> StatisticsPeriods.startOfHour(epoch)
        TrendBucket.DAY -> StatisticsPeriods.startOfDay(epoch)
        TrendBucket.WEEK -> StatisticsPeriods.startOfWeek(epoch)
        TrendBucket.MONTH -> StatisticsPeriods.startOfMonth(epoch)
    }

    fun addBuckets(bucket: TrendBucket, epoch: Long, count: Int): Long = when (bucket) {
        TrendBucket.HOUR -> StatisticsPeriods.addHours(epoch, count)
        TrendBucket.DAY -> StatisticsPeriods.addDays(epoch, count)
        TrendBucket.WEEK -> StatisticsPeriods.addDays(epoch, count * 7)
        TrendBucket.MONTH -> StatisticsPeriods.addMonths(epoch, count)
    }

    /**
     * The bucket starts covering [window], ascending.
     *
     * The first is snapped back to its own boundary, so a window that begins mid-bucket still starts
     * on one. Every subsequent start is the previous bucket's end, which is what makes the columns
     * abut.
     */
    fun bucketStarts(bucket: TrendBucket, window: TrendWindow): List<Long> {
        if (window.endExclusive <= window.startInclusive) return emptyList()
        val starts = ArrayList<Long>()
        var cursor = startOfBucket(bucket, window.startInclusive)
        while (cursor < window.endExclusive) {
            starts.add(cursor)
            val next = addBuckets(bucket, cursor, 1)
            // Cannot happen with calendar arithmetic; the loop would not terminate if it ever did.
            check(next > cursor) { "Bucket $bucket did not advance from $cursor." }
            cursor = next
        }
        return starts
    }

    /** The window one bucket's worth of [bucket] holds, for a per-bucket aggregate query. */
    fun bucketWindow(bucket: TrendBucket, bucketStartEpoch: Long): TrendWindow =
        TrendWindow(bucketStartEpoch, addBuckets(bucket, bucketStartEpoch, 1))

    /**
     * Label every nth bucket, so that at most [maxLabels] of them are labelled. Never zero, so
     * `index % stride` is always safe.
     *
     * A month of day buckets cannot carry thirty dates across a phone; the axis reads by labelling
     * a few and letting the gridlines carry the rest.
     */
    fun labelStride(bucketCount: Int, maxLabels: Int = MAX_X_LABELS): Int {
        if (bucketCount <= maxLabels || maxLabels <= 0) return 1
        return (bucketCount + maxLabels - 1) / maxLabels
    }
}
