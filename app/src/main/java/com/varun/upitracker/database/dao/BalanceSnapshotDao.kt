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
