package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchants")
data class Merchant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,       // e.g. "Zomato"
    val addedEpoch: Long
)