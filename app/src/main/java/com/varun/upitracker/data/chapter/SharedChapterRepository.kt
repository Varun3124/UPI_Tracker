package com.varun.upitracker.data.chapter

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.data.declaration.CheckpointStore
import com.varun.upitracker.data.mailbox.Delivery
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxServices
import com.varun.upitracker.data.mailbox.deliver
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterPerson
import com.varun.upitracker.database.entity.ChapterShare
import com.varun.upitracker.database.entity.ChapterShareMode
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.chapter.ChapterEnd
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterSnapshot
import com.varun.upitracker.domain.chapter.ChapterSnapshotFormat
import com.varun.upitracker.domain.chapter.ClaimMatcher
import com.varun.upitracker.domain.chapter.ClaimRow
import com.varun.upitracker.domain.chapter.LocalRow
import com.varun.upitracker.domain.chapter.SnapshotBuilder
import com.varun.upitracker.domain.chapter.SnapshotHint
import com.varun.upitracker.domain.chapter.SnapshotSource
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Something about sharing a chapter the user has to be told, in words they can act on. */
class SharedChapterException(message: String) : IllegalStateException(message)

/** What a snapshot did here. */
enum class SnapshotNews { NEW, UPDATED, IGNORED }

/** A member of one of this phone's shared chapters, as its screen lists them. */
data class ChapterRecipient(
    val friendId: Long,
    val name: String,
    /** Linked, so their copy is kept up to date. Otherwise they can only be pasted a static copy. */
    val linked: Boolean,
    /** Has the chapter as it stands now. */
    val upToDate: Boolean
)

/**
 * Shared chapters: the owner's side, which sends each linked member a snapshot after every change, and
 * the member's side, which keeps a copy that uses the owner's plan. See docs/declarations-design.md
 * S1-S10.
 *
 * A snapshot is the whole chapter, not a change to it, so a member who missed three updates is right
 * again after the fourth, and one arriving out of order is simply older than what is here.
 */
class SharedChapterRepository(private val context: Context, private val db: AppDatabase) {

    private companion object {
        const val TAG = "SharedChapters"

        /** Process-wide: a collection and a screen closing at once must not send the same version twice. */
        val publishing = Mutex()

        /** SQLite's parameter limit, with room to spare. */
        const val LOOKUP_CHUNK = 400
    }

    private val services = MailboxServices.get(context)
    private val identities = MailboxIdentityRepository(context, db)

    // --- the owner's side -----------------------------------------------------------------------------

    /** S1: gives the chapter the id every phone will know it by, and sends it to every linked member. */
    suspend fun share(chapterId: Long): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chapter = requireOwn(chapterId)
            db.chapterDao().update(
                chapter.copy(
                    shareId = chapter.shareId ?: MailboxIds.newRandomId(),
                    shareMode = ChapterShareMode.SHARED,
                    shareVersion = chapter.shareVersion + 1
                )
            )
        }
        publishPending()
    }

    /** Stops sending it. Members' copies freeze where they are and keep counting (S7). */
    suspend fun stopSharing(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = requireOwn(chapterId)
        if (chapter.mode != ChapterShareMode.SHARED) return@withContext
        notifyEnded(chapter, ChapterEnd.UNSHARED)
        db.withTransaction {
            db.chapterDao().getById(chapterId)?.let { db.chapterDao().update(it.copy(shareMode = ChapterShareMode.PRIVATE)) }
            db.chapterDao().deleteShares(chapterId)
        }
    }

    /**
     * Deletes one of this phone's chapters, telling each member holding a copy first so theirs goes
     * too (S7). The telling is best effort: a member it cannot reach keeps a copy that never updates
     * again -- which is all a frozen copy is.
     */
    suspend fun deleteOwn(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = requireOwn(chapterId)
        if (chapter.shareId != null) notifyEnded(chapter, ChapterEnd.DELETED)
        ChapterRepository(db).delete(chapterId)
    }

    suspend fun recipients(chapterId: Long): List<ChapterRecipient> = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().getById(chapterId) ?: return@withContext emptyList()
        val shares = db.chapterDao().sharesFor(chapterId).associateBy { it.friendId }
        db.chapterDao().memberIds(chapterId).mapNotNull { friendId ->
            val friend = db.friendDao().getFriendById(friendId) ?: return@mapNotNull null
            ChapterRecipient(
                friendId = friendId,
                name = friend.name,
                linked = db.mailboxDao().getLink(friendId)?.state == FriendLinkState.LINKED,
                upToDate = (shares[friendId]?.sentVersion ?: -1L) >= chapter.shareVersion
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Sends a fresh snapshot to every linked member of every shared chapter who is behind. Never
     * throws: a member who cannot be reached now is still behind next time, and gets it then.
     */
    suspend fun publishPending(): Int = withContext(Dispatchers.IO) {
        publishing.withLock {
            val identity = identities.identity() ?: return@withLock 0
            var sent = 0
            db.chapterDao().sharedByMe().forEach { chapter ->
                val links = db.chapterDao().memberIds(chapter.id).mapNotNull { id ->
                    db.mailboxDao().getLink(id)?.takeIf { it.state == FriendLinkState.LINKED }
                }
                val shares = db.chapterDao().sharesFor(chapter.id).associateBy { it.friendId }
                val behind = links.filter { (shares[it.friendId]?.sentVersion ?: -1L) < chapter.shareVersion }
                if (behind.isEmpty()) return@forEach

                // Everyone the chapter goes to may be named to the others by account: they were sent it together.
                val source = sourceFor(chapter, receivingUids = links.associate { it.friendId to it.uid })
                behind.forEach { link ->
                    try {
                        val snapshot = SnapshotBuilder.build(source, link.friendId, hintFor(chapter.id, link.friendId), pasteTokenFor(link.friendId))
                        val delivery = services.deliver(
                            db, identity, link, MailboxKind.CHAPTER_SNAPSHOT, ChapterSnapshotFormat.encode(snapshot)
                        )
                        if (delivery == Delivery.SENT) {
                            db.chapterDao().upsertShare(ChapterShare(chapter.id, link.friendId, chapter.shareVersion))
                            sent++
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not send a chapter to one of its members", error)
                    }
                }
            }
            sent
        }
    }

    /**
     * The chapter as [recipientFriendId] would receive it, for pasting to someone who is not linked:
     * a static copy that never updates (S2). It names nobody by account and carries no hint.
     */
    suspend fun snapshotFor(chapterId: Long, recipientFriendId: Long): ChapterSnapshot = withContext(Dispatchers.IO) {
        val chapter = db.withTransaction {
            val own = requireOwn(chapterId)
            if (own.shareId != null) own else own.copy(shareId = MailboxIds.newRandomId()).also { db.chapterDao().update(it) }
        }
        SnapshotBuilder.build(sourceFor(chapter, receivingUids = emptyMap()), recipientFriendId, hint = null, pasteTokenFor(recipientFriendId))
    }

    // --- the member's side ----------------------------------------------------------------------------

    /**
     * Takes a snapshot from [ownerFriendId]: a new copy, or an update to theirs. A share id already
     * belonging to another owner -- or to one of this phone's own chapters -- is refused: nobody updates
     * someone else's chapter by naming its id.
     *
     * [body] is kept as sent, and is what the copy reads its plan and rows from afterwards.
     */
    suspend fun receiveSnapshot(
        ownerFriendId: Long,
        ownerUid: String?,
        myUid: String?,
        snapshot: ChapterSnapshot,
        body: String,
        mode: String = ChapterShareMode.REPLICA
    ): SnapshotNews = withContext(Dispatchers.IO) {
        db.withTransaction { applySnapshot(ownerFriendId, ownerUid, myUid, snapshot, body, mode) }
    }

    /** The owner stopped sharing it, or deleted it (S7). */
    suspend fun receiveEnded(ownerFriendId: Long, shareId: String, end: ChapterEnd) = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().getByShareId(shareId)?.takeIf { it.ownerFriendId == ownerFriendId }
            ?: return@withContext
        when (end) {
            ChapterEnd.UNSHARED -> db.chapterDao().update(chapter.copy(shareMode = ChapterShareMode.FROZEN, isActive = false))
            // Its claimed rows go back to the direct ledger, exactly as deleting a chapter of one's own does.
            ChapterEnd.DELETED -> ChapterRepository(db).delete(chapter.id)
        }
    }

    /** S7: a copy that no longer updates can be removed. One still kept up to date cannot. */
    suspend fun removeCopy(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().getById(chapterId)
            ?: throw SharedChapterException("That chapter is gone.")
        if (chapter.isOwn) throw SharedChapterException("That chapter is your own.")
        if (chapter.mode == ChapterShareMode.REPLICA) {
            throw SharedChapterException("It stays while it is shared with you. It can be removed once it stops updating.")
        }
        ChapterRepository(db).delete(chapterId)
    }

    /**
     * S4: says who one of the owner's people is here, or forgets it with a null [friendId]. The owner is
     * already counted as themselves, so nobody can be mapped to them.
     */
    suspend fun mapPerson(chapterId: Long, personKey: String, friendId: Long?) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chapter = db.chapterDao().getById(chapterId)?.takeIf { !it.isOwn }
                ?: throw SharedChapterException("That chapter is gone.")
            if (friendId != null && friendId == chapter.ownerFriendId) {
                throw SharedChapterException("That is who shared it. They are already counted.")
            }
            if (friendId == null) {
                db.chapterDao().deletePerson(chapterId, personKey)
            } else {
                db.chapterDao().upsertPerson(ChapterPerson(chapterId, personKey, friendId))
            }
            ReplicaBook(db).recomputeInTransaction(chapter)
        }
    }

    // --- the in-transaction half -------------------------------------------------------------------------

    private suspend fun applySnapshot(
        ownerFriendId: Long,
        ownerUid: String?,
        myUid: String?,
        snapshot: ChapterSnapshot,
        body: String,
        mode: String
    ): SnapshotNews {
        val existing = db.chapterDao().getByShareId(snapshot.shareId)
        if (existing != null) {
            if (existing.ownerFriendId != ownerFriendId) return SnapshotNews.IGNORED
            // A pasted copy never replaces one kept up to date, and nothing older replaces anything.
            if (existing.mode == ChapterShareMode.REPLICA && mode != ChapterShareMode.REPLICA) return SnapshotNews.IGNORED
            if (snapshot.version < existing.shareVersion) return SnapshotNews.IGNORED
            if (snapshot.version == existing.shareVersion && existing.mode == mode) return SnapshotNews.IGNORED
        }

        val now = System.currentTimeMillis()
        val closedEpoch = if (snapshot.state == ChapterState.CLOSED) existing?.closedEpoch ?: now else null
        val chapterId = if (existing == null) {
            db.chapterDao().insert(
                Chapter(
                    name = snapshot.name,
                    createdEpoch = now,
                    state = snapshot.state,
                    closedEpoch = closedEpoch,
                    notes = snapshot.notes,
                    shareId = snapshot.shareId,
                    ownerFriendId = ownerFriendId,
                    shareVersion = snapshot.version,
                    snapshot = body,
                    shareMode = mode
                )
            )
        } else {
            db.chapterDao().update(
                existing.copy(
                    name = snapshot.name,
                    state = snapshot.state,
                    closedEpoch = closedEpoch,
                    notes = snapshot.notes,
                    shareVersion = snapshot.version,
                    snapshot = body,
                    shareMode = mode,
                    isActive = false
                )
            )
            existing.id
        }
        val chapter = requireNotNull(db.chapterDao().getById(chapterId))

        // S5: this phone's own copies of its rows move into it; rows the owner dropped move back out.
        val moved = claim(chapter, snapshot, ownerUid, myUid, pasteTokenFor(ownerFriendId))
        // D6: agreements that counted it by share id before it got here now count it for real.
        val waiting = db.declarationDao().friendIdsAwaitingShare(snapshot.shareId)
        db.declarationDao().attachShare(snapshot.shareId, chapterId)
        // S8: what the owner's agreement with this phone says about it.
        val hinted = applyHint(chapter, ownerFriendId, snapshot.hint)

        ReplicaBook(db).recomputeInTransaction(chapter)
        val chapters = ChapterRepository(db)
        chapters.replayInTransaction(chapters.affectedFriends(moved) + waiting + hinted)
        return if (existing == null) SnapshotNews.NEW else SnapshotNews.UPDATED
    }

    /**
     * Moves this phone's own copies of the snapshot's rows into the copy, and anything the owner no
     * longer has back out, returning every row that moved. A refund moves with its purchase (R6); a row
     * already in some other chapter is left where it is.
     */
    private suspend fun claim(
        chapter: Chapter,
        snapshot: ChapterSnapshot,
        ownerUid: String?,
        myUid: String?,
        pasteToken: String?
    ): List<Transaction> {
        val claimRows = snapshot.rows.zip(snapshot.extras) { row, extra ->
            ClaimRow(row.shareRef, row.legacyRef, extra.sourceRef, extra.pending)
        }
        val lookups = ClaimMatcher.lookups(claimRows, ownerUid, myUid, pasteToken)
        val candidates = buildList {
            lookups.shareRefs.chunked(LOOKUP_CHUNK).forEach { addAll(db.transactionDao().findByShareRefs(it)) }
            lookups.sharedRefIds.chunked(LOOKUP_CHUNK).forEach { addAll(db.transactionDao().findBySharedRefIds(it)) }
            lookups.ids.chunked(LOOKUP_CHUNK).forEach { addAll(db.transactionDao().findByIds(it)) }
        }.distinctBy { it.id }
        val matched = ClaimMatcher.match(
            claimRows,
            candidates.map { LocalRow(it.id, it.shareRef, it.sharedRefId) },
            ownerUid,
            myUid,
            pasteToken
        )

        val byId = candidates.associateBy { it.id }
        val held = db.chapterDao().taggedTransactions(chapter.id).filter { it.refundsTransactionId == null }
        val toClaim = matched.mapNotNull { byId[it] }
            .filter { it.refundsTransactionId == null && it.chapterId == null }
        val toRelease = held.filter { it.id !in matched }

        toClaim.forEach {
            db.transactionDao().setChapter(it.id, chapter.id)
            db.transactionDao().setChapterForRefundsOf(it.id, chapter.id)
        }
        toRelease.forEach {
            db.transactionDao().setChapter(it.id, null)
            db.transactionDao().setChapterForRefundsOf(it.id, null)
        }
        return toClaim + toRelease
    }

    /** Sets this phone's part for the chapter in the agreement the hint names. Returns who to replay. */
    private suspend fun applyHint(chapter: Chapter, ownerFriendId: Long, hint: SnapshotHint?): Set<Long> {
        hint ?: return emptySet()
        val declaration = db.declarationDao().get(hint.declarationId)?.takeIf { it.friendId == ownerFriendId }
            ?: return emptySet()
        val shareId = requireNotNull(chapter.shareId)
        val part = db.declarationDao().partForChapter(declaration.id, chapter.id)
            ?: db.declarationDao().partForShare(declaration.id, shareId)
        when {
            part == null && hint.amountPaise == 0L -> return emptySet()
            part == null -> db.declarationDao().insertPart(
                DeclarationPart(declarationId = declaration.id, chapterId = chapter.id, shareId = shareId, amountPaise = hint.amountPaise)
            )
            part.amountPaise == hint.amountPaise && part.chapterId == chapter.id -> return emptySet()
            else -> db.declarationDao().updatePart(part.copy(chapterId = chapter.id, shareId = shareId, amountPaise = hint.amountPaise))
        }
        return setOf(ownerFriendId)
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Everything about [chapter] a snapshot is built from. Every row gets its reference first, so all copies agree. */
    private suspend fun sourceFor(chapter: Chapter, receivingUids: Map<Long, String>): SnapshotSource {
        val transactions = db.chapterDao().taggedTransactions(chapter.id)
        transactions.filter { it.shareRef == null }.forEach {
            db.transactionDao().assignShareRef(it.id, MailboxIds.newRandomId())
        }
        val refreshed = if (transactions.isEmpty()) emptyList() else db.transactionDao().findByIds(transactions.map { it.id })
        val refs = refreshed.associate { it.id to it.shareRef }
        val shares = if (refreshed.isEmpty()) emptyMap() else {
            db.transactionShareDao().getSharesForTransactions(refreshed.map { it.id }).groupBy { it.transactionId }
        }
        val rows = refreshed.map { TaggedTx(it, shares[it.id].orEmpty()) }
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }
        return SnapshotSource(
            chapter = chapter,
            result = ChapterMath.compute(rows),
            memberIds = db.chapterDao().memberIds(chapter.id).toSet(),
            rows = rows,
            shareRefOf = { requireNotNull(refs[it]) { "Every row has a reference by now." } },
            friendName = { friendNames[it] },
            merchantName = { merchantNames[it] },
            receivingUids = receivingUids,
            sentEpoch = System.currentTimeMillis()
        )
    }

    /** S8: this phone's part for the chapter in its agreement with [friendId], from their seat. */
    private suspend fun hintFor(chapterId: Long, friendId: Long): SnapshotHint? {
        val checkpoint = CheckpointStore(db).effective(friendId) ?: return null
        val part = db.declarationDao().partForChapter(checkpoint.id, chapterId)
        return SnapshotHint(checkpoint.id, -(part?.amountPaise ?: 0L))
    }

    private fun pasteTokenFor(friendId: Long): String? = ParcelExportRepository(db, context).existingOriginToken(friendId)

    private suspend fun notifyEnded(chapter: Chapter, end: ChapterEnd) {
        val shareId = chapter.shareId ?: return
        val identity = identities.identity() ?: return
        val body = ChapterSnapshotFormat.encodeEnded(shareId, end)
        db.chapterDao().sharesFor(chapter.id).forEach { share ->
            val link = db.mailboxDao().getLink(share.friendId)?.takeIf { it.state == FriendLinkState.LINKED }
                ?: return@forEach
            try {
                services.deliver(db, identity, link, MailboxKind.CHAPTER_ENDED, body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not tell a member their copy has ended", error)
            }
        }
    }

    private suspend fun requireOwn(chapterId: Long): Chapter {
        val chapter = db.chapterDao().getById(chapterId) ?: throw SharedChapterException("That chapter is gone.")
        if (!chapter.isOwn) throw SharedChapterException("Only whoever shared it can change it.")
        return chapter
    }
}
