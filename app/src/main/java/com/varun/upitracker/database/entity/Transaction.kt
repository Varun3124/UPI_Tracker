package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
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
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["myAccountId"],
            onDelete = ForeignKey.RESTRICT
        ),
        // RESTRICT, not CASCADE or SET_NULL: deleting a purchase that has been refunded would
        // either silently drop a real inflow or silently reclassify it as income. Blocking the
        // delete keeps the decision with the user.
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["refundsTransactionId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("payerFriendId"),
        Index("payerMerchantId"),
        Index("payeeFriendId"),
        Index("payeeMerchantId"),
        Index("myAccountId"),
        Index(value = ["myAccountId", "dateEpoch"]),
        Index("upiRefId", unique = true),
        // Deliberately NOT unique: HDFC reuses filler ref numbers such as "000000000000000"
        // across unrelated rows, so a unique index would reject valid imports.
        Index("statementRefNo"),
        Index("refundsTransactionId")
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val amountPaise: Long,

    val payerActorType: String = "ME",
    val payerFriendId: Long? = null,
    val payerMerchantId: Long? = null,
    val payerRawLabel: String? = null,

    val payeeActorType: String = "UNKNOWN",
    val payeeFriendId: Long? = null,
    val payeeMerchantId: Long? = null,
    val payeeRawLabel: String? = null,

    val reason: String? = null,
    val upiRefId: String? = null,
    /** `Chq./Ref.No.` of the bank-statement row this came from, or was matched to. */
    val statementRefNo: String? = null,
    val myAccountId: String? = null,
    val dateEpoch: Long,
    val source: String,
    val isPending: Boolean = false,

    /**
     * The purchase this transaction refunds, or null.
     *
     * A linked refund is a negative expense: it carries its own category splits, restricted to the
     * original's categories, and the spend aggregates subtract them from the ORIGINAL's time
     * period rather than the refund's. Linking by id rather than by copying the date means
     * re-dating the purchase moves the credit with it.
     *
     * Null means this is not a refund at all -- an unlinked merchant credit is income, and never
     * enters expense reporting.
     */
    val refundsTransactionId: Long? = null,

    /** Whether this moves what ME and a friend owe each other. See [LedgerEffect]. */
    val ledgerEffect: LedgerEffect = LedgerEffect.DEBT
)
