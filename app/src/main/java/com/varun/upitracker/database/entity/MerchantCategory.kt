package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "merchant_categories",
    primaryKeys = ["merchantId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Merchant::class,
            parentColumns = ["id"],
            childColumns = ["merchantId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("merchantId"), Index("categoryId")]
)
data class MerchantCategory(
    val merchantId: Long,
    val categoryId: Long
)