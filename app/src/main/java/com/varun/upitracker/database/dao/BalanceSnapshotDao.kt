package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.BalanceSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: BalanceSnapshot)

    @Update
    suspend fun update(snapshot: BalanceSnapshot)

    @Delete
    suspend fun delete(snapshot: BalanceSnapshot)

    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId = :accountId
        ORDER BY snapshotEpoch DESC
        """
    )
    fun getForAccount(accountId: String): Flow<List<BalanceSnapshot>>

    @Query("SELECT * FROM balance_snapshot WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BalanceSnapshot?

    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId = :accountId AND snapshotEpoch <= :atEpoch
        ORDER BY snapshotEpoch DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAtOrBefore(accountId: String, atEpoch: Long): BalanceSnapshot?

    /**
     * Every scoped account's snapshots in one range, for the trends balance line.
     *
     * Half-open `[fromEpoch, toEpoch)`, matching the two range DAOs this is merged with rather than
     * the exclusive-at-the-start convention the balance derivation above uses.
     *
     * One query rather than [getForAccount] per account: that returns a Flow, and collecting one
     * per account registers an invalidation observer apiece to answer a question asked once.
     *
     * Ordered so that two snapshots sharing an epoch reach the caller in a settled order. SQLite
     * leaves that tie unbroken in [getLatestAtOrBefore], so this does not decide it either -- it
     * only stops the order changing between reads.
     */
    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId IN (:accountIds)
          AND snapshotEpoch >= :fromEpoch
          AND snapshotEpoch < :toEpoch
        ORDER BY accountId ASC, snapshotEpoch ASC, id ASC
        """
    )
    suspend fun getSnapshotsBetween(
        accountIds: List<String>,
        fromEpoch: Long,
        toEpoch: Long
    ): List<BalanceSnapshot>

    /** Null when no scoped account has ever been reconciled. */
    @Query("SELECT MIN(snapshotEpoch) FROM balance_snapshot WHERE accountId IN (:accountIds)")
    suspend fun getEarliestSnapshotEpoch(accountIds: List<String>): Long?

    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId = :accountId
        ORDER BY snapshotEpoch ASC
        LIMIT 1
        """
    )
    suspend fun getEarliestForAccount(accountId: String): BalanceSnapshot?

    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId = :accountId AND snapshotEpoch > :atEpoch
        ORDER BY snapshotEpoch ASC
        LIMIT 1
        """
    )
    suspend fun getEarliestAfter(accountId: String, atEpoch: Long): BalanceSnapshot?
}
