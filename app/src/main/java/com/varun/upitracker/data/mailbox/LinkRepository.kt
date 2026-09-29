package com.varun.upitracker.data.mailbox

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.declaration.DeclarationRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.LinkInvite
import com.varun.upitracker.database.entity.MailboxMessage
import com.varun.upitracker.database.entity.MailboxMessageState
import com.varun.upitracker.domain.mailbox.InviteCode
import com.varun.upitracker.domain.mailbox.LinkAccept
import com.varun.upitracker.domain.mailbox.LinkInviteCode
import com.varun.upitracker.domain.mailbox.LinkMessages
import com.varun.upitracker.domain.mailbox.MailboxCrypto
import com.varun.upitracker.domain.mailbox.MailboxEnvelope
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.mailbox.PublicMailboxKeys
import com.varun.upitracker.domain.mailbox.Unsealed
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something about linking that the user has to be told, in words they can act on. */
class LinkException(message: String) : IllegalStateException(message)

/** A reply to one of this phone's invites, waiting for the inviter to say "yes, that is them". */
data class PendingLinkReply(
    val messageId: String,
    val friendId: Long,
    val friendName: String,
    /** What the replier's Google account calls them. The thing to recognise, or not. */
    val responderName: String,
    val fingerprint: String,
    val receivedEpoch: Long
)

/**
 * Linking two people: an invite one of them sends through a chat app, a reply the other sends back
 * through the mailbox, and a confirmation that closes the loop.
 *
 * Every step is explicit. A friend is bound to an account only because the person holding this
 * phone picked that friend -- when accepting an invite, or when confirming a reply to one -- and
 * never because a name matched.
 */
class LinkRepository(context: Context, private val db: AppDatabase) {

    companion object {
        private const val TAG = "LinkRepository"

        /** Long enough to be answered over a weekend; short enough that a stray code goes stale. */
        val INVITE_LIFETIME_MS: Long = TimeUnit.DAYS.toMillis(7)
    }

    private val services = MailboxServices.get(context)
    private val identities = MailboxIdentityRepository(context, db)

    /** Lazy so that a repository built only to read a link never builds this one too. */
    private val declarations by lazy { DeclarationRepository(context, db) }

    // --- the inviter's side -------------------------------------------------------------------

    /** Opens an invite for [friendId] and returns the code to send them. */
    suspend fun createInvite(friendId: Long): String = withContext(Dispatchers.IO) {
        val identity = requireIdentity()
        db.friendDao().getFriendById(friendId) ?: throw LinkException("That person is no longer in your list.")
        val now = System.currentTimeMillis()
        val invite = LinkInvite(
            id = MailboxIds.newRandomId(),
            friendId = friendId,
            secret = MailboxIds.newRandomId(),
            createdEpoch = now,
            expiresEpoch = now + INVITE_LIFETIME_MS
        )
        services.auth.authorized { idToken, uid ->
            services.firestore.create(
                idToken,
                "invites",
                invite.id,
                mapOf("owner" to FirestoreValue.Text(uid), "expiresAt" to FirestoreValue.Time(invite.expiresEpoch))
            )
        }
        db.mailboxDao().insertInvite(invite)
        codeFor(invite, identity)
    }

    /** The code for the newest invite still open to [friendId], to send again. Null when there is none. */
    suspend fun openInviteCode(friendId: Long): String? = withContext(Dispatchers.IO) {
        val identity = identities.identity() ?: return@withContext null
        db.mailboxDao().getInvitesForFriend(friendId)
            .firstOrNull { it.expiresEpoch > System.currentTimeMillis() }
            ?.let { codeFor(it, identity) }
    }

    suspend fun withdrawInvites(friendId: Long) = withContext(Dispatchers.IO) {
        db.mailboxDao().getInvitesForFriend(friendId).forEach { invite ->
            runCatching {
                services.auth.authorized { idToken, _ -> services.firestore.delete(idToken, "invites/${invite.id}") }
            }.onFailure { Log.w(TAG, "Could not withdraw an invite on the server; it expires by itself", it) }
            db.mailboxDao().deleteInvite(invite.id)
        }
    }

    suspend fun pendingReplies(): List<PendingLinkReply> = withContext(Dispatchers.IO) {
        db.mailboxDao().getMessagesInStates(listOf(MailboxMessageState.NEW))
            .filter { it.kind == MailboxKind.LINK_ACCEPT.name }
            .mapNotNull { message ->
                val accept = message.body?.let(LinkMessages::decodeAccept) ?: return@mapNotNull null
                val friendId = message.friendId ?: return@mapNotNull null
                val friend = db.friendDao().getFriendById(friendId) ?: return@mapNotNull null
                val keys = PublicMailboxKeys.fromText(accept.encryptionKey, accept.signingKey) ?: return@mapNotNull null
                PendingLinkReply(message.id, friendId, friend.name, accept.responderName, keys.fingerprint, message.receivedEpoch)
            }
    }

    /**
     * "Yes, that is them": links the friend the invite was sent to, and tells the replier.
     *
     * Only reached when [receiveAccept] could not do it while collecting -- the reply arrived while
     * offline, or the server refused the moment it tried.
     */
    suspend fun confirmReply(messageId: String) = withContext(Dispatchers.IO) {
        val (message, accept, keys) = pendingReply(messageId)
        val friendId = message.friendId ?: throw LinkException("That person is no longer in your list.")
        try {
            establishLink(friendId, message.senderUid, accept, keys)
        } catch (error: LinkException) {
            // The reply itself is spent either way: an invite answered and then cancelled cannot be
            // confirmed later, and leaving the row would offer the user a button that always fails.
            db.mailboxDao().setMessageState(messageId, MailboxMessageState.DISMISSED)
            throw error
        }
        db.mailboxDao().setMessageState(messageId, MailboxMessageState.IMPORTED)
        // The balance the link proposed by itself goes out now, rather than at the next collection.
        runCatching { declarations.flushOutbox() }
            .onFailure { Log.w(TAG, "Could not send the proposal the link made", it) }
    }

    /**
     * Records the link both ways: this phone lets them write, tells them so, and pins their keys.
     *
     * Ordered so that a failure leaves nothing half-linked. Their permission is written first --
     * their parcels have to be accepted before they are told they may send any -- and the link is
     * recorded last, once they know, because a link on this side alone would have this phone
     * sending into a mailbox that refuses it.
     */
    private suspend fun establishLink(
        friendId: Long,
        responderUid: String,
        accept: LinkAccept,
        keys: PublicMailboxKeys
    ) {
        val identity = requireIdentity()
        val friendName = db.friendDao().getFriendById(friendId)?.name
            ?: throw LinkException("That person is no longer in your list.")
        requireLinkable(friendId, friendName, responderUid)

        val now = System.currentTimeMillis()
        services.auth.authorized { idToken, uid ->
            services.firestore.set(
                idToken,
                "users/$uid/contacts/$responderUid",
                mapOf("addedAt" to FirestoreValue.Time(now))
            )
            try {
                sendControl(identity, idToken, responderUid, keys, MailboxKind.LINK_CONFIRMED, "")
            } catch (error: MailboxException) {
                if (error.kind != MailboxException.Kind.PERMISSION_DENIED) throw error
                // They withdrew their side after replying, so nothing is linked on either side.
                runCatching { services.firestore.delete(idToken, "users/$uid/contacts/$responderUid") }
                throw LinkException(
                    "${accept.responderName} has cancelled their side of the link. Send $friendName a new " +
                        "invite to try again."
                )
            }
            db.mailboxDao().upsertLink(
                FriendLink(
                    friendId = friendId,
                    uid = responderUid,
                    encKey = keys.encryptionKeyText,
                    sigKey = keys.signingKeyText,
                    fingerprint = keys.fingerprint,
                    remoteName = accept.responderName,
                    state = FriendLinkState.LINKED,
                    linkedEpoch = now
                )
            )
            runCatching { services.firestore.delete(idToken, "invites/${accept.inviteId}") }
        }
        db.mailboxDao().deleteInvite(accept.inviteId)
        // D11: linking is when the two books first meet, so the phone that completes the link offers
        // the balance it holds. Queued only: whoever called this sends it once the link is recorded.
        declarations.queueAutomatic(friendId)
    }

    /**
     * "That is not them." The code evidently reached someone else, so it is closed as well, and the
     * replier is told so their side stops waiting.
     */
    suspend fun declineReply(messageId: String) = withContext(Dispatchers.IO) {
        val identity = requireIdentity()
        val (message, accept, keys) = pendingReply(messageId)
        services.auth.authorized { idToken, _ ->
            // Best effort: they allowed this account to write to them when they replied.
            runCatching { sendControl(identity, idToken, message.senderUid, keys, MailboxKind.UNLINK, LinkMessages.UNLINK_DECLINED) }
            runCatching { services.firestore.delete(idToken, "invites/${accept.inviteId}") }
        }
        db.mailboxDao().deleteInvite(accept.inviteId)
        db.mailboxDao().setMessageState(messageId, MailboxMessageState.DISMISSED)
    }

    // --- the invitee's side -------------------------------------------------------------------

    /**
     * Answers [invite] as this account, having decided the inviter is [friendId]. Refusals come back
     * as sentences; anything the network did wrong is thrown as a [MailboxException].
     */
    suspend fun acceptInvite(invite: LinkInviteCode, friendId: Long): String = withContext(Dispatchers.IO) {
        val identity = requireIdentity()
        val friend = db.friendDao().getFriendById(friendId) ?: throw LinkException("That person is no longer in your list.")
        val now = System.currentTimeMillis()
        if (InviteCode.isExpired(invite, now)) {
            throw LinkException("This invite has expired. Ask ${invite.nameHint} for a new one.")
        }
        if (invite.ownerUid == identity.uid) {
            throw LinkException("That is your own invite. Send it to the person you want to link with.")
        }
        requireLinkable(friendId, friend.name, invite.ownerUid)

        services.auth.authorized { idToken, uid ->
            val ownerKeys = services.firestore.publishedKeys(idToken, invite.ownerUid)
                ?: throw LinkException("${invite.nameHint} has not turned on their friends mailbox, or has turned it off.")
            // The code was carried by a chat the two of you already trust; the server was not. Keys
            // that differ from the code's are someone else's, whatever the server says.
            if (ownerKeys.fingerprint != invite.ownerFingerprint) {
                throw LinkException(
                    "The keys the server holds for ${invite.nameHint} do not match this invite, so it was " +
                        "not accepted. Ask them for a new invite."
                )
            }

            val mine = identity.keys.publicKeys
            val reply = LinkAccept(
                inviteId = invite.inviteId,
                responderName = identity.displayName,
                encryptionKey = mine.encryptionKeyText,
                signingKey = mine.signingKeyText,
                proof = InviteCode.proof(invite.secret, invite.inviteId, uid, mine.fingerprint)
            )

            services.firestore.set(idToken, "users/$uid/contacts/${invite.ownerUid}", mapOf("addedAt" to FirestoreValue.Time(now)))
            val messageId = MailboxIds.newRandomId()
            val envelope = MailboxEnvelope(
                MailboxKind.LINK_ACCEPT, messageId, uid, invite.ownerUid, now, LinkMessages.encodeAccept(reply)
            )
            try {
                services.firestore.create(
                    idToken,
                    "inbox/${invite.ownerUid}/messages",
                    messageId,
                    mapOf(
                        "from" to FirestoreValue.Text(uid),
                        "ciphertext" to FirestoreValue.Bytes(MailboxCrypto.seal(envelope, identity.keys, ownerKeys)),
                        "inviteId" to FirestoreValue.Text(invite.inviteId)
                    )
                )
            } catch (error: MailboxException) {
                if (error.kind != MailboxException.Kind.PERMISSION_DENIED) throw error
                runCatching { services.firestore.delete(idToken, "users/$uid/contacts/${invite.ownerUid}") }
                throw LinkException("This invite is no longer open. Ask ${invite.nameHint} for a new one.")
            }

            db.mailboxDao().upsertLink(
                FriendLink(
                    friendId = friendId,
                    uid = invite.ownerUid,
                    encKey = ownerKeys.encryptionKeyText,
                    sigKey = ownerKeys.signingKeyText,
                    fingerprint = ownerKeys.fingerprint,
                    remoteName = invite.nameHint,
                    state = FriendLinkState.AWAITING_CONFIRMATION,
                    linkedEpoch = now
                )
            )
        }
        friend.name
    }

    // --- either side --------------------------------------------------------------------------

    /**
     * Stops [friendId] writing to this account and forgets their pins. Telling them is best effort;
     * withdrawing their permission is not -- if that fails the link stays, and the caller says why.
     */
    suspend fun unlink(friendId: Long) = withContext(Dispatchers.IO) {
        val link = db.mailboxDao().getLink(friendId) ?: return@withContext
        val identity = identities.identity()
        if (identity != null) {
            services.auth.authorized { idToken, uid ->
                PublicMailboxKeys.fromText(link.encKey, link.sigKey)?.let { keys ->
                    runCatching { sendControl(identity, idToken, link.uid, keys, MailboxKind.UNLINK, LinkMessages.UNLINK_REMOVED) }
                }
                services.firestore.delete(idToken, "users/$uid/contacts/${link.uid}")
            }
        }
        db.mailboxDao().deleteLink(friendId)
        withdrawInvites(friendId)
        // D12: whatever the two agreed still stands -- unlinking changes no balance -- but nothing
        // can be proposed or answered between them any more.
        declarations.archive(friendId)
        // S7: their chapters stop updating here, and keep counting as they last stood.
        db.chapterDao().freezeCopiesOf(friendId)
    }

    // --- called while collecting the inbox ----------------------------------------------------

    /**
     * Checks a reply to one of this phone's invites and, if it holds up, links the friend it was
     * sent to there and then.
     *
     * The reply carries the keys it is signed with, so on its own it proves nothing. Four checks
     * make it mean something: the invite is ours and still open; the keys match what the replier
     * published; the proof shows they hold the code's secret; and the signature is theirs.
     *
     * Those four leave nothing for a confirmation step to decide. The friend was chosen when the
     * invite was made, and the proof shows the reply came from whoever holds the code -- so asking
     * the user again only re-asks whether the code reached the right person. That question is
     * better put afterwards, as "You are now linked with Bob, and here is his key", with an undo,
     * than as a step that leaves the other side waiting until this phone is next opened.
     */
    internal suspend fun receiveAccept(
        documentId: String,
        documentInviteId: String?,
        sentEpoch: Long,
        fromUid: String,
        unsealed: Unsealed,
        idToken: String,
        now: Long
    ): MailboxMessage {
        fun record(
            state: String,
            friendId: Long? = null,
            body: String? = null,
            kind: MailboxKind = MailboxKind.LINK_ACCEPT
        ) = MailboxMessage(
            id = documentId,
            senderUid = fromUid,
            friendId = friendId,
            kind = kind.name,
            body = body,
            sentEpoch = sentEpoch,
            receivedEpoch = now,
            state = state,
            ciphertext = null
        )

        val body = unsealed.unverifiedBody()
        val accept = LinkMessages.decodeAccept(body) ?: return record(MailboxMessageState.UNREADABLE)
        if (documentInviteId != accept.inviteId) return record(MailboxMessageState.UNREADABLE)
        // Withdrawn, already answered, or never ours: nothing to confirm.
        val invite = db.mailboxDao().getInvite(accept.inviteId) ?: return record(MailboxMessageState.DISMISSED)
        if (sentEpoch > invite.expiresEpoch) return record(MailboxMessageState.DISMISSED)

        val claimed = PublicMailboxKeys.fromText(accept.encryptionKey, accept.signingKey)
            ?: return record(MailboxMessageState.UNREADABLE)
        val published = services.firestore.publishedKeys(idToken, fromUid)
        if (published?.fingerprint != claimed.fingerprint) return record(MailboxMessageState.UNREADABLE)
        val expectedProof = InviteCode.proof(invite.secret, invite.id, fromUid, claimed.fingerprint)
        if (!InviteCode.proofMatches(expectedProof, accept.proof)) return record(MailboxMessageState.UNREADABLE)
        unsealed.verifiedBy(claimed) ?: return record(MailboxMessageState.UNREADABLE)

        val accepted = try {
            establishLink(invite.friendId, fromUid, accept, claimed)
            true
        } catch (error: LinkException) {
            // Their side is gone, or this friend is already linked to another account. Nothing to
            // link, but the user should still hear that somebody answered.
            Log.w(TAG, "An invite reply could not be linked", error)
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Offline, or the server refused mid-way. The reply keeps, and the inbox offers Link --
            // and the rest of the collection carries on, since one reply is not worth losing it.
            Log.w(TAG, "An invite reply could not be linked now", error)
            false
        }
        return if (accepted) {
            record(MailboxMessageState.NEW, friendId = invite.friendId, kind = MailboxKind.LINK_CONFIRMED)
        } else {
            record(MailboxMessageState.NEW, friendId = invite.friendId, body = body)
        }
    }

    /** Our reply was confirmed. A no-op for a link that is already whole. */
    internal suspend fun receiveConfirmed(link: FriendLink) {
        if (link.state == FriendLinkState.AWAITING_CONFIRMATION) {
            db.mailboxDao().setLinkState(link.friendId, FriendLinkState.LINKED)
        }
    }

    /** They unlinked us, or did not recognise our reply. Either way, stop accepting their messages too. */
    internal suspend fun receiveUnlink(link: FriendLink, idToken: String, ownUid: String) {
        runCatching { services.firestore.delete(idToken, "users/$ownUid/contacts/${link.uid}") }
            .onFailure { Log.w(TAG, "Could not withdraw a contact after being unlinked", it) }
        db.mailboxDao().deleteLink(link.friendId)
        declarations.archive(link.friendId)
        db.chapterDao().freezeCopiesOf(link.friendId)
    }

    internal suspend fun sendControl(
        identity: MailboxIdentity,
        idToken: String,
        recipientUid: String,
        recipientKeys: PublicMailboxKeys,
        kind: MailboxKind,
        body: String
    ) {
        services.post(identity, idToken, recipientUid, recipientKeys, kind, body)
    }

    // --- helpers ------------------------------------------------------------------------------

    private suspend fun requireIdentity(): MailboxIdentity =
        identities.identity() ?: throw LinkException("Turn the friends mailbox on first, in Settings.")

    /** One account per friend, one friend per account. Re-linking the same pair is allowed. */
    private suspend fun requireLinkable(friendId: Long, friendName: String, uid: String) {
        db.mailboxDao().getLink(friendId)?.let { existing ->
            if (existing.uid != uid) {
                throw LinkException("$friendName is already linked to another DhanMoney account. Unlink them first.")
            }
        }
        db.mailboxDao().getLinkByUid(uid)?.let { existing ->
            if (existing.friendId != friendId) {
                val other = db.friendDao().getFriendById(existing.friendId)?.name ?: "someone else"
                throw LinkException("That DhanMoney account is already linked to $other.")
            }
        }
    }

    private suspend fun pendingReply(messageId: String): Triple<MailboxMessage, LinkAccept, PublicMailboxKeys> {
        val message = db.mailboxDao().getMessage(messageId)
            ?.takeIf { it.kind == MailboxKind.LINK_ACCEPT.name && it.state == MailboxMessageState.NEW }
            ?: throw LinkException("That reply has already been dealt with.")
        val accept = message.body?.let(LinkMessages::decodeAccept)
            ?: throw LinkException("That reply could not be read.")
        val keys = PublicMailboxKeys.fromText(accept.encryptionKey, accept.signingKey)
            ?: throw LinkException("That reply could not be read.")
        return Triple(message, accept, keys)
    }

    private fun codeFor(invite: LinkInvite, identity: MailboxIdentity): String = InviteCode.encode(
        LinkInviteCode(
            inviteId = invite.id,
            secret = invite.secret,
            ownerUid = identity.uid,
            ownerFingerprint = identity.keys.fingerprint,
            nameHint = identity.displayName,
            expiresEpoch = invite.expiresEpoch
        )
    )
}
