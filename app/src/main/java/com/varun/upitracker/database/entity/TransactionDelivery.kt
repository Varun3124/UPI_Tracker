package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One transaction sent to one friend through the mailbox.
 *
 * What lets the send screen say "sent to Bob on 12 Sep" and leave Bob unticked the second time, and
 * what lets deleting the account take back messages nobody collected -- [messageId] names the
 * document in the friend's inbox.
 */
@Entity(
    tableName = "transaction_deliveries",
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
    indices = [Index("transactionId"), Index("friendId")]
)
data class TransactionDelivery(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    val friendId: Long,
    val messageId: String,
    val sentEpoch: Long
)
