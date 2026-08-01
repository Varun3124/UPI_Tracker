package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "balance_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["accountId", "snapshotEpoch"])]
)
data class BalanceSnapshot(
    @PrimaryKey val id: String,
    val accountId: String,
    val snapshotEpoch: Long,
    val balancePaise: Long,
    val source: BalanceSnapshotSource,
    val notes: String? = null
)
