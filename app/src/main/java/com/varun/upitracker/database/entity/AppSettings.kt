package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey
    val id: Int = 1,
    val totalBalancePaise: Long? = null,
    val updatedEpoch: Long
)
