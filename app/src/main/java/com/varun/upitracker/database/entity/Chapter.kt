package com.varun.upitracker.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Whether a chapter still takes new transactions.
 *
 * Stored as TEXT like every other enum here, so a later value needs no migration. "Settled" is
 * deliberately absent: it is derived from the nets and the pending count, never written down, so it
 * cannot go stale behind a transaction someone edited.
 */
enum class ChapterState { OPEN, CLOSED }

/**
 * Whose a chapter is, and whether it is shared. Stored as text in `chapters.shareMode`, where null
 * means [PRIVATE] -- every chapter from before sharing existed. See docs/declarations-design.md S1-S10.
 */
object ChapterShareMode {

    /** This phone's own, and nobody else has it. */
    const val PRIVATE = "PRIVATE"

    /** This phone's own, sent to every linked member after each change. */
    const val SHARED = "SHARED"

    /** A linked friend's chapter, kept up to date by them. Read-only here. */
    const val REPLICA = "REPLICA"

    /** A friend's chapter that stopped updating: the two unlinked, or they stopped sharing it. */
    const val FROZEN = "FROZEN"

    /** A friend's chapter pasted in by hand. It never updates. */
    const val STATIC = "STATIC"
}

/**
 * A private, named container for tracking who owes whom among a group of friends: a trip, a flat, a
 * party.
 *
 * ME is always a member and is never stored in [ChapterMember] -- there is no friend row for the
 * user to point at. Friends are not asked to consent and need not have the app; a chapter never
 * leaves this device.
 *
 * See docs/chapters-design.md.
 */
@Entity(tableName = "chapters", indices = [Index(value = ["shareId"], unique = true)])
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val createdEpoch: Long,
    val state: ChapterState = ChapterState.OPEN,
    val closedEpoch: Long? = null,

    /**
     * The chapter new transactions are offered to by default. At most one row is ever true, and only
     * an open one -- see `ChapterDao.setActive`, which enforces both in a single statement rather
     * than trusting callers to clear the old flag.
     */
    val isActive: Boolean = false,

    val notes: String? = null,

    /**
     * The id this chapter goes by on every phone that holds it: random, minted by its owner the first
     * time it is shared. Null for a chapter that has never left this phone.
     */
    val shareId: String? = null,

    /** Null when this phone owns the chapter; otherwise the friend it is a copy of. */
    val ownerFriendId: Long? = null,

    /**
     * Owner: bumped on every change a member's copy would show, so the phone knows who is behind.
     * Copy: the owner's version it last took, so an older snapshot arriving late is ignored.
     */
    @ColumnInfo(defaultValue = "0")
    val shareVersion: Long = 0,

    /**
     * A copy only: the owner's last snapshot, as sent to this phone. Its plan is what the copy's
     * balances come from -- a copy never works a plan out for itself.
     */
    val snapshot: String? = null,

    /** One of [ChapterShareMode]; null reads as [ChapterShareMode.PRIVATE]. */
    val shareMode: String? = null
) {
    /** Whether this phone owns it, and so may change it. */
    val isOwn: Boolean get() = ownerFriendId == null

    val mode: String get() = shareMode ?: ChapterShareMode.PRIVATE
}

/**
 * Owner side: a linked member a shared chapter goes to, and the last version they were sent.
 * A member whose [sentVersion] is behind the chapter's `shareVersion` gets a new snapshot at the next
 * chance -- which is also how a member who relinks catches up.
 */
@Entity(
    tableName = "chapter_shares",
    primaryKeys = ["chapterId", "friendId"],
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("friendId")]
)
data class ChapterShare(
    val chapterId: Long,
    val friendId: Long,
    val sentVersion: Long
)

/**
 * Copy side: who one of the owner's people is on this phone, when nothing else says. Someone the owner
 * names by an account this phone is linked to needs no row; anyone else is matched here by hand, and
 * until they are, a payment between them and ME is shown but not counted (S4).
 */
@Entity(
    tableName = "chapter_people",
    primaryKeys = ["chapterId", "personKey"],
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("friendId")]
)
data class ChapterPerson(
    val chapterId: Long,
    /** `uid:<uid>` for someone named by account, `name:<name>` for anyone else. */
    val personKey: String,
    val friendId: Long
)

/**
 * A friend in a chapter.
 *
 * `friendId` is RESTRICT, not CASCADE like the rest of this schema: membership is something the user
 * stated, and losing it silently would leave a chapter quietly recomputing to different numbers.
 * `SettingsRepository.friendHasHistory` blocks the delete first, so the constraint is a backstop.
 */
@Entity(
    tableName = "chapter_members",
    primaryKeys = ["chapterId", "friendId"],
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("friendId")]
)
data class ChapterMember(
    val chapterId: Long,
    val friendId: Long,
    val addedEpoch: Long
)

/**
 * What one chapter's simplified plan does to one friend's balance with ME. Positive means the friend
 * owes ME, the same sign as [IouEntry.amountPaise].
 *
 * Derived, never authored: `ChapterRepository.recompute` rebuilds every row of a chapter from its
 * tagged transactions. Only non-zero contributions are stored, so a member who comes out even has no
 * row here at all.
 *
 * Both keys CASCADE -- unlike [ChapterMember], there is nothing here worth protecting, and a
 * RESTRICT would make `SettingsRepository.mergeFriendInto` throw when it deletes the merged-away
 * friend.
 */
@Entity(
    tableName = "chapter_balances",
    primaryKeys = ["chapterId", "friendId"],
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("friendId")]
)
data class ChapterBalance(
    val chapterId: Long,
    val friendId: Long,
    val amountPaise: Long
)
