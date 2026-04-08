package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friends")
data class Friend(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val avatarInitials: String,  // e.g. "RK", shown in UI
    val addedEpoch: Long
)