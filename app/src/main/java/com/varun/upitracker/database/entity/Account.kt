package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account")
data class Account(
    @PrimaryKey val id: String,
    val type: AccountType,
    val label: String,
    val addedEpoch: Long,
    val isDefault: Boolean = false,
    val isArchived: Boolean = false
)
