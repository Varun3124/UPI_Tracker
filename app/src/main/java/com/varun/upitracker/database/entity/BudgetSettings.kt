package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budget_settings")
data class BudgetSettings(
    @PrimaryKey
    val id: Int = 1,   // Always a single row — replace on update, never insert new

    val monthlyLimitPaise: Long,
    val dailyLimitPaise: Long? = null,
    val updatedEpoch: Long
)