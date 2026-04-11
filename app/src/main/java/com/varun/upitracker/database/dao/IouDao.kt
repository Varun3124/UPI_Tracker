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
    suspend fun getAllNetBalances(): List<FriendBalance>

    // For auto-offset — oldest unsettled entries first
    @Query("""
    SELECT iou_entries.* FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId AND iou_entries.isSettled = 0
    ORDER BY transactions.dateEpoch ASC
""")
    suspend fun getUnsettledOldestFirst(friendId: Long): List<IouEntry>

    @Query("""
    SELECT MAX(transactions.dateEpoch) FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId
""")
    suspend fun getLastActivityEpoch(friendId: Long): Long?

    // All entries ever for a friend — settled and unsettled, for lifetime totals
    @Query("SELECT * FROM iou_entries WHERE friendId = :friendId")
    suspend fun getAllEntriesForFriend(friendId: Long): List<IouEntry>
}

data class FriendBalance(
    val friendId: Long,
    val netAmount: Long
)