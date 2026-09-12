package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transaction_shares",
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("transactionId"),
        Index("friendId")
    ]
)
data class TransactionShare(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    val side: String? = null, // "PAYER" or "PAYEE"; null = legacy
    val participantType: String, // "ME" or "FRIEND"
    val friendId: Long? = null,
    val amountPaise: Long,

    /**
     * What to call a participant this database cannot identify -- someone the sender of a shared
     * parcel split with, who is not us.
     *
     * Never a stand-in for [friendId]. Every ledger leg in
     * [com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService] filters on a
     * non-null [friendId], so a labelled-but-unidentified share carries its amount -- which the
     * payer and payee sums require -- without moving any money. Giving them an id on the strength
     * of a matching name would post a debt to someone we have never dealt with.
     */
    val rawLabel: String? = null
)
