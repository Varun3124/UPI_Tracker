package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.TransactionParty

@Dao
interface TransactionPartyDao {

    @Insert
    suspend fun insert(party: TransactionParty)

    @Delete
    suspend fun delete(party: TransactionParty)

    @Query("SELECT * FROM transaction_parties WHERE transactionId = :transactionId")
    suspend fun getPartiesForTransaction(transactionId: Long): List<TransactionParty>

    // Total spent on a specific friend across all transactions
    @Query("""
        SELECT SUM(spentOnThemPaise) FROM transaction_parties 
        WHERE friendId = :friendId
    """)
    suspend fun getTotalSpentOnFriend(friendId: Long): Long?

    // How many transactions this friend has been part of
    @Query("""
        SELECT COUNT(*) FROM transaction_parties 
        WHERE friendId = :friendId
    """)
    suspend fun getTransactionCountForFriend(friendId: Long): Int

    @Query("DELETE FROM transaction_parties WHERE transactionId = :txId")
    suspend fun deleteForTransaction(txId: Long)
}