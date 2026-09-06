package com.varun.upitracker.data.repository

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.statistics.BalanceAnchor
import com.varun.upitracker.domain.statistics.BalanceMovement
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.toBalanceMovements

/**
 * Everything the balance line needs for one loaded span.
 *
 * Held rather than recomputed so that panning inside the span costs no database round trip: the
 * series for any window within `[spanFrom, spanToExclusive)` follows from these three collections
 * alone.
 */
data class BalanceSeriesInputs(
    val spanFrom: Long,
    val spanToExclusive: Long,
    val accountIds: Set<String>,
    /** Each account's balance immediately before [spanFrom], the shape `getBalance` answers in. */
    val openingByAccount: Map<String, Long>,
    val movements: List<BalanceMovement>,
    val anchorsByAccount: Map<String, List<BalanceAnchor>>
)

/**
 * Loads the trends charts' data.
 *
 * The balance side is deliberately **not** one `getBalance` call per point. That function fires
 * three or four statements per call and re-sums from scratch each time, so sixty points across five
 * accounts would be some nine hundred queries. This loads two range queries, one snapshot query and
 * one opening balance per account -- around twenty statements for the same span -- and lets
 * `BalanceTimeline` walk the movements once.
 */
class TrendsRepository internal constructor(private val source: TrendsDataSource) {

    constructor(db: AppDatabase) : this(RoomTrendsDataSource(db))

    /**
     * @param spanFrom inclusive, and never [Long.MIN_VALUE]: the opening balance is taken one
     *   millisecond earlier, which would overflow. Use `0L` for "from the beginning", the same
     *   choice `AllTransactionsViewModel.loadAllTime` makes and for the same reason.
     * @param spanToExclusive exclusive, matching the two range DAOs.
     */
    suspend fun loadBalanceInputs(
        spanFrom: Long,
        spanToExclusive: Long,
        accountIds: Set<String>
    ): BalanceSeriesInputs {
        require(spanFrom > Long.MIN_VALUE) { "spanFrom leaves no room for an opening balance." }
        if (accountIds.isEmpty()) {
            return BalanceSeriesInputs(spanFrom, spanToExclusive, accountIds, emptyMap(), emptyList(), emptyMap())
        }

        val ids = accountIds.toList()
        val entries =
            source.getTransactionsBetween(spanFrom, spanToExclusive).map(LedgerEntry::Tx) +
                source.getTransfersBetween(spanFrom, spanToExclusive).map(LedgerEntry::Transfer)

        return BalanceSeriesInputs(
            spanFrom = spanFrom,
            spanToExclusive = spanToExclusive,
            accountIds = accountIds,
            // One millisecond before the span: `getBalance` is inclusive at its upper bound, and a
            // movement dated exactly at the span start belongs inside the span, not before it.
            openingByAccount = accountIds.associateWith { source.getBalance(it, spanFrom - 1) },
            movements = entries.toBalanceMovements(accountIds),
            anchorsByAccount = source.getSnapshotsBetween(ids, spanFrom, spanToExclusive)
                .groupBy { it.accountId }
                .mapValues { (_, rows) -> rows.map { BalanceAnchor(it.snapshotEpoch, it.balancePaise) } }
        )
    }

    /**
     * The oldest thing the scope knows about, or null when it knows about nothing.
     *
     * Bounds panning. Scoped rather than global so that a single account opened last month stops
     * there instead of letting the user pan back through years of another account's flat line.
     */
    suspend fun earliestDataEpoch(accountIds: Set<String>): Long? {
        if (accountIds.isEmpty()) return null
        val ids = accountIds.toList()
        return listOfNotNull(
            source.earliestTransactionEpoch(ids),
            source.earliestTransferEpoch(ids),
            source.earliestSnapshotEpoch(ids)
        ).minOrNull()
    }
}

/** Narrowed to what the trends charts read, so the assembly above can be tested without Room. */
internal interface TrendsDataSource {
    suspend fun getTransactionsBetween(fromEpoch: Long, toEpochExclusive: Long): List<Transaction>
    suspend fun getTransfersBetween(fromEpoch: Long, toEpochExclusive: Long): List<AccountTransfer>
    suspend fun getSnapshotsBetween(
        accountIds: List<String>,
        fromEpoch: Long,
        toEpochExclusive: Long
    ): List<BalanceSnapshot>

    suspend fun earliestTransactionEpoch(accountIds: List<String>): Long?
    suspend fun earliestTransferEpoch(accountIds: List<String>): Long?
    suspend fun earliestSnapshotEpoch(accountIds: List<String>): Long?

    /** The one place the line touches the same derivation the Accounts screen shows. */
    suspend fun getBalance(accountId: String, atEpoch: Long): Long
}

private class RoomTrendsDataSource(private val db: AppDatabase) : TrendsDataSource {

    private val accountRepository = AccountRepository(db)

    override suspend fun getTransactionsBetween(fromEpoch: Long, toEpochExclusive: Long): List<Transaction> =
        db.transactionDao().getTransactionsBetweenSync(fromEpoch, toEpochExclusive)

    override suspend fun getTransfersBetween(fromEpoch: Long, toEpochExclusive: Long): List<AccountTransfer> =
        db.accountTransferDao().getTransfersBetween(fromEpoch, toEpochExclusive)

    override suspend fun getSnapshotsBetween(
        accountIds: List<String>,
        fromEpoch: Long,
        toEpochExclusive: Long
    ): List<BalanceSnapshot> =
        db.balanceSnapshotDao().getSnapshotsBetween(accountIds, fromEpoch, toEpochExclusive)

    override suspend fun earliestTransactionEpoch(accountIds: List<String>): Long? =
        db.transactionDao().getEarliestDateEpochForAccounts(accountIds)

    override suspend fun earliestTransferEpoch(accountIds: List<String>): Long? =
        db.accountTransferDao().getEarliestDateEpochForAccounts(accountIds)

    override suspend fun earliestSnapshotEpoch(accountIds: List<String>): Long? =
        db.balanceSnapshotDao().getEarliestSnapshotEpoch(accountIds)

    override suspend fun getBalance(accountId: String, atEpoch: Long): Long =
        accountRepository.getBalance(accountId, atEpoch)
}
