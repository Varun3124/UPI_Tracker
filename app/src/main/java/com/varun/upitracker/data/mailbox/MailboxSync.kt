package com.varun.upitracker.data.mailbox

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.chapter.SharedChapterRepository
import com.varun.upitracker.data.chapter.SnapshotNews
import com.varun.upitracker.data.declaration.AnswerNews
import com.varun.upitracker.data.declaration.DeclarationRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.MailboxMessage
import com.varun.upitracker.database.entity.MailboxMessageState
import com.varun.upitracker.domain.chapter.ChapterSnapshotFormat
import com.varun.upitracker.domain.declaration.DeclarationMessages
import com.varun.upitracker.domain.mailbox.MailboxCrypto
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.mailbox.PublicMailboxKeys
import com.varun.upitracker.domain.mailbox.UnsealResult
import com.varun.upitracker.domain.mailbox.Unsealed
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.ParcelPerspective
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one collection of the inbox turned up. */
data class SyncReport(
    /** New parcels, counted per friend they came from. */
    val parcelsByFriend: Map<Long, Int>,
    /** Replies that could not be linked as they were collected, and are waiting to be answered by hand. */
    val newLinkReplies: Int,
    /** Friends linked during this collection, from either side of the invite. */
    val confirmedFriendIds: List<Long>,
    val unreadable: Int,
    /** Friends who asked for an agreed balance, waiting for an answer. */
    val proposalsFrom: List<Long> = emptyList(),
    /** Friends who accepted one of this phone's proposals. */
    val acceptedBy: List<Long> = emptyList(),
    /** Friends who turned one down. */
    val deniedBy: List<Long> = emptyList(),
    /** Friends who shared a chapter with this phone for the first time. */
    val chaptersFrom: List<Long> = emptyList()
)

/** What one collection learnt along the way, gathered while it runs and reported at the end. */
private class Gathered {
    val confirmed = mutableListOf<Long>()
    val proposals = mutableListOf<Long>()
    val accepted = mutableListOf<Long>()
    val denied = mutableListOf<Long>()
    val chapters = mutableListOf<Long>()
}

/**
 * Collects the inbox: takes every message out of Firestore, opens and checks it, and keeps the
 * phone's own copy.
 *
 * Nothing collected here moves money. A parcel is stored for the user to review, exactly as a pasted
 * one would be reviewed; link messages only ever change links.
 *
 * Run at launch, when the dashboard comes back (throttled), and when the inbox screen asks. There is
 * no background schedule: every empty collection still costs a read against a shared free quota,
 * and nothing here is urgent enough to spend it on.
 */
class MailboxSync(context: Context, private val db: AppDatabase) {

    companion object {
        private const val TAG = "MailboxSync"

        val THROTTLE_MS: Long = TimeUnit.MINUTES.toMillis(10)

        /** How long a message from an account with no link here is kept, in case the link turns up. */
        private val HELD_FOR_MS: Long = TimeUnit.DAYS.toMillis(30)

        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 10

        /** Process-wide: a launch and a dashboard resume must not collect, and apply UNLINKs, twice. */
        private val mutex = Mutex()
    }

    private val services = MailboxServices.get(context)
    private val identities = MailboxIdentityRepository(context, db)
    private val links = LinkRepository(context, db)
    private val declarations = DeclarationRepository(context, db)
    private val sharedChapters = SharedChapterRepository(context, db)

    /** Null when the mailbox is off, or [force] is false and the last collection was recent. */
    suspend fun run(force: Boolean = false): SyncReport? = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Throttled before the keys are touched: the dashboard asks on every resume, and most of
            // those asks should cost nothing but one small file read.
            val session = services.auth.session() ?: return@withContext null
            val now = System.currentTimeMillis()
            if (!force && now - session.lastSyncEpoch < THROTTLE_MS) return@withContext null
            val identity = identities.identity() ?: return@withContext null

            val collected = mutableListOf<MailboxMessage>()
            val gathered = Gathered()

            var pageToken: String? = null
            var pages = 0
            do {
                val idToken = services.auth.idToken()
                val page = services.firestore.list(idToken, "inbox/${identity.uid}/messages", PAGE_SIZE, pageToken)
                page.documents.forEach { document ->
                    val message = collect(document, identity, idToken, now, gathered)
                    if (message != null && db.mailboxDao().insertMessageIfNew(message) != -1L) collected += message
                    // Stored, already here, or worthless: either way the server copy can go. If this
                    // fails, the copy comes back next time and is recognised by its id.
                    runCatching { services.firestore.delete(idToken, "inbox/${identity.uid}/messages/${document.id}") }
                        .onFailure { Log.w(TAG, "Could not clear a collected message", it) }
                }
                pageToken = page.nextPageToken
                pages++
            } while (pageToken != null && pages < MAX_PAGES)

            collected += reopenHeld(identity, now, gathered)
            db.mailboxDao().deleteMessagesInStateBefore(MailboxMessageState.HELD, now - HELD_FOR_MS)
            services.auth.recordSync(now)

            // Proposals made while offline, and the one a link made by itself while being collected
            // above, go out now. A failure keeps them for next time.
            try {
                declarations.flushOutbox()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not send waiting proposals", error)
            }
            // Members of shared chapters who are behind -- including anyone who has just relinked --
            // get the chapter as it stands now. Never throws.
            sharedChapters.publishPending()

            SyncReport(
                parcelsByFriend = collected
                    .filter { it.kind == MailboxKind.PARCEL.name && it.state == MailboxMessageState.NEW }
                    .mapNotNull { it.friendId }
                    .groupingBy { it }
                    .eachCount(),
                newLinkReplies = collected.count {
                    it.kind == MailboxKind.LINK_ACCEPT.name && it.state == MailboxMessageState.NEW
                },
                // Distinct: one friend can appear from both sides of a link in the same collection.
                confirmedFriendIds = gathered.confirmed.distinct(),
                unreadable = collected.count { it.state == MailboxMessageState.UNREADABLE },
                proposalsFrom = gathered.proposals.distinct(),
                acceptedBy = gathered.accepted.distinct(),
                deniedBy = gathered.denied.distinct(),
                chaptersFrom = gathered.chapters.distinct()
            )
        }
    }

    /** The phone's record of one document, having applied what it says. Null for a document that is not a message. */
    private suspend fun collect(
        document: FirestoreDocument,
        identity: MailboxIdentity,
        idToken: String,
        now: Long,
        gathered: Gathered
    ): MailboxMessage? {
        if (!MailboxIds.isRandomId(document.id)) return null
        // Already collected once, and already applied: an UNLINK must never be applied twice.
        if (db.mailboxDao().getMessage(document.id) != null) return null
        val from = document.text("from")?.takeIf(MailboxIds::isUid) ?: return null
        val ciphertext = document.bytes("ciphertext") ?: return null

        val unsealed = when (val result = MailboxCrypto.unseal(ciphertext, identity.keys, from, identity.uid, document.id)) {
            is UnsealResult.Ok -> result.unsealed
            else -> return MailboxMessage(
                id = document.id,
                senderUid = from,
                friendId = db.mailboxDao().getLinkByUid(from)?.friendId,
                kind = "UNKNOWN",
                body = null,
                sentEpoch = document.createTimeEpoch,
                receivedEpoch = now,
                state = MailboxMessageState.UNREADABLE,
                ciphertext = null
            )
        }

        if (unsealed.kind == MailboxKind.LINK_ACCEPT) {
            val reply = links.receiveAccept(
                document.id, document.text("inviteId"), document.createTimeEpoch, from, unsealed, idToken, now
            )
            // Linked as it was collected: the row is a notice about it rather than a reply to answer.
            if (reply.kind == MailboxKind.LINK_CONFIRMED.name) reply.friendId?.let { gathered.confirmed += it }
            return reply
        }

        val link = db.mailboxDao().getLinkByUid(from)
            ?: return MailboxMessage(
                id = document.id,
                senderUid = from,
                friendId = null,
                kind = unsealed.kind.name,
                body = null,
                sentEpoch = document.createTimeEpoch,
                receivedEpoch = now,
                state = MailboxMessageState.HELD,
                ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
            )
        return fromLink(document.id, document.createTimeEpoch, from, link, unsealed, identity, idToken, now, gathered)
    }

    /**
     * A message from a linked friend, checked against the keys pinned when the two linked -- never
     * against whatever the server publishes today.
     */
    private suspend fun fromLink(
        messageId: String,
        sentEpoch: Long,
        from: String,
        link: FriendLink,
        unsealed: Unsealed,
        identity: MailboxIdentity,
        idToken: String,
        now: Long,
        gathered: Gathered
    ): MailboxMessage {
        fun record(kind: MailboxKind, state: String, body: String? = null) = MailboxMessage(
            id = messageId,
            senderUid = from,
            friendId = link.friendId,
            kind = kind.name,
            body = body,
            sentEpoch = sentEpoch,
            receivedEpoch = now,
            state = state,
            ciphertext = null
        )

        val pinned = PublicMailboxKeys.fromText(link.encKey, link.sigKey)
        val envelope = pinned?.let(unsealed::verifiedBy)
        if (envelope == null) {
            // Their key changed, or someone else wrote as them. Nothing in it is trusted either way,
            // and nothing more goes to them until the two link again.
            val published = runCatching { services.firestore.publishedKeys(idToken, from) }.getOrNull()
            if (published != null && published.fingerprint != link.fingerprint) {
                db.mailboxDao().setLinkState(link.friendId, FriendLinkState.KEY_CHANGED)
            }
            return record(unsealed.kind, MailboxMessageState.UNREADABLE)
        }

        return when (envelope.kind) {
            MailboxKind.PARCEL -> {
                val parsed = ParcelFormat.parse(envelope.body, ParcelFormat.MAILBOX_VERSION)
                val usable = parsed is ParcelDecodeResult.Ok &&
                    parsed.parcel.transactions.isNotEmpty() &&
                    ParcelPerspective.linkedUidsAreThirdParties(parsed.parcel, from, identity.uid)
                if (usable) {
                    record(MailboxKind.PARCEL, MailboxMessageState.NEW, envelope.body)
                } else {
                    record(MailboxKind.PARCEL, MailboxMessageState.UNREADABLE)
                }
            }
            MailboxKind.LINK_CONFIRMED -> {
                val completed = link.state == FriendLinkState.AWAITING_CONFIRMATION
                if (completed) gathered.confirmed += link.friendId
                links.receiveConfirmed(link)
                // NEW while it says something the user has not been told: the link they started is
                // now whole. A repeat of one already recorded is filed away silently.
                record(
                    MailboxKind.LINK_CONFIRMED,
                    if (completed) MailboxMessageState.NEW else MailboxMessageState.IMPORTED
                )
            }
            MailboxKind.UNLINK -> {
                links.receiveUnlink(link, idToken, identity.uid)
                record(MailboxKind.UNLINK, MailboxMessageState.IMPORTED, envelope.body)
            }
            // Replies to invites are handled before a link is looked for; one arriving here is not a reply.
            MailboxKind.LINK_ACCEPT -> record(MailboxKind.LINK_ACCEPT, MailboxMessageState.UNREADABLE)
            // What it asks is kept in iou_declarations, which is where the inbox and the friend page read
            // it from; this row only records that the message was applied.
            MailboxKind.DECLARATION_PROPOSAL -> {
                val proposal = DeclarationMessages.decodeProposal(envelope.body)
                    ?: return record(MailboxKind.DECLARATION_PROPOSAL, MailboxMessageState.UNREADABLE)
                if (declarations.receiveProposal(link.friendId, proposal)) gathered.proposals += link.friendId
                record(MailboxKind.DECLARATION_PROPOSAL, MailboxMessageState.IMPORTED, envelope.body)
            }
            MailboxKind.DECLARATION_ANSWER -> {
                val answer = DeclarationMessages.decodeAnswer(envelope.body)
                    ?: return record(MailboxKind.DECLARATION_ANSWER, MailboxMessageState.UNREADABLE)
                when (declarations.receiveAnswer(link.friendId, answer)) {
                    AnswerNews.ACCEPTED -> gathered.accepted += link.friendId
                    AnswerNews.DENIED -> gathered.denied += link.friendId
                    AnswerNews.WITHDRAWN, null -> Unit
                }
                record(MailboxKind.DECLARATION_ANSWER, MailboxMessageState.IMPORTED, envelope.body)
            }
            // Applied straight away, no consent asked: a linked friend's chapter updates by itself (S2).
            // The body is kept in the chapter, which reads its plan and rows from it; not here too.
            MailboxKind.CHAPTER_SNAPSHOT -> {
                val snapshot = ChapterSnapshotFormat.decode(envelope.body, senderUid = from, readerUid = identity.uid)
                    ?: return record(MailboxKind.CHAPTER_SNAPSHOT, MailboxMessageState.UNREADABLE)
                val news = sharedChapters.receiveSnapshot(link.friendId, from, identity.uid, snapshot, envelope.body)
                if (news == SnapshotNews.NEW) gathered.chapters += link.friendId
                record(MailboxKind.CHAPTER_SNAPSHOT, MailboxMessageState.IMPORTED)
            }
            MailboxKind.CHAPTER_ENDED -> {
                val (shareId, end) = ChapterSnapshotFormat.decodeEnded(envelope.body)
                    ?: return record(MailboxKind.CHAPTER_ENDED, MailboxMessageState.UNREADABLE)
                sharedChapters.receiveEnded(link.friendId, shareId, end)
                record(MailboxKind.CHAPTER_ENDED, MailboxMessageState.IMPORTED, envelope.body)
            }
            // From a newer version of the app. Genuine and signed, but nothing here can read it.
            MailboxKind.UNSUPPORTED -> record(MailboxKind.UNSUPPORTED, MailboxMessageState.UNREADABLE)
        }
    }

    /** Messages held for want of a link, opened now that one exists. */
    private suspend fun reopenHeld(identity: MailboxIdentity, now: Long, gathered: Gathered): List<MailboxMessage> {
        val reopened = mutableListOf<MailboxMessage>()
        db.mailboxDao().getMessagesInStates(listOf(MailboxMessageState.HELD)).forEach { held ->
            val link = db.mailboxDao().getLinkByUid(held.senderUid) ?: return@forEach
            val sealed = held.ciphertext?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() } ?: return@forEach
            val result = MailboxCrypto.unseal(sealed, identity.keys, held.senderUid, identity.uid, held.id)
            val unsealed = (result as? UnsealResult.Ok)?.unsealed ?: return@forEach
            val resolved = fromLink(held.id, held.sentEpoch, held.senderUid, link, unsealed, identity, services.auth.idToken(), now, gathered)
            db.mailboxDao().resolveHeldMessage(held.id, resolved.body, resolved.friendId, resolved.state)
            reopened += resolved
        }
        return reopened
    }
}
