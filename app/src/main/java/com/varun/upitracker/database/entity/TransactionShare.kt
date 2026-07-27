package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transaction_shares",
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
    indices = [
        Index("transactionId"),
        Index("friendId")
    ]
)
data class TransactionShare(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    val side: String? = null, // "PAYER" or "PAYEE"; null = legacy
    val participantType: String, // "ME" or "FRIEND"
    val friendId: Long? = null,
    val amountPaise: Long
)
