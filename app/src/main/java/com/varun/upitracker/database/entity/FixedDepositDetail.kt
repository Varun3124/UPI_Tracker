package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fixed_deposit_detail",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["sourceAccountId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("sourceAccountId")]
)
data class FixedDepositDetail(
    @PrimaryKey val accountId: String,
    val principalPaise: Long,
    val sourceAccountId: String,
    val bookedEpoch: Long,
    val maturityEpoch: Long,
    val status: FixedDepositStatus
)
