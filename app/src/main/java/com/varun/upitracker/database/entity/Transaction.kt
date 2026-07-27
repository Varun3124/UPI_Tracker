package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
            parentColumns = ["id"],
            childColumns = ["payerFriendId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Merchant::class,
            parentColumns = ["id"],
            childColumns = ["payerMerchantId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
            parentColumns = ["id"],
            childColumns = ["payeeFriendId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(

            entity = _root_ide_package_.com.varun.upitracker.database.entity.Merchant::class,
            parentColumns = ["id"],
            childColumns = ["payeeMerchantId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("payerFriendId"),
        Index("payerMerchantId"),
        Index("payeeFriendId"),
        Index("payeeMerchantId"),
        Index("upiRefId", unique = true)
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val amountPaise: Long,

    val payerActorType: String = "ME",      // "ME", "FRIEND", "MERCHANT", "UNKNOWN"
    val payerFriendId: Long? = null,
    val payerMerchantId: Long? = null,
    val payerRawLabel: String? = null,

    val payeeActorType: String = "UNKNOWN", // "ME", "FRIEND", "MERCHANT", "UNKNOWN"
    val payeeFriendId: Long? = null,
    val payeeMerchantId: Long? = null,
    val payeeRawLabel: String? = null,

    val reason: String? = null,
    val upiRefId: String? = null,
    val dateEpoch: Long,
    val source: String,
    val isPending: Boolean = false
)
