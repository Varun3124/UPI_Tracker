package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.model.MerchantCategoryUse

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

    /**
     * Every category each merchant's confirmed transactions have been filed under, per direction:
     * expense splits of payments to the merchant, income splits of money from it. The same
     * direction rule the reporting queries apply, so a credit's leftover expense split from before
     * refunds were linked never counts as the merchant's habit. Linked refunds are left out -- they
     * borrow the purchase's categories rather than choosing their own.
     *
     * [merchantId] null reads every merchant at once, for the entry screen's suggestions.
     */
    @Query(
        """
        SELECT DISTINCT
            CASE WHEN c.kind = 'EXPENSE' THEN t.payeeMerchantId ELSE t.payerMerchantId END AS merchantId,
            c.kind AS kind,
            cs.categoryId AS categoryId
        FROM transaction_category_splits cs
        INNER JOIN transactions t ON t.id = cs.transactionId
        INNER JOIN categories c ON c.id = cs.categoryId
        WHERE t.isPending = 0
          AND t.refundsTransactionId IS NULL
          AND (
            (c.kind = 'EXPENSE' AND t.payeeActorType = 'MERCHANT' AND t.payerActorType != 'MERCHANT'
                AND t.payeeMerchantId IS NOT NULL
                AND (:merchantId IS NULL OR t.payeeMerchantId = :merchantId))
            OR (c.kind = 'INCOME' AND t.payerActorType = 'MERCHANT' AND t.payeeActorType != 'MERCHANT'
                AND t.payerMerchantId IS NOT NULL
                AND (:merchantId IS NULL OR t.payerMerchantId = :merchantId))
          )
        """
    )
    suspend fun getMerchantCategoryUses(merchantId: Long?): List<MerchantCategoryUse>
}
