package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "iou_entries",
    foreignKeys = [
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("transactionId"), Index("friendId")]
)
data class IouEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    val friendId: Long,
    // Positive = friend owes you, Negative = you owe friend
    val amountPaise: Long,
    val isSettled: Boolean = false,
    val settledEpoch: Long? = null
)