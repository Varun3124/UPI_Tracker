package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.varun.upitracker.database.entity.IouEntry

@Dao
interface IouDao {

    @Insert
    suspend fun insert(entry: IouEntry)

    @Update
    suspend fun update(entry: IouEntry)

    @Query("SELECT * FROM iou_entries WHERE friendId = :friendId AND isSettled = 0")
    fun getUnsettledForFriend(friendId: Long): LiveData<List<IouEntry>>

    // Net balance: positive = friend owes you, negative = you owe friend
    @Query("""
        SELECT SUM(amountPaise) FROM iou_entries 
        WHERE friendId = :friendId AND isSettled = 0
    """)
    suspend fun getNetBalanceForFriend(friendId: Long): Long?

    @Query("SELECT * FROM iou_entries WHERE transactionId = :transactionId")
    suspend fun getEntriesForTransaction(transactionId: Long): List<IouEntry>

    // For all friends at once — used by home screen IOU summary
    @Query("""
        SELECT friendId, SUM(amountPaise) as netAmount 
        FROM iou_entries WHERE isSettled = 0 
        GROUP BY friendId
    """)
    fun getAllNetBalances(): LiveData<List<FriendBalance>>
}

data class FriendBalance(
    val friendId: Long,
    val netAmount: Long
)