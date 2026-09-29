package com.varun.upitracker.data.mailbox

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.mailbox.PublicMailboxKeys

/** How sending one sealed message to a linked friend went, short of the network failing. */
enum class Delivery {
    SENT,

    /** They have turned their mailbox off: nothing is published to seal to. */
    MAILBOX_OFF,

    /** The keys they publish are not the ones pinned when the two linked. The link is marked. */
    KEY_CHANGED,

    /** Their inbox refused it: they have unlinked this account. */
    REFUSED
}

/**
 * Seals [body] to [link]'s pinned keys and posts it -- after checking those keys are still the ones
 * the friend publishes, the check sending a parcel makes, so a changed key is caught before anything
 * is sealed to a key nobody holds.
 *
 * Everything a caller can act on comes back as a [Delivery]; a network failure is thrown as the
 * [MailboxException] it is, for the caller to keep the message for later.
 */
internal suspend fun MailboxServices.deliver(
    db: AppDatabase,
    identity: MailboxIdentity,
    link: FriendLink,
    kind: MailboxKind,
    body: String
): Delivery {
    val pinned = PublicMailboxKeys.fromText(link.encKey, link.sigKey) ?: return Delivery.KEY_CHANGED
    return auth.authorized { idToken, _ ->
        val published = firestore.publishedKeys(idToken, link.uid)
        when {
            published == null -> Delivery.MAILBOX_OFF
            published.fingerprint != link.fingerprint -> {
                db.mailboxDao().setLinkState(link.friendId, FriendLinkState.KEY_CHANGED)
                Delivery.KEY_CHANGED
            }
            else -> try {
                post(identity, idToken, link.uid, pinned, kind, body)
                Delivery.SENT
            } catch (error: MailboxException) {
                if (error.kind != MailboxException.Kind.PERMISSION_DENIED) throw error
                Delivery.REFUSED
            }
        }
    }
}
