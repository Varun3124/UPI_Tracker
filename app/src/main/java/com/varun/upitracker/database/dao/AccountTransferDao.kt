package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.varun.upitracker.database.entity.AccountTransfer

@Dao
interface AccountTransferDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: AccountTransfer)

    @Query("SELECT * FROM account_transfer WHERE statementRefNo = :statementRefNo LIMIT 1")
    suspend fun findByStatementRefNo(statementRefNo: String): AccountTransfer?

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
