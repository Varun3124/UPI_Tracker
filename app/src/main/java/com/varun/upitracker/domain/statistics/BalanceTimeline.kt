package com.varun.upitracker.domain.statistics

/** One movement on one account. The timeline never needs to know what caused it. */
data class BalanceMovement(val epoch: Long, val accountId: String, val deltaPaise: Long)

/** A reconciled balance: what one account was actually worth at one instant, however it got there. */
data class BalanceAnchor(val epoch: Long, val balancePaise: Long)

/**
 * A combined balance over time, one point per change: [balances] from each of [epochs] until the
 * next. Parallel, ascending, never empty.
 */
class BalancePoints(val epochs: LongArray, val balances: LongArray) {
    init {
        require(epochs.size == balances.size && epochs.isNotEmpty()) { "Points come in pairs, at least one." }
    }

    val size: Int get() = epochs.size

    /** The balance in force at [epoch]: the last point at or before it, or the first when before them all. */
    fun balanceAt(epoch: Long): Long {
        val index = indexAtOrBefore(epoch)
        return balances[if (index < 0) 0 else index]
    }

    /** The last point at or before [epoch], or -1 when every point is after it. */
    fun indexAtOrBefore(epoch: Long): Int {
        var lo = 0
        var hi = epochs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[mid] <= epoch) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }
}

/**
 * The balance of a set of accounts over time, one point per change.
 *
 * Written as a single pass over movements rather than one balance lookup per point:
 * `AccountRepository.getBalance` fires three or four statements per call, and a history has
 * thousands of points, each of which would re-sum what the one before it already summed.
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
     * The combined balance just after every moment it changed, from [spanFrom] on: one point per
     * transaction, transfer or reconciliation that moved it, which is what the Trends graph plots.
     *
     * Every event sharing an epoch is applied before that epoch's point is taken, so a payment and a
     * snapshot logged at one instant read as where the balance ended up, not as a spike. A moment
     * that leaves the total where it was -- a transfer between two accounts both in the scope --
     * adds no point: no money in the scope moved.
     *
     * The first point is always the opening balance at [spanFrom], so the line has somewhere to
     * start before its first change. The rules above apply, so every point agrees with the Accounts
     * screen at that instant.
     *
     * @param openingByAccount `getBalance(id, spanFrom - 1)` per in-scope account; missing is zero.
     */
    fun points(
        spanFrom: Long,
        accountIds: Set<String>,
        openingByAccount: Map<String, Long>,
        movements: List<BalanceMovement>,
        anchorsByAccount: Map<String, List<BalanceAnchor>> = emptyMap()
    ): BalancePoints {
        val running = HashMap<String, Long>(accountIds.size)
        accountIds.forEach { running[it] = openingByAccount[it] ?: 0L }
        var total = running.values.sum()

        val events = collectEvents(spanFrom, accountIds, movements, anchorsByAccount)
        val epochs = ArrayList<Long>(events.size + 1)
        val balances = ArrayList<Long>(events.size + 1)
        epochs += spanFrom
        balances += total

        var next = 0
        while (next < events.size) {
            val epoch = events[next].epoch
            while (next < events.size && events[next].epoch == epoch) {
                val event = events[next]
                val before = running[event.accountId] ?: 0L
                val after = event.anchorPaise ?: (before + event.deltaPaise)
                running[event.accountId] = after
                total += after - before
                next++
            }
            if (total == balances.last()) continue
            if (epoch == epochs.last()) {
                // Something at the opening instant itself: it is where the line opens.
                balances[balances.lastIndex] = total
            } else {
                epochs += epoch
                balances += total
            }
        }
        return BalancePoints(epochs.toLongArray(), balances.toLongArray())
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
