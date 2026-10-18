package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A friend the user chose to keep outside the chapter a transaction is tagged into.
 *
 * Normally tagging pulls everyone a transaction names into the chapter (R5). Someone kept outside is
 * not made a member: what they and ME owe each other on this transaction stays in the base ledger,
 * exactly as if it were untagged, and only the members' lines feed the chapter's plan. They may only
 * owe or be owed by ME there -- a line with another member has to run through ME first -- so a
 * chapter never holds a debt for someone who is not in it.
 *
 * Asked about every time a transaction is tagged, never remembered per person: who deals with whom
 * changes. Cleared whenever the transaction leaves its chapter or moves to another.
 */
@Entity(
    tableName = "chapter_outsiders",
    primaryKeys = ["transactionId", "friendId"],
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
    indices = [Index("friendId")]
)
data class ChapterOutsider(
    val transactionId: Long,
    val friendId: Long
)
