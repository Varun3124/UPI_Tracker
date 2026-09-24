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
        Index(value = ["statementRefNo"], unique = true),
        Index(value = ["upiRefId"], unique = true)
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
    val notes: String? = null,
    /**
     * The UPI reference of the bank message this transfer was recorded from, if any. Carried over
     * when a pending SMS or statement transaction is converted into a transfer, so every importer can
     * see the payment is already here -- the transaction that used to hold it is gone.
     */
    val upiRefId: String? = null
)
