package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.varun.upitracker.database.entity.IouEntry

@Dao
interface IouDao {

    @Insert
    suspend fun insert(entry: com.varun.upitracker.database.entity.IouEntry)

    @Update
    suspend fun update(entry: com.varun.upitracker.database.entity.IouEntry)

    @Query("SELECT * FROM iou_entries WHERE friendId = :friendId AND isSettled = 0")
    fun getUnsettledForFriend(friendId: Long): LiveData<List<com.varun.upitracker.database.entity.IouEntry>>

    // Net balance: positive = friend owes you, negative = you owe friend
    @Query("""
        SELECT SUM(amountPaise) FROM iou_entries 
        WHERE friendId = :friendId AND isSettled = 0
    """)
    suspend fun getNetBalanceForFriend(friendId: Long): Long?

    // For all friends at once — used by home screen IOU summary
    @Query("""
        SELECT friendId, SUM(amountPaise) as netAmount 
        FROM iou_entries WHERE isSettled = 0 
        GROUP BY friendId
    """)
    suspend fun getAllNetBalances(): List<com.varun.upitracker.database.dao.FriendBalance>

    @Query("SELECT SUM(amountPaise) FROM iou_entries WHERE isSettled = 0")
    suspend fun getTotalUnsettledBalance(): Long?

    // For auto-offset — oldest unsettled entries first
    @Query("""
    SELECT iou_entries.* FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId AND iou_entries.isSettled = 0
    ORDER BY transactions.dateEpoch ASC, iou_entries.id ASC
""")
    suspend fun getUnsettledOldestFirst(friendId: Long): List<com.varun.upitracker.database.entity.IouEntry>

    @Query("""
    SELECT iou_entries.* FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId
      AND iou_entries.isSettled = 0
      AND iou_entries.amountPaise > 0
    ORDER BY transactions.dateEpoch ASC, iou_entries.id ASC
""")
    suspend fun getPositiveUnsettledOldestFirst(friendId: Long): List<com.varun.upitracker.database.entity.IouEntry>

    @Query("""
    SELECT iou_entries.* FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId
      AND iou_entries.isSettled = 0
      AND iou_entries.amountPaise < 0
    ORDER BY transactions.dateEpoch ASC, iou_entries.id ASC
""")
    suspend fun getNegativeUnsettledOldestFirst(friendId: Long): List<com.varun.upitracker.database.entity.IouEntry>

    @Query("""
    SELECT MAX(transactions.dateEpoch) FROM iou_entries
    INNER JOIN transactions ON iou_entries.transactionId = transactions.id
    WHERE iou_entries.friendId = :friendId
""")
    suspend fun getLastActivityEpoch(friendId: Long): Long?

    // All entries ever for a friend — settled and unsettled, for lifetime totals
    @Query("SELECT * FROM iou_entries WHERE friendId = :friendId")
    suspend fun getAllEntriesForFriend(friendId: Long): List<com.varun.upitracker.database.entity.IouEntry>

    @Query("DELETE FROM iou_entries WHERE transactionId = :txId")
    suspend fun deleteForTransaction(txId: Long)

    /**
     * Everything these friends hold, settled and unsettled alike.
     *
     * Scoped by friend and not by transaction on purpose: a partial repayment leaves a residual row
     * attributed to the transaction it settled against, not to the one that paid it, so deleting by
     * transaction would strand residuals owned by rows that are about to be rebuilt. Only
     * [com.varun.upitracker.ledger.LedgerReplayer] should call this, and only immediately before
     * reposting every one of them.
     */
    @Query("DELETE FROM iou_entries WHERE friendId IN (:friendIds)")
    suspend fun deleteForFriends(friendIds: List<Long>)

    @Query("SELECT COUNT(*) FROM iou_entries WHERE friendId = :friendId")
    suspend fun countEntriesForFriend(friendId: Long): Int

    /**
     * Who these transactions currently hold entries for.
     *
     * A tag or untag has to replay every friend whose balance the move touches, and an edit may
     * already have taken someone off the transaction while leaving their entry behind -- so the
     * entries are asked as well as the row itself.
     */
    @Query("SELECT DISTINCT friendId FROM iou_entries WHERE transactionId IN (:transactionIds)")
    suspend fun friendIdsWithEntriesFor(transactionIds: List<Long>): List<Long>

    @Query("UPDATE iou_entries SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignFriend(sourceId: Long, targetId: Long)
}

data class FriendBalance(
    val friendId: Long,
    val netAmount: Long
)
