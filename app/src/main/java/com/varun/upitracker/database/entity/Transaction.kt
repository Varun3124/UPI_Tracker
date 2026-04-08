package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["resolvedFriendId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Merchant::class,
            parentColumns = ["id"],
            childColumns = ["resolvedMerchantId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("resolvedFriendId"),
        Index("resolvedMerchantId"),
        Index("upiRefId", unique = true)
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val amountPaise: Long,              // ₹1 = 100 paise, avoids float errors
    val direction: String,              // "DEBIT" or "CREDIT"
    val payeeRaw: String,               // Raw string from SMS, never modified
    val payeeType: String = "UNKNOWN",  // "FRIEND", "MERCHANT", or "UNKNOWN"

    val resolvedFriendId: Long? = null,    // Set if payeeType == FRIEND
    val resolvedMerchantId: Long? = null,  // Set if payeeType == MERCHANT

    val reason: String? = null,         // User-entered note
    val upiRefId: String? = null,       // For deduplication — unique in table
    val dateEpoch: Long,                // Unix ms from SMS timestamp
    val source: String,                 // "SMS" or "MANUAL"
    val isPending: Boolean = false      // True if overlay dismissed without completing
)