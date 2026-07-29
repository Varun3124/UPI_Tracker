package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.BalanceSnapshot

@Dao
interface BalanceSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: BalanceSnapshot)

    @Update
    suspend fun update(snapshot: BalanceSnapshot)

    @Query(
        """
        SELECT * FROM balance_snapshot
        WHERE accountId = :accountId AND snapshotEpoch <= :atEpoch
        ORDER BY snapshotEpoch DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAtOrBefore(accountId: String, atEpoch: Long): BalanceSnapshot?

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
