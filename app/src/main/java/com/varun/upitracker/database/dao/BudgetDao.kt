package com.varun.upitracker.database.dao

import androidx.room.*
import com.varun.upitracker.database.entity.BudgetSettings

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: com.varun.upitracker.database.entity.BudgetSettings)

    @Query("SELECT * FROM budget_settings WHERE id = 1")
    suspend fun get(): com.varun.upitracker.database.entity.BudgetSettings?
}