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
            childColumns = ["payerFriendId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Merchant::class,
            parentColumns = ["id"],
            childColumns = ["payerMerchantId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["payeeFriendId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Merchant::class,
            parentColumns = ["id"],
            childColumns = ["payeeMerchantId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["myAccountId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("payerFriendId"),
        Index("payerMerchantId"),
        Index("payeeFriendId"),
        Index("payeeMerchantId"),
        Index("myAccountId"),
        Index(value = ["myAccountId", "dateEpoch"]),
        Index("upiRefId", unique = true)
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val amountPaise: Long,

    val payerActorType: String = "ME",
    val payerFriendId: Long? = null,
    val payerMerchantId: Long? = null,
    val payerRawLabel: String? = null,

    val payeeActorType: String = "UNKNOWN",
    val payeeFriendId: Long? = null,
    val payeeMerchantId: Long? = null,
    val payeeRawLabel: String? = null,

    val reason: String? = null,
    val upiRefId: String? = null,
    val myAccountId: String? = null,
    val dateEpoch: Long,
    val source: String,
    val isPending: Boolean = false
)
