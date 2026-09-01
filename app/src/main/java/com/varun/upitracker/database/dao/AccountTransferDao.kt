package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.AccountTransfer

@Dao
interface AccountTransferDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: AccountTransfer)

    @Update
    suspend fun update(transfer: AccountTransfer)

    @Query("SELECT * FROM account_transfer WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountTransfer?

    @Query("DELETE FROM account_transfer WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM account_transfer WHERE statementRefNo = :statementRefNo LIMIT 1")
    suspend fun findByStatementRefNo(statementRefNo: String): AccountTransfer?

    @Query("SELECT * FROM account_transfer ORDER BY dateEpoch DESC, id DESC LIMIT :limit")
    suspend fun getRecentTransfers(limit: Int): List<AccountTransfer>

    /**
     * Half-open `[fromEpoch, toEpoch)`, matching `TransactionDao.getTransactionsBetweenSync` so the
     * two can be merged over the same month window. Deliberately a different convention from
     * [getAccountTransfersBetween] below — do not harmonise them.
     */
    @Query(
        """
        SELECT * FROM account_transfer
        WHERE dateEpoch >= :fromEpoch
          AND dateEpoch < :toEpoch
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    suspend fun getTransfersBetween(fromEpoch: Long, toEpoch: Long): List<AccountTransfer>

    /**
     * Half-open `(fromEpochExclusive, toEpochInclusive]` because this serves snapshot-relative
     * balance derivation: a transfer dated exactly at the anchoring snapshot must not be counted
     * on top of that snapshot's balance.
     */
    @Query(
        """
        SELECT * FROM account_transfer
        WHERE dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
          AND (fromAccountId = :accountId OR toAccountId = :accountId)
        ORDER BY dateEpoch ASC
        """
    )
    suspend fun getAccountTransfersBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<AccountTransfer>
}
