package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "kind"], unique = true)]
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,  // "Food", "Transport", "Movies", etc.

    /**
     * Uniqueness is per kind, not global: "Gift" is a real expense when you give one and real
     * income when you receive one, so both rows must be able to coexist.
     */
    val kind: CategoryKind = CategoryKind.EXPENSE
)