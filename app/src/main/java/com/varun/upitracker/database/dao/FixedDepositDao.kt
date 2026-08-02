package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.FixedDepositDetail

@Dao
interface FixedDepositDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(detail: FixedDepositDetail)

    @Update
    suspend fun update(detail: FixedDepositDetail)

    @Query("SELECT * FROM fixed_deposit_detail WHERE accountId = :accountId LIMIT 1")
    suspend fun getByAccountId(accountId: String): FixedDepositDetail?
}
