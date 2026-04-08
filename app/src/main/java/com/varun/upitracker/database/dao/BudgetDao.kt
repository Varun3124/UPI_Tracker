package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.BudgetSettings

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: BudgetSettings)

    @Query("SELECT * FROM budget_settings WHERE id = 1")
    suspend fun get(): BudgetSettings?
}