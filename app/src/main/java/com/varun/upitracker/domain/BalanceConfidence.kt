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
}
