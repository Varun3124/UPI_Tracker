package com.varun.upitracker.domain

/**
 * Whether a balance is something the app was told or something it worked out.
 *
 * `AccountRepository.getBalance` answers in three ways, and only the first is history: from the
 * newest snapshot at or before the instant asked about, adding the movements since. The other two
 * -- running *backwards* from a later snapshot, or summing transactions from nothing -- are
 * reconstructions. They are only as complete as the transaction record, and the transaction record
 * before the first reconciliation is exactly the part nobody has ever checked. A balance that reads
 * the same either way is worth marking, because the user is the only one who can tell whether a
 * figure from that era is trustworthy.
 *
 * The dividing line is an account's **first** snapshot. Before it: speculation. From it: derived
 * from something counted.
 */
object BalanceConfidence {

    /**
     * The instant from which a combined balance stops being speculation.
     *
     * The **latest** of the accounts' first snapshots, not the earliest: a total is only as sound
     * as its weakest part, and until every account has been reconciled at least once the sum still
     * contains a reconstructed figure. One account never reconciled at all makes the combination
     * speculative for all time, which is what a null means here.
     */
    fun certainFrom(firstSnapshotEpochs: Collection<Long?>): Long? {
        if (firstSnapshotEpochs.isEmpty()) return null
        var latest = Long.MIN_VALUE
        firstSnapshotEpochs.forEach { epoch ->
            val known = epoch ?: return null
            if (known > latest) latest = known
        }
        return latest
    }

    /** True when [atEpoch] falls before anything was counted. A null [certainFrom] is always true. */
    fun isSpeculative(atEpoch: Long, certainFrom: Long?): Boolean =
        certainFrom == null || atEpoch < certainFrom

    /**
     * How many leading points of a chart series are speculation.
     *
     * Takes bucket *starts* and reasons about each bucket's last instant, because a point on this
     * chart is the balance at the end of its bucket -- a snapshot taken mid-bucket has already been
     * applied by the time the point is plotted.
     */
    fun speculativePointCount(
        bucketStarts: List<Long>,
        windowEndExclusive: Long,
        certainFrom: Long?
    ): Int {
        if (bucketStarts.isEmpty()) return 0
        if (certainFrom == null) return bucketStarts.size
        return bucketStarts.indices.count { index ->
            val pointEpoch = bucketEndExclusive(bucketStarts, windowEndExclusive, index) - 1
            pointEpoch < certainFrom
        }
    }

    /**
     * Where to draw the checkpoint across the plot, as a fraction of its width, or null when the
     * line would say nothing.
     *
     * Measured in slot space rather than in time, matching how the chart lays points out: bucket
     * `i` owns the fraction `[i/n, (i+1)/n)` whatever its duration in days, so a checkpoint inside
     * it is interpolated within its own slot. Without that a month bucket and a day bucket in the
     * same series would put the same date in different places.
     *
     * Null when nothing on screen is speculative (the line would sit on the left edge saying
     * nothing), when everything is (the brown line already says it), and when there is no
     * reconciliation to mark at all.
     */
    fun checkpointFraction(
        bucketStarts: List<Long>,
        windowEndExclusive: Long,
        certainFrom: Long?
    ): Float? {
        if (certainFrom == null || bucketStarts.isEmpty()) return null
        if (certainFrom <= bucketStarts.first()) return null
        if (certainFrom >= windowEndExclusive) return null

        val count = bucketStarts.size
        val index = bucketStarts.indexOfLast { it <= certainFrom }.coerceAtLeast(0)
        val start = bucketStarts[index]
        val end = bucketEndExclusive(bucketStarts, windowEndExclusive, index)
        val span = (end - start).coerceAtLeast(1L)
        val within = ((certainFrom - start).toDouble() / span).coerceIn(0.0, 1.0)
        return ((index + within) / count).toFloat().coerceIn(0f, 1f)
    }

    private fun bucketEndExclusive(
        bucketStarts: List<Long>,
        windowEndExclusive: Long,
        index: Int
    ): Long = if (index == bucketStarts.lastIndex) windowEndExclusive else bucketStarts[index + 1]
}
