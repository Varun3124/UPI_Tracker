package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.TransactionCategorySplit

@Dao
interface TransactionCategorySplitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(split: TransactionCategorySplit)

    @Query("SELECT * FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun getForTransaction(txId: Long): List<TransactionCategorySplit>

    @Query("SELECT * FROM transaction_category_splits WHERE categoryId = :categoryId")
    suspend fun getForCategory(categoryId: Long): List<TransactionCategorySplit>

    @Query("SELECT * FROM transaction_category_splits WHERE transactionId = :txId AND categoryId = :categoryId LIMIT 1")
    suspend fun getByTransactionAndCategory(txId: Long, categoryId: Long): TransactionCategorySplit?

    @Update
    suspend fun update(split: TransactionCategorySplit)

    @Delete
    suspend fun delete(split: TransactionCategorySplit)

    @Query("DELETE FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun deleteForTransaction(txId: Long)
}
