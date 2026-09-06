package com.varun.upitracker.domain.statistics

/** One movement on one account. The timeline never needs to know what caused it. */
data class BalanceMovement(val epoch: Long, val accountId: String, val deltaPaise: Long)

/** A reconciled balance: what one account was actually worth at one instant, however it got there. */
data class BalanceAnchor(val epoch: Long, val balancePaise: Long)

/**
 * The balance of a set of accounts at the end of each bucket.
 *
 * Written as a single pass over movements rather than one balance lookup per point:
 * `AccountRepository.getBalance` fires three or four statements per call, so sixty points across
 * five accounts would be some nine hundred queries, each re-summing what the previous point already
 * summed.
 *
 * **The line must agree with the Accounts screen**, which is the whole constraint here.
 * `getBalance` re-anchors on the nearest snapshot at or before the instant asked about, so a plain
 * cumulative sum from one opening figure would diverge from what that screen shows at every point
 * after a reconciliation. Each account re-anchors *independently*, so the combined line is the sum
 * of per-account running balances, not one combined running total -- a single total cannot express
 * "reset this account and leave the others alone".
 *
 * Two rules reproduce `getBalance` exactly:
 *  - an anchor replaces its account's running balance outright, discarding whatever came before it;
 *  - at an equal epoch the anchor lands *after* the movements, because `getBalance` at the snapshot
 *    instant sums `(snapshotEpoch, atEpoch]` and so counts nothing dated on the snapshot itself.
 */
object BalanceTimeline {

    /**
     * @param bucketStarts ascending, from [TrendsBuckets.bucketStarts].
     * @param windowEndExclusive the end of the last bucket; each point is one millisecond before the
     *   next bucket begins, so the series reads as "the balance you went to bed on".
     * @param openingByAccount `getBalance(id, bucketStarts.first() - 1)` per in-scope account. An
     *   account missing from the map opens at zero.
     * @param movements every movement in the window, both legs of an internal transfer included.
     * @param anchorsByAccount that account's snapshots; each list ascending by epoch.
     * @return one balance per bucket, parallel to [bucketStarts].
     */
    fun build(
        bucketStarts: List<Long>,
        windowEndExclusive: Long,
        accountIds: Set<String>,
        openingByAccount: Map<String, Long>,
        movements: List<BalanceMovement>,
        anchorsByAccount: Map<String, List<BalanceAnchor>> = emptyMap()
    ): List<Long> {
        if (bucketStarts.isEmpty()) return emptyList()
        val windowStart = bucketStarts.first()
        require(windowEndExclusive > bucketStarts.last()) {
            "The last bucket must have room to end: $windowEndExclusive <= ${bucketStarts.last()}."
        }

        val running = HashMap<String, Long>(accountIds.size)
        accountIds.forEach { running[it] = openingByAccount[it] ?: 0L }

        val events = collectEvents(windowStart, accountIds, movements, anchorsByAccount)

        var next = 0
        return bucketStarts.indices.map { index ->
            val pointEpoch =
                (if (index == bucketStarts.lastIndex) windowEndExclusive else bucketStarts[index + 1]) - 1
            while (next < events.size && events[next].epoch <= pointEpoch) {
                val event = events[next]
                running[event.accountId] = when (event.anchorPaise) {
                    null -> (running[event.accountId] ?: 0L) + event.deltaPaise
                    else -> event.anchorPaise
                }
                next++
            }
            running.values.sum()
        }
    }

    /**
     * Everything in the window that can move a running balance, in the order it has to be applied.
     *
     * Both kinds are filtered to the window rather than trusted: the opening balance already
     * contains every movement and every anchor before it, so applying an earlier one again would
     * double-count it. Anchors especially, since an account's snapshots are naturally loaded in
     * full rather than by range.
     *
     * Movements sharing an epoch may be applied in any order -- addition commutes, and an anchor at
     * that same epoch discards all of them together.
     */
    private fun collectEvents(
        windowStart: Long,
        accountIds: Set<String>,
        movements: List<BalanceMovement>,
        anchorsByAccount: Map<String, List<BalanceAnchor>>
    ): List<Event> {
        val events = ArrayList<Event>(movements.size)
        movements.forEach { movement ->
            if (movement.epoch >= windowStart && movement.accountId in accountIds) {
                events.add(Event(movement.epoch, movement.accountId, movement.deltaPaise, null))
            }
        }
        accountIds.forEach { accountId ->
            anchorsByAccount[accountId].orEmpty().forEach { anchor ->
                if (anchor.epoch >= windowStart) {
                    events.add(Event(anchor.epoch, accountId, 0L, anchor.balancePaise))
                }
            }
        }
        // Stable, so two anchors on one account at the same epoch resolve to the later of the list.
        // SQLite's own "ORDER BY snapshotEpoch DESC LIMIT 1" leaves that tie unbroken too, so the
        // ambiguity is inherited rather than introduced.
        return events.sortedWith(compareBy({ it.epoch }, { if (it.anchorPaise != null) 1 else 0 }))
    }

    private class Event(
        val epoch: Long,
        val accountId: String,
        val deltaPaise: Long,
        val anchorPaise: Long?
    )
}
