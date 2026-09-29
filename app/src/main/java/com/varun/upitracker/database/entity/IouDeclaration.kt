package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a proposal asks for. Stored as text, like [FriendLinkState]. */
object DeclarationKind {

    /** A new checkpoint: "as of now, this is what we owe each other". */
    const val DECLARE = "DECLARE"

    /** Replaces a checkpoint with one at the same instant and a different amount -- a late row folded in. */
    const val AMEND = "AMEND"

    /** Takes a checkpoint away, so the one before it (if any) counts again. */
    const val REVOKE = "REVOKE"
}

/** Where a proposal stands. Stored as text, like [FriendLinkState]. */
object DeclarationState {

    /** Waiting for the other side. Changes nothing on either phone. */
    const val OPEN = "OPEN"

    /** Both sides agreed. From here on the proposal has done what it asked, on both phones. */
    const val ACCEPTED = "ACCEPTED"

    const val DENIED = "DENIED"

    /** The proposer took it back before it was accepted. */
    const val WITHDRAWN = "WITHDRAWN"

    /** The two unlinked while it was still open, so nobody can answer it any more. */
    const val CLOSED = "CLOSED"
}

/**
 * One proposal between this phone and a linked friend, and -- once accepted -- the declaration it
 * made: "as of [asOfEpoch], [friendId] owes ME [amountPaise]".
 *
 * Both phones hold a row under the same [id], each written from its own seat, so the amounts are
 * negatives of each other. Nothing here changes a balance until [state] is ACCEPTED, and an accepted
 * row is never edited again: an amendment or revocation is a proposal of its own that names this one
 * as its [targetId]. That is what lets the two phones apply accepted proposals in whatever order they
 * arrive and still agree -- see [com.varun.upitracker.domain.declaration.DeclarationSet].
 *
 * See docs/declarations-design.md.
 */
@Entity(
    tableName = "iou_declarations",
    foreignKeys = [
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("friendId")]
)
data class IouDeclaration(
    /** Random, minted by the proposer, and the same on both phones. */
    @PrimaryKey
    val id: String,

    val friendId: Long,

    /**
     * One of [DeclarationKind], or null for a stub: a withdrawal that arrived before the proposal it
     * withdraws. The stub is what makes the proposal be ignored when it does arrive.
     */
    val kind: String?,

    /** The declaration an AMEND replaces or a REVOKE removes. */
    val targetId: String? = null,

    /** The instant a DECLARE or AMEND speaks for. Rows dated at or before it are covered by it. */
    val asOfEpoch: Long? = null,

    /** The whole balance agreed as of [asOfEpoch], chapters included. Positive means the friend owes ME. */
    val amountPaise: Long? = null,

    /** What an AMEND adds to the declaration it replaces. Only for showing; [amountPaise] is the figure. */
    val deltaPaise: Long? = null,

    /** What the proposer said about it, e.g. which late row an amendment folds in. */
    val note: String? = null,

    val proposedByMe: Boolean,

    /** When the proposer made it, by the proposer's clock. */
    val proposedEpoch: Long,

    /** One of [DeclarationState]. */
    val state: String,

    val decidedEpoch: Long? = null,

    /**
     * Set when the two unlink. An archived declaration still anchors the balance -- unlinking never
     * changes what either owes -- but no proposal can change it any more.
     */
    val archived: Boolean = false,

    /** Outgoing only: when it reached the other side's inbox. Null while it waits in the outbox. */
    val sentEpoch: Long? = null,

    /** Made by the app itself when the link completed, rather than by the user. */
    val auto: Boolean = false,

    /** What the other side said when they answered, if anything. */
    val replyNote: String? = null
)

/**
 * How much of a declaration's amount one chapter's share was, from this phone's seat.
 *
 * A declaration covers the whole balance, chapters included, but chapters stay live: their plans move
 * whenever they change. The declaration's opening entry is therefore its amount minus these parts,
 * and each chapter keeps contributing through `chapter_balances` as before -- so a chapter only moves
 * the balance by how much it has changed since it was agreed.
 *
 * A shared chapter's part is agreed by both phones: listed in the proposal, or set from the owner's
 * hint. A private chapter's part is this phone's own. [chapterId] is null while a listed chapter's
 * copy has not reached this phone yet; it only counts once it has.
 */
@Entity(
    tableName = "declaration_parts",
    foreignKeys = [
        ForeignKey(
            entity = IouDeclaration::class,
            parentColumns = ["id"],
            childColumns = ["declarationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["declarationId", "chapterId"], unique = true),
        Index(value = ["declarationId", "shareId"], unique = true),
        Index("chapterId")
    ]
)
data class DeclarationPart(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val declarationId: String,

    val chapterId: Long?,

    /** The chapter's share id when it is shared, which is how the other phone knows which chapter this is. */
    val shareId: String?,

    /** Positive means the friend owes ME, the same sign as [IouEntry.amountPaise]. */
    val amountPaise: Long
)
