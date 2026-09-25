package com.varun.upitracker.database.entity

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
 * A private, named container for tracking who owes whom among a group of friends: a trip, a flat, a
 * party.
 *
 * ME is always a member and is never stored in [ChapterMember] -- there is no friend row for the
 * user to point at. Friends are not asked to consent and need not have the app; a chapter never
 * leaves this device.
 *
 * See docs/chapters-design.md.
 */
@Entity(tableName = "chapters")
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

    val notes: String? = null
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
