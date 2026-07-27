package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "friend_raw_names",
    foreignKeys = [ForeignKey(
        entity = _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
        parentColumns = ["id"],
        childColumns = ["friendId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("friendId"), Index("rawName", unique = true)]
)
data class FriendRawName(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val friendId: Long,
    val rawName: String  // e.g. "RAHUL KUMAR", "Rahul K" — used for DEBIT resolution
)