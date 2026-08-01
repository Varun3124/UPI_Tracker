package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_transfer",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["fromAccountId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["toAccountId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["fromAccountId", "dateEpoch"]),
        Index(value = ["toAccountId", "dateEpoch"]),
        Index(value = ["statementRefNo"], unique = true)
    ]
)
data class AccountTransfer(
    @PrimaryKey val id: String,
    val fromAccountId: String?,
    val toAccountId: String?,
    val amountFromPaise: Long,
    val amountToPaise: Long,
    val type: AccountTransferType,
    val dateEpoch: Long,
    val source: EntrySource,
    val statementRefNo: String? = null,
    val notes: String? = null
)
