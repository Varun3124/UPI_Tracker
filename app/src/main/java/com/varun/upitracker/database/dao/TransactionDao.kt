package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.varun.upitracker.database.entity.Transaction

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC")
    fun getAllTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    fun getTransactionsSince(fromEpoch: Long): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE isPending = 1 ORDER BY dateEpoch DESC, id DESC")
    fun getPendingTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE upiRefId = :refId LIMIT 1")
    suspend fun findByRefId(refId: String): Transaction?

    @Query("""
        SELECT SUM(amountPaise) FROM transactions 
        WHERE direction = 'DEBIT' AND dateEpoch >= :fromEpoch
    """)
    suspend fun getTotalDebitSince(fromEpoch: Long): Long?

    @Query("""
        SELECT * FROM transactions 
        WHERE (resolvedFriendId = :friendId OR resolvedMerchantId = :merchantId)
        ORDER BY dateEpoch DESC, id DESC
    """)
    fun getTransactionsForEntity(friendId: Long?, merchantId: Long?): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): Transaction?

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    suspend fun getTransactionsSinceSync(fromEpoch: Long): List<Transaction>

    @Query("""
    SELECT DISTINCT t.* FROM transactions t
    LEFT JOIN iou_entries i ON t.id = i.transactionId AND i.friendId = :friendId
    LEFT JOIN transaction_parties p ON t.id = p.transactionId AND p.friendId = :friendId
    WHERE t.resolvedFriendId = :friendId OR i.friendId = :friendId OR p.friendId = :friendId
    ORDER BY t.dateEpoch DESC, t.id DESC
""")
    suspend fun getTransactionsForFriendSync(friendId: Long): List<Transaction>
}