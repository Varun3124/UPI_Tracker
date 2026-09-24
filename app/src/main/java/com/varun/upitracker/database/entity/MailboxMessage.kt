package com.varun.upitracker.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What has happened to a message since it arrived. */
object MailboxMessageState {

    /** Opened and verified, and waiting for the user: a parcel to review, or an invite reply to confirm. */
    const val NEW = "NEW"

    /** Dealt with: the parcel was saved, the reply confirmed, or a control message applied. */
    const val IMPORTED = "IMPORTED"

    /** The user looked and chose to do nothing with it. */
    const val DISMISSED = "DISMISSED"

    /**
     * From an account no link here names. Kept sealed, with its ciphertext, so it can still be opened
     * if the link turns up -- a restore from an older backup can lose a link that the server still
     * honours.
     */
    const val HELD = "HELD"

    /** It would not open, or its signature did not check. Never imported; the sender is asked to resend. */
    const val UNREADABLE = "UNREADABLE"
}

/**
 * The phone's own copy of a message taken out of its Firestore inbox.
 *
 * A message is written here **before** its server copy is deleted, and keyed by the server's
 * document id, so a crash between the two leaves at worst a copy that is ignored next time --
 * never a message that is lost.
 *
 * Kept in the database rather than a file so the Drive backup carries it: a parcel collected but
 * not yet reviewed survives a restore, where the server copy is already gone.
 */
@Entity(
    tableName = "mailbox_messages",
    foreignKeys = [
        ForeignKey(
            entity = Friend::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("friendId")]
)
data class MailboxMessage(
    /** The Firestore document id, which the seal is also bound to. */
    @PrimaryKey
    val id: String,

    val senderUid: String,

    /** The friend linked to [senderUid] when it arrived, or null for a held message or an invite reply. */
    val friendId: Long?,

    /** One of [com.varun.upitracker.domain.mailbox.MailboxKind]. */
    val kind: String,

    /** The verified envelope body, or null until it has been opened. */
    val body: String?,

    /** When the server says it was written, from the document's `createTime`. */
    val sentEpoch: Long,

    val receivedEpoch: Long,

    /** One of [MailboxMessageState]. */
    val state: String,

    /** The sealed bytes, base64url -- kept only while [state] is HELD, so it can be opened later. */
    val ciphertext: String?
)
