package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "transaction_category_splits",
    primaryKeys = ["transactionId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = _root_ide_package_.com.varun.upitracker.database.entity.Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("transactionId"),
        Index("categoryId")
    ]
)
data class TransactionCategorySplit(
    val transactionId:    Long,
    val categoryId:       Long,
    val myAmountPaise:    Long,
    val partyAmountPaise: Long
)