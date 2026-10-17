package com.varun.upitracker.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendUpiId
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
import com.varun.upitracker.domain.parcel.ParcelSender
import com.varun.upitracker.domain.parcel.ParcelTransaction
import com.varun.upitracker.domain.parcel.SenderChoice
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.DeltaRecorder
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.util.initialsOf
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

    /** Pasted: the parcel suggests who sent it, and rows are qualified by the sender's token. */
    data class Pasted(val originToken: String) : ParcelOrigin

    /** Collected from the mailbox: the verified sender says who, and rows carry their own references. */
    data class Mailbox(val messageId: String, val senderUid: String) : ParcelOrigin
}

data class ParcelImportPlan(
    /** Who the rows land as coming from. Never [SenderChoice.Unknown] once saving starts. */
    val sender: SenderChoice,
    /** What this screen calls them: your own name for a friend, or the alias a new person will get. */
    val senderName: String,
    /** What the parcel calls its writer, when it says. Null from the mailbox and from older apps. */
    val senderAlias: String?,
    /** The writer's UPI ID, when the parcel gave one. */
    val senderUpiId: String?,
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

    /** The friend the rows land under, or null while that person is still to be added or named. */
    val senderFriendId: Long? get() = (sender as? SenderChoice.Existing)?.friendId

    val canSave: Boolean get() = sender !is SenderChoice.Unknown

    companion object {
        /**
         * The id the preview posts the sender's side against when they are not in the list yet. No
         * real friend can have it, and it only ever keys the preview's own deltas.
         */
        const val PREVIEW_SENDER_ID = -1L
    }
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
 * A parcel arrives one of two ways, and they differ only in who says who sent it. Pasted, the parcel
 * names its writer and [ParcelSender] suggests who that is here; the reader can say otherwise.
 * Collected from the mailbox, the verified sender is the linked friend, and people in the rows the
 * reader has linked are identified by account rather than left as names.
 *
 * Every row saved also keeps an untouchable copy of itself as it arrived -- see
 * [ReceivedOriginalRepository].
 */
class ParcelImportRepository(private val db: AppDatabase, context: Context) {

    private val ledgerPostingService = LedgerPostingService()
    private val prefs = AppPrefs.of(context)
    private val originals = ReceivedOriginalRepository(db)

    companion object {
        private const val TAG = "ParcelImport"

        /**
         * `parcel_sender_<originToken>` -> the friend that token's rows were last saved under. The
         * token is per writer and per reader, so this is the reader's own earlier answer to "who is
         * this", which outranks anything the parcel claims. Kept in preferences beside the writer's
         * `parcel_origin_` tokens, and backed up with them.
         */
        private const val SENDER_KEY_PREFIX = "parcel_sender_"

        /**
         * The same tolerance the statement importer uses, and for a related reason: two phones
         * record the same payment from two different SMS, whose timestamps rarely agree to the
         * minute and can fall either side of midnight.
         */
        private val DATE_TOLERANCE_MILLIS = TimeUnit.DAYS.toMillis(1)
    }

    /**
     * The plan for a pasted parcel. [override] is the reader's own answer to who sent it; without
     * one, the parcel's alias and UPI ID are matched against this person's friends.
     */
    suspend fun buildPlan(parcel: Parcel, override: SenderChoice? = null): ParcelImportPlan {
        val originToken = parcel.originToken
            ?: throw IllegalArgumentException("That parcel carries no origin token.")
        val sender = override ?: resolveSender(parcel)
        val senderName = when (sender) {
            is SenderChoice.Existing -> db.friendDao().getFriendById(sender.friendId)?.name
                ?: throw IllegalArgumentException("That friend is no longer in your list.")
            is SenderChoice.New -> sender.name
            SenderChoice.Unknown -> "Whoever sent this"
        }
        val senderFriendId = (sender as? SenderChoice.Existing)?.friendId ?: ParcelImportPlan.PREVIEW_SENDER_ID
        return plan(
            parcel = parcel,
            sender = sender,
            senderName = senderName,
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

    /** See [ParcelSender]. Read here once, so its lambdas need no database of their own. */
    private suspend fun resolveSender(parcel: Parcel): SenderChoice {
        val friends = db.friendDao().getAllFriendsSync()
        val friendIds = friends.map { it.id }.toSet()
        val upiOwner = parcel.senderUpiId?.let { db.friendDao().findByUpiIdIgnoreCase(it)?.friendId }
        return ParcelSender.resolve(
            parcel = parcel,
            rememberedFriendId = { token ->
                prefs.getLong(SENDER_KEY_PREFIX + token, -1L).takeIf { it in friendIds }
            },
            friendByUpiId = { upiOwner },
            // Only when exactly one friend has the name. Two Rahuls is a question, not an answer.
            friendByName = { name ->
                friends.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }.singleOrNull()?.id
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
            sender = SenderChoice.Existing(senderFriendId, SenderChoice.How.PICKED),
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
        sender: SenderChoice,
        senderName: String,
        origin: ParcelOrigin,
        linkedFriendIds: Map<String, Long>,
        sameSenderPrefixes: List<String>,
        referencesOf: (ParcelTransaction) -> List<String>,
        landForPreview: (ParcelTransaction) -> LocalParcelRow
    ): ParcelImportPlan {
        val accountIds = db.accountDao().getAllSync().map { it.id }
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val senderFriendId = (sender as? SenderChoice.Existing)?.friendId ?: ParcelImportPlan.PREVIEW_SENDER_ID

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
            sender = sender,
            senderName = senderName,
            senderAlias = parcel.senderAlias,
            senderUpiId = parcel.senderUpiId,
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
        require(plan.canSave) { "Say who sent this first." }
        val merchantIds = resolveMerchantIds(plan)
        var settledSenderId: Long? = null
        val result = db.runInTransaction<ParcelImportResult> {
            runBlocking {
                var created = 0
                plan.entries.forEachIndexed { index, entry ->
                    if (index in skipped || entry.certainDuplicateId != null) return@forEachIndexed
                    // Settled only once a row is about to go in, so a parcel that turns out to hold
                    // nothing new leaves no stranger behind in the people list.
                    val senderFriendId = settledSenderId ?: settleSender(plan).also { settledSenderId = it }
                    if (insert(entry, plan, senderFriendId, personMappings, merchantIds)) created++
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
        // After the transaction, never inside it: a save that rolled back must not be remembered.
        val pasted = plan.origin as? ParcelOrigin.Pasted
        val senderId = settledSenderId
        if (pasted != null && senderId != null && result.created > 0) {
            prefs.edit().putLong(SENDER_KEY_PREFIX + pasted.originToken, senderId).apply()
        }
        return result
    }

    /**
     * The friend the rows land under, adding them first when the parcel's writer is new here. A
     * pasted writer's UPI ID is kept against them too, unless someone already holds it: that is what
     * lets this person's own SMS of a payment to or from them resolve to the same friend from now on.
     */
    private suspend fun settleSender(plan: ParcelImportPlan): Long {
        val friendId = when (val sender = plan.sender) {
            is SenderChoice.Existing -> sender.friendId
            is SenderChoice.New -> db.friendDao().insertFriend(
                Friend(
                    name = sender.name,
                    avatarInitials = initialsOf(sender.name, fallback = "F"),
                    addedEpoch = System.currentTimeMillis()
                )
            )
            SenderChoice.Unknown -> throw IllegalArgumentException("Say who sent this first.")
        }
        val upiId = plan.senderUpiId
        if (upiId != null && plan.origin is ParcelOrigin.Pasted && db.friendDao().findByUpiIdIgnoreCase(upiId) == null) {
            db.friendDao().insertUpiId(FriendUpiId(friendId = friendId, upiId = upiId))
        }
        return friendId
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
        senderFriendId: Long,
        personMappings: Map<String, Long>,
        merchantIds: Map<String, Long>
    ): Boolean {
        // Never map a name onto the sender: the parcel already says which rows are theirs, and a
        // same-named friend of theirs would otherwise double their balance.
        val mapPerson = { name: String -> personMappings[name]?.takeIf { it != senderFriendId } }
        val local = when (val origin = plan.origin) {
            is ParcelOrigin.Pasted -> ParcelPerspective.toLocal(
                row = entry.row,
                senderFriendId = senderFriendId,
                originToken = origin.originToken,
                mapPerson = mapPerson,
                resolveShop = { name -> merchantIds[name] },
                carryUpiRefId = true
            )
            is ParcelOrigin.Mailbox -> ParcelPerspective.toLocalFromMailbox(
                row = entry.row,
                senderFriendId = senderFriendId,
                senderUid = origin.senderUid,
                mapPerson = mapPerson,
                resolveLinked = { uid -> plan.linkedFriendIds[uid]?.takeIf { it != senderFriendId } },
                resolveShop = { name -> merchantIds[name] },
                carryUpiRefId = true
            )
        }
        return try {
            val transactionId = db.transactionDao().insert(local.transaction)
            local.shares.forEach { share ->
                db.transactionShareDao().insert(share.copy(transactionId = transactionId))
            }
            originals.captureInTransaction(transactionId, local.transaction, local.shares, plan.senderName)
            true
        } catch (error: SQLiteConstraintException) {
            // The unique sharedRefId or upiRefId index caught a duplicate the plan did not see --
            // a parcel applied twice from two devices, or an SMS that arrived while reviewing.
            Log.w(TAG, "Skipped a duplicate parcel row: ${error.message}")
            false
        }
    }
}
