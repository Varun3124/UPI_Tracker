package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.TransactionCategorySplit

@Dao
interface TransactionCategorySplitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(split: com.varun.upitracker.database.entity.TransactionCategorySplit)

    @Query("SELECT * FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun getForTransaction(txId: Long): List<com.varun.upitracker.database.entity.TransactionCategorySplit>

    @Query("SELECT * FROM transaction_category_splits WHERE categoryId = :categoryId")
    suspend fun getForCategory(categoryId: Long): List<com.varun.upitracker.database.entity.TransactionCategorySplit>

    @Query("SELECT * FROM transaction_category_splits WHERE transactionId = :txId AND categoryId = :categoryId LIMIT 1")
    suspend fun getByTransactionAndCategory(txId: Long, categoryId: Long): com.varun.upitracker.database.entity.TransactionCategorySplit?

    @Update
    suspend fun update(split: com.varun.upitracker.database.entity.TransactionCategorySplit)

    @Delete
    suspend fun delete(split: com.varun.upitracker.database.entity.TransactionCategorySplit)

    @Query("DELETE FROM transaction_category_splits WHERE transactionId = :txId")
    suspend fun deleteForTransaction(txId: Long)
}
