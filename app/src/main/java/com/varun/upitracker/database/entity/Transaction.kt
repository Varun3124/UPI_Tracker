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
        Index("refundsTransactionId"),
        // Unique for the same reason `upiRefId` is: applying a parcel that was already applied
        // has to fail at the insert rather than quietly duplicate the row. SQLite treats NULLs
        // as distinct here, so every transaction that never came from a parcel is unaffected.
        Index("sharedRefId", unique = true),
        // Unique so two rows can never be sent under one reference: friends dedup on it.
        Index("shareRef", unique = true),
        // Every date-range query filters on this alone. Without it, and with no ANALYZE to build
        // sqlite_stat1, the planner reaches for index_transactions_refundsTransactionId instead --
        // a nonsense choice for a date range, and measurably slower than a plain scan would be.
        Index("dateEpoch"),
        Index("chapterId")
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
    /**
     * The reference of the shared parcel this row was imported from, `"<originToken>.<their id>"`,
     * or null for everything entered here.
     *
     * The sender's own transaction id is not enough on its own -- two friends both sharing their
     * row 41 would collide -- so it is qualified by a token this install keeps per recipient. That
     * makes re-importing the same parcel a no-op without the parcel having to name who wrote it.
     *
     * A row that came through the friends mailbox holds `"mbx:<sender uid>:<their shareRef>"`
     * instead -- see [com.varun.upitracker.domain.parcel.ParcelPerspective.mailboxRefIdFor].
     */
    val sharedRefId: String? = null,
    /**
     * The reference this transaction travels under when it is sent through the friends mailbox:
     * random, minted the first time it is sent, and the same for every friend it goes to.
     *
     * Kept on the row rather than derived from [id], so a transaction restored from an older backup
     * keeps its reference and a new one that happens to reuse an old id never inherits it -- which
     * would make a friend's app skip it as already imported. The opposite of [sharedRefId]: that is
     * what a row *received* carries, this is what a row *sent* carries.
     */
    val shareRef: String? = null,
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
    val ledgerEffect: LedgerEffect = LedgerEffect.DEBT,

    /**
     * Which side of the split decides who pays whom back. See [IouRecovery].
     *
     * Null only on a row from before this existed, including one restored from an older backup: the
     * column has no default, which is how [com.varun.upitracker.maintenance.IouRecoveryBackfill] finds
     * the rows it still has to bring forward. Read it through
     * [com.varun.upitracker.domain.iou.IouLegs.resolve], which covers that gap.
     */
    val iouRecovery: IouRecovery? = IouRecovery.FROM_SECONDARY_PAYERS,

    /**
     * The chapter this transaction belongs to, or null for the base ledger. A transaction is in at
     * most one chapter.
     *
     * Deliberately not a foreign key: SQLite cannot add one with ALTER TABLE ADD COLUMN, and
     * rebuilding this table to gain it is not worth it. `ChapterRepository.delete` untags every row
     * before deleting the chapter, so nothing is left pointing at one that has gone.
     *
     * A tagged transaction posts nothing to `iou_entries`. Its effect reaches a friend's balance
     * only through `chapter_balances`, so anything that moves this column has to replay the base
     * ledger for the friends involved -- see `LedgerReplayer`.
     */
    val chapterId: Long? = null
)
