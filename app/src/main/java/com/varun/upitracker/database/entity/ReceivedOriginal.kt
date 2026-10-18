package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A received transaction exactly as it arrived from a friend, kept beside the row it became.
 *
 * The row itself is the receiver's to change in any way -- every person decides what a shared
 * transaction means for them. This copy is never written after the import that made it, so whatever
 * they do, "as they sent it" is always there to look at and to go back to. Gone with the row.
 *
 * Real columns rather than a serialised blob, so the friend and merchant references stay right
 * through a delete (SET_NULL, with [payerRawLabel] and the rest still naming them) and through a merge,
 * which moves these the same way it moves everything else.
 */
@Entity(
    tableName = "received_originals",
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
        )
    ],
    indices = [
        Index("payerFriendId"),
        Index("payerMerchantId"),
        Index("payeeFriendId"),
        Index("payeeMerchantId")
    ]
)
data class ReceivedOriginal(
    @PrimaryKey
    val transactionId: Long,

    /** Who it came from, by the name this person had for them when it arrived. */
    val senderName: String,
    val receivedEpoch: Long,

    val amountPaise: Long,
    val dateEpoch: Long,

    val payerActorType: String,
    val payerFriendId: Long? = null,
    val payerMerchantId: Long? = null,
    /** Always the name at the time for a friend or a shop, so a cleared id still reads. */
    val payerRawLabel: String? = null,

    val payeeActorType: String,
    val payeeFriendId: Long? = null,
    val payeeMerchantId: Long? = null,
    val payeeRawLabel: String? = null,

    val reason: String? = null,
    val ledgerEffect: LedgerEffect,
    val iouRecovery: IouRecovery? = null
)

/** One split of a [ReceivedOriginal], shaped like [TransactionShare]. */
@Entity(
    tableName = "received_original_shares",
    foreignKeys = [
        ForeignKey(
            entity = ReceivedOriginal::class,
            parentColumns = ["transactionId"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        // SET_NULL where a live share CASCADEs: deleting a friend must not quietly change what the
        // original says was owed. The name in [rawLabel] stays.
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("transactionId"),
        Index("friendId")
    ]
)
data class ReceivedOriginalShare(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    val side: String? = null,
    val participantType: String,
    val friendId: Long? = null,
    val rawLabel: String? = null,
    val amountPaise: Long,
    val keepPayeeLeg: Boolean = true,
    val keepPayerLeg: Boolean = true,
    /** Who each IOU ran through as sent. See [TransactionShare.payeeLegVia]. */
    val payeeLegVia: String? = null,
    val payeeLegViaFriendId: Long? = null,
    val payerLegVia: String? = null,
    val payerLegViaFriendId: Long? = null
)
