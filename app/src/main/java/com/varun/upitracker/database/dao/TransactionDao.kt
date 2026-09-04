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

    /**
     * Re-import guard for bank-statement rows that carry no UPI ref id. Scoped to the account
     * because [Transaction.statementRefNo] is intentionally not globally unique.
     */
    @Query("SELECT * FROM transactions WHERE statementRefNo = :refNo AND myAccountId = :accountId LIMIT 1")
    suspend fun findByStatementRefNo(refNo: String, accountId: String): Transaction?

    /**
     * Existing transactions a bank-statement row could plausibly already be recorded as: I am on
     * one side of it, it sits on one of my savings accounts (or none yet), the amount is exact and
     * the date is within the caller's tolerance window.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amountPaise
          AND dateEpoch BETWEEN :fromEpoch AND :toEpoch
          AND (payerActorType = 'ME' OR payeeActorType = 'ME')
          AND (myAccountId IS NULL OR myAccountId IN (:savingsAccountIds))
        ORDER BY dateEpoch ASC, id ASC
        """
    )
    suspend fun findMatchCandidates(
        amountPaise: Long,
        fromEpoch: Long,
        toEpoch: Long,
        savingsAccountIds: List<String>
    ): List<Transaction>

    /**
     * ME's net merchant spend over `(fromEpochExclusive, toEpochInclusive]`: ME's share as payer
     * minus ME's share as payee, for transactions where the other side is a MERCHANT.
     *
     * Not scoped to an account: a share row's own [side][com.varun.upitracker.database.entity.TransactionShare.side]
     * (falling back to the transaction-level actor type for legacy rows where `side` is null) says
     * which side ME is on, since ME can be a secondary participant in a split where the primary
     * payer/payee is a FRIEND — and those transactions carry no `myAccountId` at all (there's no UI
     * to attach an account to a secondary share), so a per-account query would silently drop them.
     */
    @Query(
        """
        SELECT
            SUM(
                CASE
                    WHEN COALESCE(s.side, CASE WHEN t.payerActorType = 'ME' THEN 'PAYER' WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END) = 'PAYER'
                        AND t.payeeActorType = 'MERCHANT' THEN s.amountPaise
                    WHEN COALESCE(s.side, CASE WHEN t.payerActorType = 'ME' THEN 'PAYER' WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END) = 'PAYEE'
                        AND t.payerActorType = 'MERCHANT' THEN -s.amountPaise
                    ELSE 0
                END
            )
        FROM transaction_shares s
        INNER JOIN transactions t
            ON s.transactionId = t.id
        WHERE t.dateEpoch > :fromEpochExclusive AND t.dateEpoch <= :toEpochInclusive
          AND s.participantType = 'ME'
          AND (t.payerActorType = 'MERCHANT' OR t.payeeActorType = 'MERCHANT')
        """
    )
    suspend fun getMerchantSpendTotal(
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long?

    /**
     * How much [accountId]'s balance moved over `(fromEpochExclusive, toEpochInclusive]`.
     *
     * Unlike [getMerchantSpendTotal], which measures spend, this counts the whole amount
     * of every transaction on the account whatever the counterparty, and does not join
     * `transaction_shares` — so pending rows from SMS and statement import count too.
     *
     * Mirrors [com.varun.upitracker.domain.BalanceDeltaCalculator.transactionDelta]; keep the two
     * in step. Covered by `Index(["myAccountId", "dateEpoch"])`.
     */
    @Query(
        """
        SELECT SUM(
            CASE
                WHEN payerActorType = 'ME' AND payeeActorType = 'ME' THEN 0
                WHEN payerActorType = 'ME' THEN -amountPaise
                WHEN payeeActorType = 'ME' THEN amountPaise
                ELSE 0
            END
        )
        FROM transactions
        WHERE myAccountId = :accountId
          AND dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
        """
    )
    suspend fun getAccountBalanceDeltaBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long?

    @Query(
        """
        SELECT * FROM transactions
        WHERE (
            payerFriendId = :friendId
            OR payeeFriendId = :friendId
            OR payerMerchantId = :merchantId
            OR payeeMerchantId = :merchantId
        )
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    fun getTransactionsForEntity(friendId: Long?, merchantId: Long?): LiveData<List<com.varun.upitracker.database.entity.Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): com.varun.upitracker.database.entity.Transaction?

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    suspend fun getTransactionsSinceSync(fromEpoch: Long): List<Transaction>

    @Query(
        """
        SELECT * FROM transactions
        WHERE dateEpoch >= :fromEpoch
          AND dateEpoch < :toEpoch
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    suspend fun getTransactionsBetweenSync(fromEpoch: Long, toEpoch: Long): List<Transaction>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        SELECT * FROM transactions
        WHERE myAccountId = :accountId
          AND dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
        ORDER BY dateEpoch ASC, id ASC
        """
    )
    suspend fun getAccountTransactionsBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<com.varun.upitracker.database.entity.Transaction>

    @Query(
        """
        SELECT DISTINCT t.* FROM transactions t
        LEFT JOIN iou_entries i
            ON t.id = i.transactionId
           AND i.friendId = :friendId
        LEFT JOIN transaction_shares s
            ON t.id = s.transactionId
           AND s.friendId = :friendId
        WHERE t.payerFriendId = :friendId
           OR t.payeeFriendId = :friendId
           OR i.friendId = :friendId
           OR s.friendId = :friendId
        ORDER BY t.dateEpoch DESC, t.id DESC
        """
    )
    suspend fun getTransactionsForFriendSync(friendId: Long): List<com.varun.upitracker.database.entity.Transaction>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerFriendId = :friendId
           OR payeeFriendId = :friendId
        """
    )
    suspend fun countReferencesForFriend(friendId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerMerchantId = :merchantId
           OR payeeMerchantId = :merchantId
        """
    )
    suspend fun countReferencesForMerchant(merchantId: Long): Int

    @Query("UPDATE transactions SET payerFriendId = :targetId WHERE payerFriendId = :sourceId")
    suspend fun reassignPayerFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeFriendId = :targetId WHERE payeeFriendId = :sourceId")
    suspend fun reassignPayeeFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payerMerchantId = :targetId WHERE payerMerchantId = :sourceId")
    suspend fun reassignPayerMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeMerchantId = :targetId WHERE payeeMerchantId = :sourceId")
    suspend fun reassignPayeeMerchant(sourceId: Long, targetId: Long)
}
