package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a link between two people stands. Stored as text, like [ActorType][com.varun.upitracker.ui.ActorType]. */
object FriendLinkState {

    /** This side accepted an invite; the inviter has yet to confirm. Nothing can be sent yet. */
    const val AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"

    const val LINKED = "LINKED"

    /**
     * Their published keys no longer match the ones pinned here. Nothing is sent to them, and
     * nothing from them is trusted, until the two re-link -- a key change is either a reinstall
     * that lost its keys, or someone other than them answering for their account.
     */
    const val KEY_CHANGED = "KEY_CHANGED"
}

/**
 * A friend in this book, bound to the DhanMoney account they linked with.
 *
 * One link per friend and one friend per account, both enforced here: two friends sharing an account
 * would split one person's balance in two, and two accounts on one friend would merge two people's.
 *
 * The keys are **pinned** when the link is made and never silently refreshed. That is what lets the
 * mailbox notice a server that starts handing out someone else's keys under a friend's account.
 *
 * Carried by the Drive backup like every other table. A restore brings links back with their pins,
 * which is what lets messages from linked friends keep opening on a new phone.
 */
@Entity(
    tableName = "friend_links",
    foreignKeys = [
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("uid", unique = true)]
)
data class FriendLink(
    @PrimaryKey
    val friendId: Long,

    /** Their Firebase Auth uid. Never their email: an email can change hands. */
    val uid: String,

    /** Their HPKE public keyset as published, base64url. What messages to them are sealed to. */
    val encKey: String,

    /** Their Ed25519 public keyset as published, base64url. What their messages are verified with. */
    val sigKey: String,

    /** [com.varun.upitracker.domain.mailbox.KeyFingerprint] of the two keys above. */
    val fingerprint: String,

    /** What their Google account called them when they linked. Shown beside the friend, never matched. */
    val remoteName: String,

    /** One of [FriendLinkState]. */
    val state: String,

    val linkedEpoch: Long
)
