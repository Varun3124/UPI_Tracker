package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

    @Query(
        """
        SELECT SUM(t.mySharePaise) FROM transactions t
        INNER JOIN transaction_shares s
            ON s.transactionId = t.id
           AND s.participantType = 'ME'
           AND s.shareSide = 'MEANT_TO_PAY'
        WHERE t.dateEpoch >= :fromEpoch
          AND t.mySharePaise IS NOT NULL
          AND (t.payerActorType = 'MERCHANT' OR t.payeeActorType = 'MERCHANT')
        """
    )
    suspend fun getTotalDebitSince(fromEpoch: Long): Long?

    @Query(
        """
        SELECT * FROM transactions
        WHERE (
            payerFriendId = :friendId
            OR payeeFriendId = :friendId
            OR resolvedFriendId = :friendId
            OR resolvedMerchantId = :merchantId
        )
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    fun getTransactionsForEntity(friendId: Long?, merchantId: Long?): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): Transaction?

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    suspend fun getTransactionsSinceSync(fromEpoch: Long): List<Transaction>

    @Query(
        """
        SELECT DISTINCT t.* FROM transactions t
        LEFT JOIN iou_entries i
            ON t.id = i.transactionId
           AND i.friendId = :friendId
        LEFT JOIN transaction_shares s
            ON t.id = s.transactionId
           AND s.friendId = :friendId
        LEFT JOIN transaction_parties p
            ON t.id = p.transactionId
           AND p.friendId = :friendId
        WHERE t.payerFriendId = :friendId
           OR t.payeeFriendId = :friendId
           OR t.resolvedFriendId = :friendId
           OR i.friendId = :friendId
           OR s.friendId = :friendId
           OR p.friendId = :friendId
        ORDER BY t.dateEpoch DESC, t.id DESC
        """
    )
    suspend fun getTransactionsForFriendSync(friendId: Long): List<Transaction>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerFriendId = :friendId
           OR payeeFriendId = :friendId
           OR resolvedFriendId = :friendId
        """
    )
    suspend fun countReferencesForFriend(friendId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerMerchantId = :merchantId
           OR payeeMerchantId = :merchantId
           OR resolvedMerchantId = :merchantId
        """
    )
    suspend fun countReferencesForMerchant(merchantId: Long): Int

    @Query("UPDATE transactions SET resolvedFriendId = :targetId WHERE resolvedFriendId = :sourceId")
    suspend fun reassignResolvedFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payerFriendId = :targetId WHERE payerFriendId = :sourceId")
    suspend fun reassignPayerFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeFriendId = :targetId WHERE payeeFriendId = :sourceId")
    suspend fun reassignPayeeFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET resolvedMerchantId = :targetId WHERE resolvedMerchantId = :sourceId")
    suspend fun reassignResolvedMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payerMerchantId = :targetId WHERE payerMerchantId = :sourceId")
    suspend fun reassignPayerMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeMerchantId = :targetId WHERE payeeMerchantId = :sourceId")
    suspend fun reassignPayeeMerchant(sourceId: Long, targetId: Long)
}
