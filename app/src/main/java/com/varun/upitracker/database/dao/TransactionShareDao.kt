package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.varun.upitracker.database.entity.TransactionShare

@Dao
interface TransactionShareDao {

    @Insert
    suspend fun insert(share: TransactionShare): Long

    @Insert
    suspend fun insertAll(shares: List<TransactionShare>)

    @Query("SELECT * FROM transaction_shares WHERE transactionId = :transactionId")
    suspend fun getSharesForTransaction(transactionId: Long): List<TransactionShare>

    @Query("DELETE FROM transaction_shares WHERE transactionId = :transactionId")
    suspend fun deleteForTransaction(transactionId: Long)
}
