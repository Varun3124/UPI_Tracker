package com.varun.upitracker.data.declaration

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.data.mailbox.Delivery
import com.varun.upitracker.data.mailbox.MailboxException
import com.varun.upitracker.data.mailbox.MailboxIdentityRepository
import com.varun.upitracker.data.mailbox.MailboxServices
import com.varun.upitracker.data.mailbox.deliver
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.declaration.ChapterShareOf
import com.varun.upitracker.domain.declaration.Checkpoints
import com.varun.upitracker.domain.declaration.DeclarationAnswer
import com.varun.upitracker.domain.declaration.DeclarationEvent
import com.varun.upitracker.domain.declaration.DeclarationFlow
import com.varun.upitracker.domain.declaration.DeclarationMessages
import com.varun.upitracker.domain.declaration.DeclarationParts
import com.varun.upitracker.domain.declaration.DeclarationProposal
import com.varun.upitracker.domain.declaration.DeclarationReceipt
import com.varun.upitracker.domain.declaration.DeclarationSet
import com.varun.upitracker.domain.declaration.DeclarationVerdict
import com.varun.upitracker.domain.declaration.ProposalPart
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.ledger.DeltaRecorder
import com.varun.upitracker.ledger.RoomReplaySource
import com.varun.upitracker.ledger.ScopedLedgerPort
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something about an agreed balance the user has to be told, in words they can act on. */
class DeclarationException(message: String) : IllegalStateException(message)

/** How sending a proposal went. */
enum class SendResult {
    SENT,

    /** Kept in the outbox, and sent at the next mailbox check: offline, or the link needs fixing first. */
    QUEUED,

    /** Their mailbox refused it -- they have unlinked this account -- so it was closed. */
    REFUSED
}

/** What an answer from the other side did here, for the notification. */
enum class AnswerNews { ACCEPTED, DENIED, WITHDRAWN }

/** A shared chapter's share that a proposal counted, as this phone can describe it. */
data class ListedShare(
    /** This phone's name for the chapter, or null when its copy has not arrived yet. */
    val chapterName: String?,
    val amountPaise: Long
)

/** Everything the dialog for answering a proposal shows. See docs/declarations-design.md D13. */
data class ProposalPreview(
    val proposal: IouDeclaration,
    val friendName: String,
    /** The agreement an AMEND or REVOKE changes, when this phone holds it. */
    val target: IouDeclaration?,
    /** The shared chapters a DECLARE's amount counted. */
    val listedShares: List<ListedShare>,
    /** A DECLARE's or AMEND's amount less the listed shares: what is directly between the two. */
    val directPaise: Long?,
    /** This phone's own whole balance with them, right now. */
    val myBalancePaise: Long,
    /** What that becomes if the proposal is accepted. */
    val afterAcceptingPaise: Long,
    /** Their rows on this phone still awaiting review that the checkpoint would cover (D8). */
    val pendingCovered: Int,
    /** Why it cannot be accepted from here, or null when it can. */
    val cannotAccept: String?
)

/** Where agreeing on a balance stands with one friend, for their page. */
data class FriendAgreement(
    /** The checkpoint in force, if they ever agreed one. It may be archived. */
    val checkpoint: IouDeclaration?,
    /** This phone's own proposal still waiting -- to be sent, or to be answered. */
    val outgoing: IouDeclaration?,
    /** Their proposals waiting for an answer here, oldest first. */
    val incoming: List<IouDeclaration>,
    /** Whether anything can be proposed or answered right now: the two are linked. */
    val linked: Boolean
)

/**
 * Agreeing with a linked friend on what the two of you owe each other.
 *
 * A proposal travels through the mailbox, signed and sealed like any message, and changes nothing on
 * either phone until its recipient accepts. Acceptance is sent before it is applied, so a failed send
 * changes nothing; the proposer applies it when the answer arrives. The rules are in
 * docs/declarations-design.md; the state machine and the arithmetic are in `domain/declaration`,
 * where they are under test. This class is the IO around them.
 */
class DeclarationRepository(context: Context, private val db: AppDatabase) {

    private companion object {
        const val TAG = "DeclarationRepository"
    }

    private val services = MailboxServices.get(context)
    private val identities = MailboxIdentityRepository(context, db)
    private val posting = LedgerPostingService()

    // --- proposing ----------------------------------------------------------------------------------

    /**
     * D1: a new checkpoint as of now. [amountPaise] is the whole balance to agree on, positive when
     * they owe you; null proposes exactly what this phone's book says.
     */
    suspend fun propose(friendId: Long, amountPaise: Long? = null, note: String? = null): SendResult =
        withContext(Dispatchers.IO) {
            val link = requireLinked(friendId)
            requireNothingOpen(friendId)
            val row = db.withTransaction {
                insertDeclare(friendId, amountPaise, DeclarationMessages.cleanNote(note), auto = false)
            }
            send(row, link)
        }

    /**
     * D10: folds [deltaPaise] into the checkpoint in force, at its own instant -- how a row recorded
     * late, and dated before the checkpoint, gets counted after all.
     */
    suspend fun proposeAmend(friendId: Long, deltaPaise: Long, note: String?): SendResult =
        withContext(Dispatchers.IO) {
            if (deltaPaise == 0L) throw DeclarationException("That would not change the agreed balance.")
            val link = requireLinked(friendId)
            requireNothingOpen(friendId)
            val target = requireChangeable(friendId)
            val now = System.currentTimeMillis()
            val row = IouDeclaration(
                id = MailboxIds.newRandomId(),
                friendId = friendId,
                kind = DeclarationKind.AMEND,
                targetId = target.id,
                asOfEpoch = target.asOfEpoch,
                amountPaise = requireNotNull(target.amountPaise) + deltaPaise,
                deltaPaise = deltaPaise,
                note = DeclarationMessages.cleanNote(note),
                proposedByMe = true,
                proposedEpoch = now,
                state = DeclarationState.OPEN
            )
            db.declarationDao().insertIfNew(row)
            send(row, link)
        }

    /** D1: takes the checkpoint in force away, so the one before it, if any, counts again. */
    suspend fun proposeRevoke(friendId: Long, note: String? = null): SendResult = withContext(Dispatchers.IO) {
        val link = requireLinked(friendId)
        requireNothingOpen(friendId)
        val target = requireChangeable(friendId)
        val row = IouDeclaration(
            id = MailboxIds.newRandomId(),
            friendId = friendId,
            kind = DeclarationKind.REVOKE,
            targetId = target.id,
            note = DeclarationMessages.cleanNote(note),
            proposedByMe = true,
            proposedEpoch = System.currentTimeMillis(),
            state = DeclarationState.OPEN
        )
        db.declarationDao().insertIfNew(row)
        send(row, link)
    }

    /**
     * D11: the proposal a link makes by itself, on the phone that completed it. Written to the outbox
     * only -- this runs while the link is being made, and [flushOutbox] sends it straight after.
     */
    internal suspend fun queueAutomatic(friendId: Long) = withContext(Dispatchers.IO) {
        if (hasOpenOutgoing(friendId)) return@withContext
        db.withTransaction { insertDeclare(friendId, amountPaise = null, note = null, auto = true) }
    }

    /** Sends every proposal still waiting to. Returns how many went. Never throws. */
    suspend fun flushOutbox(): Int = withContext(Dispatchers.IO) {
        var sent = 0
        db.declarationDao().unsent().forEach { row ->
            val link = db.mailboxDao().getLink(row.friendId)?.takeIf { it.state == FriendLinkState.LINKED }
                ?: return@forEach
            if (send(row, link) == SendResult.SENT) sent++
        }
        sent
    }

    // --- answering ----------------------------------------------------------------------------------

    /** D2: says yes, then applies it. If saying so fails, nothing here changes. */
    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        val row = requireAnswerable(id)
        val link = requireLinked(row.friendId)
        cannotAccept(row)?.let { throw DeclarationException(it) }
        deliver(link, MailboxKind.DECLARATION_ANSWER, DeclarationMessages.encodeAnswer(DeclarationAnswer(id, DeclarationVerdict.ACCEPT)))
        db.withTransaction {
            val current = db.declarationDao().get(id) ?: return@withTransaction
            val next = DeclarationFlow.next(current.state, DeclarationEvent.ACCEPTED) ?: return@withTransaction
            val accepted = current.copy(state = next, decidedEpoch = System.currentTimeMillis())
            db.declarationDao().update(accepted)
            completeAcceptance(accepted)
        }
    }

    suspend fun deny(id: String, note: String? = null) = withContext(Dispatchers.IO) {
        val row = requireAnswerable(id)
        val link = requireLinked(row.friendId)
        val clean = DeclarationMessages.cleanNote(note)
        deliver(link, MailboxKind.DECLARATION_ANSWER, DeclarationMessages.encodeAnswer(DeclarationAnswer(id, DeclarationVerdict.DENY, clean)))
        db.withTransaction {
            val current = db.declarationDao().get(id) ?: return@withTransaction
            val next = DeclarationFlow.next(current.state, DeclarationEvent.DENIED) ?: return@withTransaction
            db.declarationDao().update(current.copy(state = next, decidedEpoch = System.currentTimeMillis(), replyNote = clean))
        }
    }

    /**
     * Takes back one of this phone's own proposals. One never sent is simply forgotten. One already
     * accepted cannot be taken back: the other side's book has it (D3).
     */
    suspend fun withdraw(id: String) = withContext(Dispatchers.IO) {
        val row = db.declarationDao().get(id)?.takeIf { it.proposedByMe }
            ?: throw DeclarationException("That proposal is gone.")
        if (row.state != DeclarationState.OPEN) throw DeclarationException(alreadyDecided(row))
        if (row.sentEpoch == null) {
            db.declarationDao().delete(id)
            return@withContext
        }
        val link = requireLinked(row.friendId)
        deliver(link, MailboxKind.DECLARATION_ANSWER, DeclarationMessages.encodeAnswer(DeclarationAnswer(id, DeclarationVerdict.WITHDRAW)))
        db.withTransaction {
            val current = db.declarationDao().get(id) ?: return@withTransaction
            val next = DeclarationFlow.next(current.state, DeclarationEvent.WITHDRAWN)
                ?: throw DeclarationException(alreadyDecided(current))
            db.declarationDao().update(current.copy(state = next, decidedEpoch = System.currentTimeMillis()))
        }
    }

    // --- collecting (called by MailboxSync, with the sender already verified) -----------------------------

    /** Records a proposal [friendId] sent. True when it is new here and waiting for an answer. */
    internal suspend fun receiveProposal(friendId: Long, proposal: DeclarationProposal): Boolean =
        db.withTransaction { storeProposal(friendId, proposal) }

    /** Applies an answer [friendId] sent. Null when it changed nothing. */
    internal suspend fun receiveAnswer(friendId: Long, answer: DeclarationAnswer): AnswerNews? =
        db.withTransaction { applyAnswer(friendId, answer) }

    // --- unlinking ----------------------------------------------------------------------------------

    /** D12: open proposals close, and agreements are kept but can no longer change. No balance moves. */
    suspend fun archive(friendId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.declarationDao().closeOpen(friendId, System.currentTimeMillis())
            db.declarationDao().archiveAccepted(friendId)
        }
    }

    // --- reading ------------------------------------------------------------------------------------

    suspend fun agreement(friendId: Long): FriendAgreement = withContext(Dispatchers.IO) {
        val rows = db.declarationDao().forFriend(friendId)
        FriendAgreement(
            checkpoint = DeclarationSet.effective(rows),
            outgoing = rows.firstOrNull { it.proposedByMe && it.state == DeclarationState.OPEN },
            incoming = rows
                .filter { !it.proposedByMe && it.kind != null && it.state == DeclarationState.OPEN }
                .sortedBy { it.proposedEpoch },
            linked = db.mailboxDao().getLink(friendId)?.state == FriendLinkState.LINKED
        )
    }

    /** Every proposal waiting for an answer on this phone, oldest first, for the inbox. */
    suspend fun openIncoming(): List<IouDeclaration> = withContext(Dispatchers.IO) {
        db.declarationDao().openIncoming()
    }

    /** Each friend's checkpoint, for every friend who has one: what a row's date is measured against. */
    suspend fun allCheckpoints(): Map<Long, IouDeclaration> = withContext(Dispatchers.IO) {
        CheckpointStore(db).effective(db.declarationDao().friendIdsWithAccepted())
    }

    /** Rows naming [friendId] still awaiting review and dated up to now, which an agreement made now covers. */
    suspend fun pendingUntilNow(friendId: Long): Int = withContext(Dispatchers.IO) {
        db.transactionDao().countPendingForFriendUntil(friendId, System.currentTimeMillis())
    }

    /** The figures to show before answering [id]. */
    suspend fun preview(id: String): ProposalPreview = withContext(Dispatchers.IO) {
        val proposal = db.declarationDao().get(id)?.takeIf { !it.proposedByMe && it.kind != null }
            ?: throw DeclarationException("That proposal is gone.")
        val friendId = proposal.friendId
        val target = proposal.targetId?.let { db.declarationDao().get(it) }?.takeIf { it.friendId == friendId }
        val parts = db.declarationDao().partsFor(id)
        val listed = parts.filter { it.shareId != null }.map { part ->
            ListedShare(part.chapterId?.let { db.chapterDao().getById(it)?.name }, part.amountPaise)
        }
        val myBalance = LedgerRepository(db).getSummaryForFriend(friendId)?.netBalancePaise ?: 0L

        val cannot = cannotAccept(proposal)
        val after = if (cannot != null) {
            myBalance
        } else {
            val rows = db.declarationDao().forFriend(friendId)
                .map { if (it.id == id) it.copy(state = DeclarationState.ACCEPTED) else it }
            val acceptedParts = partsOnAcceptance(proposal)
            balanceWith(friendId, rows) { declarationId ->
                if (declarationId == id) acceptedParts else db.declarationDao().partsFor(declarationId)
            }
        }

        ProposalPreview(
            proposal = proposal,
            friendName = friendName(friendId),
            target = target,
            listedShares = listed,
            directPaise = proposal.amountPaise?.let { amount -> amount - listed.sumOf { it.amountPaise } },
            myBalancePaise = myBalance,
            afterAcceptingPaise = after,
            pendingCovered = proposal.asOfEpoch
                ?.let { db.transactionDao().countPendingForFriendUntil(friendId, it) }
                ?: 0,
            cannotAccept = cannot
        )
    }

    /**
     * What [transactionId] does to the balance with [friendId], by the posting rules: positive means
     * they end up owing you. The amount to ask to add when it is dated before the checkpoint (D10).
     */
    suspend fun effectOf(transactionId: Long, friendId: Long): Long = withContext(Dispatchers.IO) {
        val tx = db.transactionDao().getTransactionById(transactionId) ?: return@withContext 0L
        val shares = db.transactionShareDao().getSharesForTransaction(transactionId)
        val recorder = DeltaRecorder()
        posting.postLedger(
            ScopedLedgerPort(recorder, setOf(friendId)), tx.id, tx.payerActorRef(), tx.payeeActorRef(),
            shares, tx.amountPaise, tx.ledgerEffect, IouLegs.resolve(tx, shares)
        )
        recorder.of(friendId)
    }

    // --- the in-transaction half ---------------------------------------------------------------------

    /**
     * A DECLARE of this phone's whole balance with [friendId] as of now -- or of [amountPaise] when
     * the user chose a figure -- with the chapter shares that balance counted as its parts (D6).
     */
    private suspend fun insertDeclare(friendId: Long, amountPaise: Long?, note: String?, auto: Boolean): IouDeclaration {
        val summary = LedgerRepository(db).getSummaryForFriend(friendId)
            ?: throw DeclarationException("That person is no longer in your list.")
        val now = System.currentTimeMillis()
        val row = IouDeclaration(
            id = MailboxIds.newRandomId(),
            friendId = friendId,
            kind = DeclarationKind.DECLARE,
            asOfEpoch = now,
            amountPaise = amountPaise ?: summary.netBalancePaise,
            note = note,
            proposedByMe = true,
            proposedEpoch = now,
            state = DeclarationState.OPEN,
            auto = auto
        )
        db.declarationDao().insertIfNew(row)
        val parts = DeclarationParts.forProposal(row.id, chapterSharesWith(friendId))
        if (parts.isNotEmpty()) db.declarationDao().insertParts(parts)
        return row
    }

    /** Every chapter's share of the balance with [friendId] on this phone, with the id each goes by if shared. */
    private suspend fun chapterSharesWith(friendId: Long): List<ChapterShareOf> =
        db.chapterDao().balancesForFriend(friendId).map { ChapterShareOf(it.chapterId, shareIdOf(it.chapterId), it.amountPaise) }

    private suspend fun storeProposal(friendId: Long, proposal: DeclarationProposal): Boolean {
        val existing = db.declarationDao().get(proposal.id)
        // An id already used for someone else's declaration is not this friend's to reuse.
        if (existing != null && existing.friendId != friendId) return false
        val row = DeclarationReceipt.rowForProposal(existing, friendId, proposal) ?: return false
        row.targetId?.let { targetId ->
            val target = db.declarationDao().get(targetId)
            if (target != null && target.friendId != friendId) return false
        }
        db.declarationDao().insertIfNew(row)
        proposal.parts.forEach { part ->
            db.declarationDao().insertPart(DeclarationParts.listed(row.id, part, chapterIdForShare(part.shareId)))
        }
        return true
    }

    private suspend fun applyAnswer(friendId: Long, answer: DeclarationAnswer): AnswerNews? {
        val existing = db.declarationDao().get(answer.proposalId)
        if (existing != null && existing.friendId != friendId) return null
        val updated = DeclarationReceipt.applyAnswer(existing, friendId, answer, System.currentTimeMillis())
            ?: return null
        if (existing == null) db.declarationDao().insertIfNew(updated) else db.declarationDao().update(updated)
        return when (updated.state) {
            DeclarationState.ACCEPTED -> {
                completeAcceptance(updated)
                AnswerNews.ACCEPTED
            }
            DeclarationState.DENIED -> AnswerNews.DENIED
            DeclarationState.WITHDRAWN -> AnswerNews.WITHDRAWN.takeIf { existing != null }
            else -> null
        }
    }

    /**
     * What accepting adds on this phone, then the replay that makes it count. Both phones run this:
     * the acceptor when it accepts, the proposer when the answer arrives.
     */
    private suspend fun completeAcceptance(accepted: IouDeclaration) {
        val parts = partsOnAcceptance(accepted)
        val have = db.declarationDao().partsFor(accepted.id)
        parts.filter { part -> have.none { it.chapterId == part.chapterId && it.shareId == part.shareId } }
            .forEach { db.declarationDao().insertPart(it.copy(id = 0L)) }
        ChapterRepository(db).replayInTransaction(setOf(accepted.friendId))
        // Every chapter shared with them carries a hint from this agreement (S8); they are now behind.
        db.chapterDao().bumpSharedChaptersWith(accepted.friendId)
    }

    /**
     * Every part [declaration] holds once accepted on this phone (D6): whatever it already has, plus
     * -- for a DECLARE this phone received -- its own private chapters' shares as they stand now, or
     * -- for an AMEND -- the parts of the agreement it replaces, since only the amount changes.
     */
    private suspend fun partsOnAcceptance(declaration: IouDeclaration): List<DeclarationPart> {
        val have = db.declarationDao().partsFor(declaration.id)
        val added = DeclarationParts.onAcceptance(
            declaration = declaration,
            have = have,
            ownShares = chapterSharesWith(declaration.friendId),
            targetParts = declaration.targetId?.let { db.declarationDao().partsFor(it) }.orEmpty()
        )
        return have + added
    }

    /**
     * The whole balance with [friendId] if [rows] were the declarations -- the same sum the ledger
     * keeps (D7), worked out without touching it.
     */
    private suspend fun balanceWith(
        friendId: Long,
        rows: List<IouDeclaration>,
        partsOf: suspend (String) -> List<DeclarationPart>
    ): Long {
        val effective = DeclarationSet.effective(rows)
        val opening = effective?.let { Checkpoints.openingAmount(it, partsOf(it.id)) } ?: 0L
        val recorder = DeltaRecorder()
        val port = ScopedLedgerPort(recorder, setOf(friendId))
        RoomReplaySource(db).rowsFor(setOf(friendId), effective?.asOfEpoch).forEach { (tx, shares) ->
            posting.postLedger(
                port, tx.id, tx.payerActorRef(), tx.payeeActorRef(), shares,
                tx.amountPaise, tx.ledgerEffect, IouLegs.resolve(tx, shares)
            )
        }
        val chapters = db.chapterDao().balancesForFriend(friendId).sumOf { it.amountPaise }
        return opening + recorder.of(friendId) + chapters
    }

    // --- sending ------------------------------------------------------------------------------------

    private suspend fun send(row: IouDeclaration, link: FriendLink): SendResult {
        val shared = db.declarationDao().partsFor(row.id)
            .mapNotNull { part -> part.shareId?.let { ProposalPart(it, part.amountPaise) } }
        val body = DeclarationMessages.encodeProposal(DeclarationMessages.outgoing(row, shared))
        val identity = identities.identity() ?: return SendResult.QUEUED
        return try {
            when (services.deliver(db, identity, link, MailboxKind.DECLARATION_PROPOSAL, body)) {
                Delivery.SENT -> {
                    db.declarationDao().markSent(row.id, System.currentTimeMillis())
                    SendResult.SENT
                }
                Delivery.REFUSED -> {
                    // They have unlinked this account; nothing will ever deliver it.
                    db.declarationDao().closeOpen(row.friendId, System.currentTimeMillis())
                    SendResult.REFUSED
                }
                // Kept: it goes once they turn the mailbox back on, or the two link again.
                Delivery.MAILBOX_OFF, Delivery.KEY_CHANGED -> SendResult.QUEUED
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailboxException) {
            Log.w(TAG, "A proposal stays in the outbox", error)
            SendResult.QUEUED
        }
    }

    /** An answer, sent before anything here changes (D2). Throws with what to tell the user when it cannot go. */
    private suspend fun deliver(link: FriendLink, kind: MailboxKind, body: String) {
        val identity = identities.identity()
            ?: throw DeclarationException("Turn the friends mailbox on first, in Settings.")
        val name = friendName(link.friendId)
        when (services.deliver(db, identity, link, kind, body)) {
            Delivery.SENT -> Unit
            Delivery.MAILBOX_OFF -> throw DeclarationException("$name has turned their friends mailbox off.")
            Delivery.KEY_CHANGED -> throw DeclarationException("$name's security key has changed. Link with them again first.")
            Delivery.REFUSED -> throw DeclarationException("$name is not accepting messages from you any more. Link with them again.")
        }
    }

    // --- checks -------------------------------------------------------------------------------------

    private suspend fun requireLinked(friendId: Long): FriendLink {
        val link = db.mailboxDao().getLink(friendId)
        val name = friendName(friendId)
        return when {
            link == null -> throw DeclarationException("Link with $name first. An agreed balance needs both of you.")
            link.state == FriendLinkState.AWAITING_CONFIRMATION ->
                throw DeclarationException("$name has not confirmed the link yet.")
            link.state == FriendLinkState.KEY_CHANGED ->
                throw DeclarationException("$name's security key has changed. Link with them again first.")
            else -> link
        }
    }

    private suspend fun requireNothingOpen(friendId: Long) {
        if (hasOpenOutgoing(friendId)) {
            throw DeclarationException("You are already waiting for ${friendName(friendId)} to answer. Withdraw that first.")
        }
    }

    private suspend fun hasOpenOutgoing(friendId: Long): Boolean =
        db.declarationDao().forFriend(friendId).any { it.proposedByMe && it.state == DeclarationState.OPEN }

    /** The checkpoint an AMEND or REVOKE would change: the one in force, and only while linked. */
    private suspend fun requireChangeable(friendId: Long): IouDeclaration {
        val effective = CheckpointStore(db).effective(friendId)
            ?: throw DeclarationException("You have no agreed balance with ${friendName(friendId)} to change.")
        if (effective.archived) {
            throw DeclarationException("That was agreed while you were linked before, so it cannot change now. Agree on a new balance instead.")
        }
        return effective
    }

    private suspend fun requireAnswerable(id: String): IouDeclaration {
        val row = db.declarationDao().get(id)?.takeIf { !it.proposedByMe && it.kind != null }
            ?: throw DeclarationException("That proposal is gone.")
        if (!DeclarationFlow.canAnswer(row.state)) throw DeclarationException(alreadyDecided(row))
        return row
    }

    /** Why [proposal] cannot be accepted here: a change to an agreement this phone does not hold. */
    private suspend fun cannotAccept(proposal: IouDeclaration): String? {
        if (proposal.kind == DeclarationKind.DECLARE) return null
        val target = proposal.targetId?.let { db.declarationDao().get(it) }
        return when {
            target == null || target.friendId != proposal.friendId ->
                "This changes an agreement this phone does not have. Ask ${friendName(proposal.friendId)} to agree on a fresh balance instead."
            target.state != DeclarationState.ACCEPTED ->
                "This changes an agreement that is not in force here."
            else -> null
        }
    }

    private suspend fun alreadyDecided(row: IouDeclaration): String = when (row.state) {
        DeclarationState.ACCEPTED -> "${friendName(row.friendId)} has already accepted it."
        DeclarationState.DENIED -> "That was already turned down."
        DeclarationState.WITHDRAWN -> "That was withdrawn."
        DeclarationState.CLOSED -> "That closed when the link ended."
        else -> "That has already been answered."
    }

    private suspend fun friendName(friendId: Long): String =
        db.friendDao().getFriendById(friendId)?.name ?: "your friend"

    /**
     * The id [chapterId] goes by on every phone that holds it, or null for a chapter that has never
     * left this one. A chapter shared once keeps its id when sharing stops: frozen copies still count.
     */
    private suspend fun shareIdOf(chapterId: Long): String? = db.chapterDao().getById(chapterId)?.shareId

    /** This phone's copy of a shared chapter, if it has reached here. */
    private suspend fun chapterIdForShare(shareId: String): Long? = db.chapterDao().getByShareId(shareId)?.id
}
