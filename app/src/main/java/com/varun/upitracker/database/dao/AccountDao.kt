package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<Account>)

    @Update
    suspend fun update(account: Account)

    @Query("SELECT * FROM account WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Account?

    @Query("SELECT * FROM account ORDER BY isArchived ASC, label ASC")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM account WHERE isArchived = 0 ORDER BY label ASC")
    fun observeActiveAccounts(): LiveData<List<Account>>

    @Query("SELECT * FROM account WHERE isArchived = 0 AND type IN (:types) ORDER BY label ASC")
    suspend fun getActiveByTypes(types: List<AccountType>): List<Account>

    @Query("SELECT * FROM account WHERE type = :type ORDER BY label ASC")
    suspend fun getByType(type: AccountType): List<Account>
}
