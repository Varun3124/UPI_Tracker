package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "friend_upi_ids",
    foreignKeys = [ForeignKey(
        entity = _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
        parentColumns = ["id"],
        childColumns = ["friendId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("friendId"), Index("upiId", unique = true)]
)
data class FriendUpiId(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val friendId: Long,
    val upiId: String    // Used for CREDIT resolution only
)