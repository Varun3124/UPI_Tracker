package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_upi_ids",
    foreignKeys = [ForeignKey(
        entity = _root_ide_package_.com.varun.upitracker.database.entity.Merchant::class,
        parentColumns = ["id"],
        childColumns = ["merchantId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("merchantId"), Index("upiId", unique = true)]
)
data class MerchantUpiId(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val merchantId: Long,
    val upiId: String   // Used for CREDIT resolution (refunds/prizes)
)