package com.varun.upitracker.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.MailboxMessageState
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.parcel.LocalParcelRow
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.domain.parcel.ParcelTransaction
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** What a row does to one friend's balance with you: positive means they end up owing you. */
data class BalanceEffect(val friendName: String, val deltaPaise: Long)

/** Everything the review row needs, resolved once so the adapter touches no database. */
data class ParcelRowPreview(
    val payerLabel: String,
    val payeeLabel: String,
    val amountPaise: Long,
    val dateEpoch: Long,
    val reason: String?,
    /** What this row does to what you and the sender owe each other, already signed. */
    val myBalanceDeltaPaise: Long,
    /**
     * What it does to anyone else. Only ever someone named by an account you linked yourself,
     * because nobody else gets an id without you saying so.
     */
    val otherEffects: List<BalanceEffect>,
    /**
     * People named in this row -- at either end or in the split -- who are neither you nor the
     * sender, and whom nothing identifies. Listed so the review screen can offer to link them,
     * never so it can do it by itself.
     */
    val unmappedPeople: List<String>,
    /**
     * The side of the split this row recovers from, when that changes who owes whom; null when either
     * side would give the same answer.
     */
    val splitRecovery: IouRecovery?,
    /** True when [splitRecovery] is the sender's own choice rather than read off an older parcel. */
    val splitRecoveryFromSender: Boolean,
    /** True when the row will still need opening after import -- see [PendingReviewRules]. */
    val needsReview: Boolean
)

/** An existing transaction offered as "you may already have recorded this yourself". */
data class ParcelMatchCandidate(
    val transaction: Transaction,
    /** True when the sender is also the counterparty on this transaction. */
    val counterpartyMatch: Boolean
)

data class ParcelEntry(
    val row: ParcelTransaction,
    val preview: ParcelRowPreview,
    val candidates: List<ParcelMatchCandidate>,
    /**
     * Set when this row's UPI reference already names a transaction we hold, which for a direct
     * transfer is proof rather than a guess. Pre-ticked on the review screen.
     */
    val certainDuplicateId: Long?
)

/** Where a plan's rows came from, which decides the reference each one lands under. */
sealed interface ParcelOrigin {

    /** Pasted: the reader said who sent it, and rows are qualified by the sender's token. */
    data class Pasted(val originToken: String) : ParcelOrigin

    /** Collected from the mailbox: the verified sender says who, and rows carry their own references. */
    data class Mailbox(val messageId: String, val senderUid: String) : ParcelOrigin
}

data class ParcelImportPlan(
    val senderFriendId: Long,
    val senderName: String,
    val origin: ParcelOrigin,
    val entries: List<ParcelEntry>,
    /** Rows skipped because this parcel, or one carrying the same rows, was already applied. */
    val alreadyImported: Int,
    /**
     * Mailbox only: accounts named in these rows that you have linked, to your friend's id. Settled
     * once, so what gets saved is exactly what the review screen showed.
     */
    val linkedFriendIds: Map<String, Long> = emptyMap()
) {
    val unmappedPeople: List<String>
        get() = entries.flatMap { it.preview.unmappedPeople }.distinct().sorted()

    val isFromMailbox: Boolean get() = origin is ParcelOrigin.Mailbox
}

data class ParcelImportResult(
    val created: Int,
    val skipped: Int
)

/**
 * Turns a friend's parcel into pending transactions here.
 *
 * Deliberately the same shape as [StatementImportRepository]: plan first with nothing written, let
 * the user settle the duplicates, then commit in one database transaction. Nothing it writes posts
 * to the ledger -- every row lands pending, and the existing review flow decides what money moves,
 * which keeps a friend's parcel from being able to change your balances on its own.
 *
 * A parcel arrives one of two ways, and they differ only in who says who sent it. Pasted, the reader
 * picks the sender. Collected from the mailbox, the verified sender is the linked friend, and people
 * in the rows the reader has linked are identified by account rather than left as names.
 */
class ParcelImportRepository(private val db: AppDatabase) {

    private val ledgerPostingService = LedgerPostingService()

    companion object {
        private const val TAG = "ParcelImport"

        /**
         * The same tolerance the statement importer uses, and for a related reason: two phones
         * record the same payment from two different SMS, whose timestamps rarely agree to the
         * minute and can fall either side of midnight.
         */
        private val DATE_TOLERANCE_MILLIS = TimeUnit.DAYS.toMillis(1)
    }

    suspend fun buildPlan(parcel: Parcel, senderFriendId: Long): ParcelImportPlan {
        val sender = db.friendDao().getFriendById(senderFriendId)
            ?: throw IllegalArgumentException("That friend is no longer in your list.")
        val originToken = parcel.originToken
            ?: throw IllegalArgumentException("That parcel carries no origin token.")
        return plan(
            parcel = parcel,
            senderFriendId = senderFriendId,
            senderName = sender.name,
            origin = ParcelOrigin.Pasted(originToken),
            linkedFriendIds = emptyMap(),
            sameSenderPrefixes = listOf("$originToken."),
            referencesOf = { row -> listOf(ParcelPerspective.sharedRefIdFor(originToken, row.sourceId)) },
            landForPreview = { row ->
                ParcelPerspective.toLocal(
                    row = row,
                    senderFriendId = senderFriendId,
                    originToken = "preview",
                    mapPerson = { null },
                    resolveShop = { null },
                    carryUpiRefId = false
                )
            }
        )
    }

    /** The plan for a parcel collected from the mailbox and not yet dealt with. */
    suspend fun buildMailboxPlan(messageId: String): ParcelImportPlan {
        val message = db.mailboxDao().getMessage(messageId)
            ?.takeIf { it.kind == MailboxKind.PARCEL.name && it.state == MailboxMessageState.NEW }
            ?: throw IllegalArgumentException("That parcel has already been dealt with.")
        val senderFriendId = message.friendId
            ?: throw IllegalArgumentException("Whoever sent this is no longer in your people list.")
        val sender = db.friendDao().getFriendById(senderFriendId)
            ?: throw IllegalArgumentException("Whoever sent this is no longer in your people list.")
        val parcel = (message.body?.let { ParcelFormat.parse(it, ParcelFormat.MAILBOX_VERSION) } as? ParcelDecodeResult.Ok)
            ?.parcel
            ?: throw IllegalArgumentException("That parcel could not be read.")
        val senderUid = message.senderUid

        // The accounts named in these rows that you linked yourself. Nobody else gets an id here.
        val linkedFriendIds = mutableMapOf<String, Long>()
        parcel.transactions
            .flatMap { tx -> listOf(tx.payer, tx.payee) + tx.shares.map { it.participant } }
            .filterIsInstance<ParcelActor.Linked>()
            .map { it.uid }
            .distinct()
            .forEach { uid ->
                db.mailboxDao().getLinkByUid(uid)?.friendId
                    ?.takeIf { it != senderFriendId }
                    ?.let { linkedFriendIds[uid] = it }
            }

        // Rows this sender once pasted to you are theirs too: a different transaction, never a copy.
        val pasteTokens = parcel.transactions
            .mapNotNull { row -> row.legacyRef?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() } }
            .distinct()

        return plan(
            parcel = parcel,
            senderFriendId = senderFriendId,
            senderName = sender.name,
            origin = ParcelOrigin.Mailbox(messageId, senderUid),
            linkedFriendIds = linkedFriendIds,
            sameSenderPrefixes = listOf(ParcelPerspective.mailboxRefPrefixFor(senderUid)) + pasteTokens.map { "$it." },
            referencesOf = { row ->
                listOfNotNull(
                    ParcelPerspective.mailboxRefIdFor(senderUid, requireNotNull(row.shareRef)),
                    row.legacyRef
                )
            },
            landForPreview = { row ->
                ParcelPerspective.toLocalFromMailbox(
                    row = row,
                    senderFriendId = senderFriendId,
                    senderUid = senderUid,
                    mapPerson = { null },
                    resolveLinked = { linkedFriendIds[it] },
                    resolveShop = { null },
                    carryUpiRefId = false
                )
            }
        )
    }

    /** The user looked at a collected parcel and chose to save none of it. */
    suspend fun dismissMailboxParcel(messageId: String) {
        db.mailboxDao().setMessageState(messageId, MailboxMessageState.DISMISSED)
    }

    private suspend fun plan(
        parcel: Parcel,
        senderFriendId: Long,
        senderName: String,
        origin: ParcelOrigin,
        linkedFriendIds: Map<String, Long>,
        sameSenderPrefixes: List<String>,
        referencesOf: (ParcelTransaction) -> List<String>,
        landForPreview: (ParcelTransaction) -> LocalParcelRow
    ): ParcelImportPlan {
        val accountIds = db.accountDao().getAllSync().map { it.id }
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }

        var alreadyImported = 0
        val entries = mutableListOf<ParcelEntry>()

        parcel.transactions.forEach { row ->
            if (referencesOf(row).any { db.transactionDao().findBySharedRefId(it) != null }) {
                alreadyImported++
                return@forEach
            }

            val certainDuplicateId = row.upiRefId?.let { db.transactionDao().findByRefId(it)?.id }
            val candidates = if (certainDuplicateId != null) {
                emptyList()
            } else {
                findCandidates(row, senderFriendId, accountIds, sameSenderPrefixes)
            }

            entries.add(
                ParcelEntry(
                    row = row,
                    preview = previewOf(row, senderName, senderFriendId, landForPreview(row), friendNames, linkedFriendIds),
                    candidates = candidates,
                    certainDuplicateId = certainDuplicateId
                )
            )
        }

        return ParcelImportPlan(
            senderFriendId = senderFriendId,
            senderName = senderName,
            origin = origin,
            entries = entries,
            alreadyImported = alreadyImported,
            linkedFriendIds = linkedFriendIds
        )
    }

    /**
     * Rows already here that could be this one. Rows where you are only in the split count too, and
     * so do rows other friends sent: once anyone in a split can be sent it, the same dinner can
     * arrive from two of them, and without this the second copy would double the debt.
     *
     * Only this sender's own earlier rows are left out. Those carry references of their own, so a
     * match among them is a different transaction of theirs, never a copy of this one.
     */
    private suspend fun findCandidates(
        row: ParcelTransaction,
        senderFriendId: Long,
        accountIds: List<String>,
        sameSenderPrefixes: List<String>
    ): List<ParcelMatchCandidate> =
        db.transactionDao().findParcelMatchCandidates(
            amountPaise = row.amountPaise,
            fromEpoch = row.dateEpoch - DATE_TOLERANCE_MILLIS,
            toEpoch = row.dateEpoch + DATE_TOLERANCE_MILLIS,
            savingsAccountIds = accountIds
        )
            .filter { candidate ->
                sameSenderPrefixes.none { prefix -> candidate.sharedRefId?.startsWith(prefix) == true }
            }
            .map { transaction ->
                ParcelMatchCandidate(
                    transaction = transaction,
                    counterpartyMatch = transaction.payerFriendId == senderFriendId ||
                        transaction.payeeFriendId == senderFriendId
                )
            }
            .sortedWith(
                compareByDescending<ParcelMatchCandidate> { it.counterpartyMatch }
                    .thenBy { it.transaction.dateEpoch }
            )

    /**
     * Built from the row as it would land with nobody mapped by hand, so what the screen previews
     * is what committing without touching anything would actually write.
     */
    private suspend fun previewOf(
        row: ParcelTransaction,
        senderName: String,
        senderFriendId: Long,
        local: LocalParcelRow,
        friendNames: Map<Long, String>,
        linkedFriendIds: Map<String, Long>
    ): ParcelRowPreview {
        val deltas = balanceDeltas(local)
        val splitMatters = IouLegs.choiceMatters(
            local.transaction.payerActorRef(),
            local.transaction.payeeActorRef(),
            local.shares,
            local.transaction.ledgerEffect
        )
        return ParcelRowPreview(
            payerLabel = labelOf(row.payer, senderName, friendNames, linkedFriendIds),
            payeeLabel = labelOf(row.payee, senderName, friendNames, linkedFriendIds),
            amountPaise = row.amountPaise,
            dateEpoch = row.dateEpoch,
            reason = row.reason,
            myBalanceDeltaPaise = deltas[senderFriendId] ?: 0L,
            otherEffects = deltas
                .filter { (friendId, delta) -> friendId != senderFriendId && delta != 0L }
                .map { (friendId, delta) -> BalanceEffect(friendNames[friendId] ?: "Someone", delta) },
            unmappedPeople = (listOf(row.payer, row.payee) + row.shares.map { it.participant })
                .mapNotNull { actor ->
                    when (actor) {
                        is ParcelActor.Person -> actor.name
                        is ParcelActor.Linked -> actor.name.takeIf { actor.uid !in linkedFriendIds }
                        else -> null
                    }
                }
                .distinct(),
            splitRecovery = local.transaction.iouRecovery?.takeIf { splitMatters },
            splitRecoveryFromSender = row.iouRecovery != null,
            needsReview = !PendingReviewRules.canAutoReview(local.transaction) ||
                !PendingReviewRules.sharesAreValid(local.transaction, local.shares)
        )
    }

    private fun labelOf(
        actor: ParcelActor,
        senderName: String,
        friendNames: Map<Long, String>,
        linkedFriendIds: Map<String, Long>
    ): String = when (actor) {
        ParcelActor.Me -> "You"
        ParcelActor.Sender -> senderName
        is ParcelActor.Person -> actor.name
        // Your own name for them: you linked them yourself.
        is ParcelActor.Linked -> linkedFriendIds[actor.uid]?.let { friendNames[it] } ?: actor.name
        is ParcelActor.Shop -> actor.name
        is ParcelActor.Unnamed -> actor.label
    }

    /**
     * What this row would do to each friend's balance: positive means they end up owing you,
     * negative means you owe them.
     *
     * Measured by running the real posting service over the row rather than reasoning about payer
     * and payee here. The rules for which leg fires are subtle enough that a second copy of them
     * would drift, and a preview that disagrees with what the ledger then does is worse than none.
     */
    private suspend fun balanceDeltas(local: LocalParcelRow): Map<Long, Long> {
        val recorder = DeltaRecorder()
        ledgerPostingService.postLedger(
            ledger = recorder,
            transactionId = 0L,
            payer = local.transaction.payerActorRef(),
            payee = local.transaction.payeeActorRef(),
            shares = local.shares,
            amountPaise = local.transaction.amountPaise,
            ledgerEffect = local.transaction.ledgerEffect,
            iouRecovery = IouLegs.resolve(local.transaction, local.shares)
        )
        return recorder.deltas
    }

    /** Adds up what would be posted against each friend, without a database behind it. */
    private class DeltaRecorder : LedgerPort {
        val deltas = linkedMapOf<Long, Long>()

        override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
            add(friendId, deltaPaise)
        }

        /** They paid me, so what they owe me falls. */
        override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
            add(friendId, -creditAmountPaise)
        }

        /** I paid them, so what they owe me rises. */
        override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
            add(friendId, debitAmountPaise)
        }

        /** Never reached from a single row's preview, but an opening moves a balance like anything else. */
        override suspend fun recordOpening(declarationId: String, friendId: Long, amountPaise: Long) {
            add(friendId, amountPaise)
        }

        private fun add(friendId: Long, deltaPaise: Long) {
            deltas[friendId] = (deltas[friendId] ?: 0L) + deltaPaise
        }
    }

    /**
     * Writes the rows the user did not tick as duplicates.
     *
     * [skipped] holds entry indices the user marked as already recorded here; those rows are
     * dropped rather than merged, because unlike a bank statement a parcel teaches this database
     * nothing about its own transaction that it does not already know.
     *
     * [personMappings] is the user's explicit say-so about who a name in a split refers to. Names
     * absent from it stay unidentified on purpose -- see
     * [com.varun.upitracker.database.entity.TransactionShare.rawLabel].
     *
     * A mailbox parcel is marked dealt with in the same database transaction, so it can neither be
     * saved twice nor linger as new once saved.
     */
    suspend fun commit(
        plan: ParcelImportPlan,
        skipped: Set<Int>,
        personMappings: Map<String, Long>
    ): ParcelImportResult {
        val merchantIds = resolveMerchantIds(plan)
        return db.runInTransaction<ParcelImportResult> {
            runBlocking {
                var created = 0
                plan.entries.forEachIndexed { index, entry ->
                    if (index in skipped || entry.certainDuplicateId != null) return@forEachIndexed
                    if (insert(entry, plan, personMappings, merchantIds)) created++
                }
                (plan.origin as? ParcelOrigin.Mailbox)?.let {
                    db.mailboxDao().setMessageState(it.messageId, MailboxMessageState.IMPORTED)
                }
                ParcelImportResult(
                    created = created,
                    skipped = plan.entries.size - created
                )
            }
        }
    }

    /** Looked up before the write transaction opens, so the commit stays a straight run of inserts. */
    private suspend fun resolveMerchantIds(plan: ParcelImportPlan): Map<String, Long> {
        val names = plan.entries.flatMap { listOf(it.row.payer, it.row.payee) }
            .filterIsInstance<ParcelActor.Shop>()
            .map { it.name }
            .distinct()
        return names.mapNotNull { name ->
            val merchant = db.merchantDao().findByNormalizedName(name)
                ?: db.merchantDao().findByRawName(name)?.let { db.merchantDao().getMerchantById(it.merchantId) }
            merchant?.let { name to it.id }
        }.toMap()
    }

    private suspend fun insert(
        entry: ParcelEntry,
        plan: ParcelImportPlan,
        personMappings: Map<String, Long>,
        merchantIds: Map<String, Long>
    ): Boolean {
        // Never map a name onto the sender: the parcel already says which rows are theirs, and a
        // same-named friend of theirs would otherwise double their balance.
        val mapPerson = { name: String -> personMappings[name]?.takeIf { it != plan.senderFriendId } }
        val local = when (val origin = plan.origin) {
            is ParcelOrigin.Pasted -> ParcelPerspective.toLocal(
                row = entry.row,
                senderFriendId = plan.senderFriendId,
                originToken = origin.originToken,
                mapPerson = mapPerson,
                resolveShop = { name -> merchantIds[name] },
                carryUpiRefId = true
            )
            is ParcelOrigin.Mailbox -> ParcelPerspective.toLocalFromMailbox(
                row = entry.row,
                senderFriendId = plan.senderFriendId,
                senderUid = origin.senderUid,
                mapPerson = mapPerson,
                resolveLinked = { uid -> plan.linkedFriendIds[uid]?.takeIf { it != plan.senderFriendId } },
                resolveShop = { name -> merchantIds[name] },
                carryUpiRefId = true
            )
        }
        return try {
            val transactionId = db.transactionDao().insert(local.transaction)
            local.shares.forEach { share ->
                db.transactionShareDao().insert(share.copy(transactionId = transactionId))
            }
            true
        } catch (error: SQLiteConstraintException) {
            // The unique sharedRefId or upiRefId index caught a duplicate the plan did not see --
            // a parcel applied twice from two devices, or an SMS that arrived while reviewing.
            Log.w(TAG, "Skipped a duplicate parcel row: ${error.message}")
            false
        }
    }
}
