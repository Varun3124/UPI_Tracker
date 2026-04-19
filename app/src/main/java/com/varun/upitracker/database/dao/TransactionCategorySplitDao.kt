package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.TransactionCategorySplit

@Dao
interface TransactionCategorySplitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(split: TransactionCategorySplit)

    @Query("SELECT * FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun getForTransaction(txId: Long): List<TransactionCategorySplit>

    @Query("DELETE FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun deleteForTransaction(txId: Long)
}