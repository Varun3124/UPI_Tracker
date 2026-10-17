package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.varun.upitracker.database.entity.ReceivedOriginal
import com.varun.upitracker.database.entity.ReceivedOriginalShare
import com.varun.upitracker.database.entity.Transaction

/**
 * Received transactions as they arrived. Written once -- at import, or for a row received before
 * originals were kept, by [com.varun.upitracker.maintenance.ReceivedOriginalBackfill] -- and otherwise
 * only ever read, except by a merge, which has to keep the people and shops it names pointing at
 * whoever survived.
 */
@Dao
interface ReceivedOriginalDao {

    /** IGNORE: an original is never replaced, even if an import somehow ran twice for one row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(original: ReceivedOriginal): Long

    @Insert
    suspend fun insertShares(shares: List<ReceivedOriginalShare>)

    @Query("SELECT * FROM received_originals WHERE transactionId = :transactionId")
    suspend fun get(transactionId: Long): ReceivedOriginal?

    @Query("SELECT * FROM received_original_shares WHERE transactionId = :transactionId ORDER BY id")
    suspend fun getShares(transactionId: Long): List<ReceivedOriginalShare>

    /** Rows from a friend with no original: received before originals were kept. */
    @Query(
        "SELECT * FROM transactions WHERE sharedRefId IS NOT NULL " +
            "AND id NOT IN (SELECT transactionId FROM received_originals) ORDER BY id"
    )
    suspend fun getReceivedWithoutOriginal(): List<Transaction>

    @Query("UPDATE received_originals SET payerFriendId = :targetId WHERE payerFriendId = :sourceId")
    suspend fun reassignPayerFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE received_originals SET payeeFriendId = :targetId WHERE payeeFriendId = :sourceId")
    suspend fun reassignPayeeFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE received_original_shares SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignShareFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE received_originals SET payerMerchantId = :targetId WHERE payerMerchantId = :sourceId")
    suspend fun reassignPayerMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE received_originals SET payeeMerchantId = :targetId WHERE payeeMerchantId = :sourceId")
    suspend fun reassignPayeeMerchant(sourceId: Long, targetId: Long)
}
