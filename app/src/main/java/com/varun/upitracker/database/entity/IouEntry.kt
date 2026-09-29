package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "iou_entries",
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = IouDeclaration::class,
            parentColumns = ["id"],
            childColumns = ["declarationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("transactionId"), Index("friendId"), Index("declarationId")]
)
data class IouEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The transaction this came from, or null for a checkpoint's opening entry (and its residuals). */
    val transactionId: Long?,
    val friendId: Long,
    // Positive = friend owes you, Negative = you owe friend
    val amountPaise: Long,
    val isSettled: Boolean = false,
    val settledEpoch: Long? = null,

    /**
     * The declaration this is the opening entry of, or null. An opening is the oldest thing in a
     * friend's base ledger -- everything before it is summed up in it -- so repayments settle it first.
     */
    val declarationId: String? = null
)
