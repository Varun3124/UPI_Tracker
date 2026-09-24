package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An invite to link that this phone sent a friend and has not closed.
 *
 * The row existing is what "open" means: confirming, declining or withdrawing it deletes the row,
 * along with the invite document in Firestore. An expired row is still open until then, so the
 * friend's page can say the invite ran out rather than forgetting it was ever sent.
 */
@Entity(
    tableName = "link_invites",
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
data class LinkInvite(
    /** Also the Firestore document id: the part of the code the server sees. */
    @PrimaryKey
    val id: String,

    /** The friend in this book the invite was sent to. The reply is bound to them, not to a name. */
    val friendId: Long,

    /** The part of the code the server never sees. Checks the reply's proof. */
    val secret: String,

    val createdEpoch: Long,
    val expiresEpoch: Long
)
