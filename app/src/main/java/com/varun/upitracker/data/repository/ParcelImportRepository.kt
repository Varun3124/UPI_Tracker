package com.varun.upitracker.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.parcel.LocalParcelRow
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.domain.parcel.ParcelTransaction
import com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService
import com.varun.upitracker.domain.transactionentry.validation.PendingReviewRules
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

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
     * People named in this row -- at either end or in the split -- who are neither you nor the
     * sender. Unidentified by default, and listed so the review screen can offer to link them,
     * never so it can do it by itself.
     */
    val unmappedPeople: List<String>,
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

data class ParcelImportPlan(
    val senderFriendId: Long,
    val senderName: String,
    val originToken: String,
    val entries: List<ParcelEntry>,
    /** Rows skipped because this parcel, or one carrying the same rows, was already applied. */
    val alreadyImported: Int
) {
    val unmappedPeople: List<String>
        get() = entries.flatMap { it.preview.unmappedPeople }.distinct().sorted()
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
        val accountIds = db.accountDao().getAllSync().map { it.id }

        var alreadyImported = 0
        val entries = mutableListOf<ParcelEntry>()

        parcel.transactions.forEach { row ->
            val sharedRefId = ParcelPerspective.sharedRefIdFor(parcel.originToken, row.sourceId)
            if (db.transactionDao().findBySharedRefId(sharedRefId) != null) {
                alreadyImported++
                return@forEach
            }

            val certainDuplicateId = row.upiRefId?.let { db.transactionDao().findByRefId(it)?.id }
            val candidates = if (certainDuplicateId != null) {
                emptyList()
            } else {
                findCandidates(row, senderFriendId, accountIds)
            }

            entries.add(
                ParcelEntry(
                    row = row,
                    preview = previewOf(row, sender.name, senderFriendId),
                    candidates = candidates,
                    certainDuplicateId = certainDuplicateId
                )
            )
        }

        return ParcelImportPlan(
            senderFriendId = senderFriendId,
            senderName = sender.name,
            originToken = parcel.originToken,
            entries = entries,
            alreadyImported = alreadyImported
        )
    }

    private suspend fun findCandidates(
        row: ParcelTransaction,
        senderFriendId: Long,
        accountIds: List<String>
    ): List<ParcelMatchCandidate> =
        db.transactionDao().findMatchCandidates(
            amountPaise = row.amountPaise,
            fromEpoch = row.dateEpoch - DATE_TOLERANCE_MILLIS,
            toEpoch = row.dateEpoch + DATE_TOLERANCE_MILLIS,
            savingsAccountIds = accountIds
        )
            .filter { it.sharedRefId == null }
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
     * Built from the unmapped form of the row, so what the screen previews is what committing
     * without touching anything would actually write.
     */
    private suspend fun previewOf(row: ParcelTransaction, senderName: String, senderFriendId: Long): ParcelRowPreview {
        val local = ParcelPerspective.toLocal(
            row = row,
            senderFriendId = senderFriendId,
            originToken = "preview",
            mapPerson = { null },
            resolveShop = { null },
            carryUpiRefId = false
        )
        return ParcelRowPreview(
            payerLabel = labelOf(row.payer, senderName),
            payeeLabel = labelOf(row.payee, senderName),
            amountPaise = row.amountPaise,
            dateEpoch = row.dateEpoch,
            reason = row.reason,
            myBalanceDeltaPaise = balanceDeltaWithSender(local, senderFriendId),
            unmappedPeople = (listOf(row.payer, row.payee) + row.shares.map { it.participant })
                .filterIsInstance<ParcelActor.Person>()
                .map { it.name }
                .distinct(),
            needsReview = !PendingReviewRules.canAutoReview(local.transaction) ||
                !PendingReviewRules.sharesAreValid(local.transaction, local.shares)
        )
    }

    private fun labelOf(actor: ParcelActor, senderName: String): String = when (actor) {
        ParcelActor.Me -> "You"
        ParcelActor.Sender -> senderName
        is ParcelActor.Person -> actor.name
        is ParcelActor.Shop -> actor.name
        is ParcelActor.Unnamed -> actor.label
    }

    /**
     * What this row would do to your balance with the sender: positive means they end up owing
     * you, negative means you owe them.
     *
     * Measured by running the real posting service over the row rather than reasoning about payer
     * and payee here. The rules for which leg fires are subtle enough that a second copy of them
     * would drift, and a preview that disagrees with what the ledger then does is worse than none.
     */
    private suspend fun balanceDeltaWithSender(local: LocalParcelRow, senderFriendId: Long): Long {
        val recorder = DeltaRecorder(senderFriendId)
        ledgerPostingService.postLedger(
            ledger = recorder,
            transactionId = 0L,
            payer = local.transaction.payerActorRef(),
            payee = local.transaction.payeeActorRef(),
            shares = local.shares,
            amountPaise = local.transaction.amountPaise,
            ledgerEffect = local.transaction.ledgerEffect
        )
        return recorder.delta
    }

    /** Adds up what would be posted against one friend, without a database behind it. */
    private class DeltaRecorder(private val friendId: Long) : LedgerPort {
        var delta = 0L
            private set

        override suspend fun recordBalanceChange(transactionId: Long, friendId: Long, deltaPaise: Long) {
            if (friendId == this.friendId) delta += deltaPaise
        }

        /** They paid me, so what they owe me falls. */
        override suspend fun applyRepayment(transactionId: Long, friendId: Long, creditAmountPaise: Long) {
            if (friendId == this.friendId) delta -= creditAmountPaise
        }

        /** I paid them, so what they owe me rises. */
        override suspend fun applyOutgoingSettlement(transactionId: Long, friendId: Long, debitAmountPaise: Long) {
            if (friendId == this.friendId) delta += debitAmountPaise
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
        val local = ParcelPerspective.toLocal(
            row = entry.row,
            senderFriendId = plan.senderFriendId,
            originToken = plan.originToken,
            // Never map a name onto the sender: the parcel already says which rows are theirs, and
            // a same-named friend of theirs would otherwise double their balance.
            mapPerson = { name -> personMappings[name]?.takeIf { it != plan.senderFriendId } },
            resolveShop = { name -> merchantIds[name] },
            carryUpiRefId = true
        )
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
