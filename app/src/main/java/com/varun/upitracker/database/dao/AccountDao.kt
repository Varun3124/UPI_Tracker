package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Delete
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(account: Account)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(accounts: List<Account>)

    @Update
    abstract suspend fun update(account: Account)

    @Delete
    abstract suspend fun delete(account: Account)

    @Query("SELECT * FROM account WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): Account?

    @Query("SELECT * FROM account ORDER BY isArchived ASC, label ASC")
    abstract fun getAll(): Flow<List<Account>>

    @Query("SELECT * FROM account ORDER BY isArchived ASC, label ASC")
    abstract suspend fun getAllSync(): List<Account>

    @Query("SELECT * FROM account WHERE isArchived = 0 ORDER BY label ASC")
    abstract fun observeActiveAccounts(): LiveData<List<Account>>

    @Query("SELECT * FROM account WHERE isArchived = 0 AND type IN (:types) ORDER BY label ASC")
    abstract suspend fun getActiveByTypes(types: List<AccountType>): List<Account>

    @Query("SELECT * FROM account WHERE type = :type ORDER BY label ASC")
    abstract suspend fun getByType(type: AccountType): List<Account>

    @Transaction
    open suspend fun setDefault(accountId: String, type: AccountType) {
        unsetDefault(type)
        markDefault(accountId, type)
    }

    @Query("UPDATE account SET isDefault = 0 WHERE type = :type")
    protected abstract suspend fun unsetDefault(type: AccountType)

    @Query("UPDATE account SET isDefault = 1 WHERE id = :accountId AND type = :type")
    protected abstract suspend fun markDefault(accountId: String, type: AccountType)

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE myAccountId = :accountId
        """
    )
    abstract suspend fun countTransactionsForAccount(accountId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM account_transfer
        WHERE fromAccountId = :accountId OR toAccountId = :accountId
        """
    )
    abstract suspend fun countTransfersForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM balance_snapshot WHERE accountId = :accountId")
    abstract suspend fun countSnapshotsForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM fixed_deposit_detail WHERE accountId = :accountId OR sourceAccountId = :accountId")
    abstract suspend fun countFixedDepositReferencesForAccount(accountId: String): Int
}
