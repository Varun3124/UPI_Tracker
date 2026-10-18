package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The entry screen exactly as it was when the user pressed Pend, kept beside the pending row it
 * belongs to so reopening that row brings back the same unfinished form.
 *
 * Nothing on it has to make sense: amounts need not add up, names may be blank or typed but never
 * matched, and the same person may sit on a side twice. That is the point of it, and why it lives
 * apart from the row, its shares and its splits -- those are only ever written by a save that
 * passed every check. Gone with the row, and cleared whenever the row is confirmed.
 *
 * Real columns rather than a serialised blob, for the reason [ReceivedOriginal] gives: friend,
 * merchant and category references stay right through a delete (SET_NULL or CASCADE, with the typed
 * label still on each row) and through a merge, which moves these the same way it moves the rest.
 */
@Entity(
    tableName = "entry_drafts",
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
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["refundsTransactionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("payerFriendId"),
        Index("payerMerchantId"),
        Index("payeeFriendId"),
        Index("payeeMerchantId"),
        Index("chapterId"),
        Index("refundsTransactionId")
    ]
)
data class EntryDraft(
    @PrimaryKey
    val transactionId: Long,
    val savedEpoch: Long,

    val payerActorType: String,
    val payerFriendId: Long? = null,
    val payerMerchantId: Long? = null,
    val payeeActorType: String,
    val payeeFriendId: Long? = null,
    val payeeMerchantId: Long? = null,

    /** Not foreign keys: an archived account is still named, and the screen falls back if one has gone. */
    val payerAccountId: String? = null,
    val payeeAccountId: String? = null,

    /** What the amount field held, character for character -- blank and "12." included. */
    val amountText: String,
    val dateEpoch: Long,
    val description: String? = null,

    /** The chapter the field showed. Only a confirming save tags anything (see [Transaction.chapterId]). */
    val chapterId: Long? = null,
    val refundsTransactionId: Long? = null,
    /** The pay-back pill the user picked, or null to follow the split's shape. */
    val iouRecoveryOverride: IouRecovery? = null,
    val categoryKind: CategoryKind,
    val trackedCategoryId: Long? = null
)

/** One share row of an [EntryDraft], in order, blank and duplicate rows included. */
@Entity(
    tableName = "entry_draft_rows",
    foreignKeys = [
        ForeignKey(
            entity = EntryDraft::class,
            parentColumns = ["transactionId"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        // SET_NULL: the row keeps its typed label, and reopening offers that name again.
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
data class EntryDraftRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val transactionId: Long,
    /** "PAYER" or "PAYEE", as on [TransactionShare.side]. */
    val side: String,
    val position: Int,
    val participantType: String,
    val friendId: Long? = null,
    val label: String,
    val initials: String,
    val amountPaise: Long,
    val keepPayeeLeg: Boolean = true,
    val keepPayerLeg: Boolean = true
)

/** A category pill checked on an [EntryDraft], with what was in its amount box (zero included). */
@Entity(
    tableName = "entry_draft_categories",
    primaryKeys = ["transactionId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = EntryDraft::class,
            parentColumns = ["transactionId"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class EntryDraftCategory(
    val transactionId: Long,
    val categoryId: Long,
    val myAmountPaise: Long
)
