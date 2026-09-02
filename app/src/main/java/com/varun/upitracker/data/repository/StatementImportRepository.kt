package com.varun.upitracker.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.statement.StatementRow
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** A statement row whose UPI ref id already matches a transaction we hold. */
data class ResolvedEntry(
    val row: StatementRow,
    val transaction: Transaction
)

/** An existing transaction offered as "this statement row may already be recorded as this". */
data class MatchCandidate(
    val transaction: Transaction,
    /** True when the row's raw name or VPA also matches this transaction's counterparty. */
    val aliasMatch: Boolean
)

/** A statement row we could not resolve by ref id, with whatever candidates we found. */
data class UnresolvedGroup(
    val row: StatementRow,
    val candidates: List<MatchCandidate>
)

data class ImportPlan(
    val accountId: String,
    val resolved: List<ResolvedEntry>,
    val unresolved: List<UnresolvedGroup>,
    /** Rows skipped because this account already holds their statement ref number. */
    val alreadyImported: Int,
    /** Rows dropped because they fall outside the chosen date range. */
    val outOfRange: Int
)

data class ImportResult(
    val enriched: Int,
    val created: Int,
    val skipped: Int
)

/**
 * Matches bank-statement rows against what the app already holds, then writes the difference.
 *
 * Two jobs, in order of preference:
 *  1. **Enrich** — a row whose UPI ref id we already have teaches us the counterparty's registered
 *     name and VPA, which is exactly what [AliasResolver] needs to auto-resolve future SMS.
 *  2. **Backfill** — a row nothing matches becomes a pending transaction, shaped identically to
 *     what [com.varun.upitracker.sms.receiver.SmsReceiver] produces so the existing review flow
 *     picks it up unchanged.
 */
class StatementImportRepository(private val db: AppDatabase) {

    companion object {
        private const val TAG = "StatementImport"
        const val SOURCE_BANK_STATEMENT = "BANK_STATEMENT"

        /** Bank value dates lag the actual payment, so match a day either side. */
        private val DATE_TOLERANCE_MILLIS = TimeUnit.DAYS.toMillis(1)
        private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)
    }

    /**
     * Read-only. Works out what an import *would* do; nothing is written until [commit].
     */
    suspend fun buildPlan(
        rows: List<StatementRow>,
        accountId: String,
        fromEpoch: Long,
        toEpoch: Long
    ): ImportPlan {
        val savingsAccountIds = db.accountDao().getByType(AccountType.SAVINGS).map { it.id }
        val inRange = rows.filter { it.dateEpoch in fromEpoch..toEpoch }

        val resolved = mutableListOf<ResolvedEntry>()
        val unresolved = mutableListOf<UnresolvedGroup>()
        var alreadyImported = 0

        for (row in inRange) {
            val refNo = row.statementRefNo
            if (refNo != null && isAlreadyImported(refNo, accountId)) {
                alreadyImported++
                continue
            }

            val byRefId = row.upiRefId?.let { db.transactionDao().findByRefId(it) }
            if (byRefId != null) {
                resolved += ResolvedEntry(row, byRefId)
                continue
            }

            unresolved += UnresolvedGroup(row, findCandidates(row, savingsAccountIds))
        }

        return ImportPlan(
            accountId = accountId,
            resolved = resolved,
            unresolved = unresolved,
            alreadyImported = alreadyImported,
            outOfRange = rows.size - inRange.size
        )
    }

    /**
     * A row is already ours if it produced either a transaction or a transfer. The transfer case
     * matters because a reviewer can reclassify a pending imported transaction into a transfer,
     * which moves the ref number to the other table.
     */
    private suspend fun isAlreadyImported(refNo: String, accountId: String): Boolean =
        db.transactionDao().findByStatementRefNo(refNo, accountId) != null ||
            db.accountTransferDao().findByStatementRefNo(refNo) != null

    private suspend fun findCandidates(
        row: StatementRow,
        savingsAccountIds: List<String>
    ): List<MatchCandidate> {
        val candidates = db.transactionDao().findMatchCandidates(
            amountPaise = row.amountPaise,
            fromEpoch = row.dateEpoch - DATE_TOLERANCE_MILLIS,
            // dateEpoch is start-of-day, so the window must span the tolerant day itself.
            toEpoch = row.dateEpoch + DATE_TOLERANCE_MILLIS + DAY_MILLIS - 1,
            savingsAccountIds = savingsAccountIds
        )
        return candidates
            .map { MatchCandidate(it, aliasMatches(row, it)) }
            // Alias matches are near-certainly the same payment; float them to the top.
            .sortedWith(compareByDescending<MatchCandidate> { it.aliasMatch }.thenBy { it.transaction.dateEpoch })
    }

    private suspend fun aliasMatches(row: StatementRow, transaction: Transaction): Boolean {
        val labels = listOfNotNull(transaction.payerRawLabel, transaction.payeeRawLabel)
            .map { it.trim().lowercase() }
        val rowKeys = listOfNotNull(row.rawName, row.upiId).map { it.trim().lowercase() }
        if (rowKeys.any { it in labels }) return true

        // The statement name/VPA may already be registered against the counterparty entity.
        val counterpartyFriendId = transaction.payerFriendId ?: transaction.payeeFriendId
        if (counterpartyFriendId != null) {
            val known = db.friendDao().getRawNamesForFriend(counterpartyFriendId).map { it.rawName.lowercase() } +
                db.friendDao().getUpiIdsForFriend(counterpartyFriendId).map { it.upiId.lowercase() }
            if (rowKeys.any { it in known }) return true
        }
        val counterpartyMerchantId = transaction.payerMerchantId ?: transaction.payeeMerchantId
        if (counterpartyMerchantId != null) {
            val known = db.merchantDao().getRawNamesForMerchant(counterpartyMerchantId).map { it.rawName.lowercase() } +
                db.merchantDao().getUpiIdsForMerchant(counterpartyMerchantId).map { it.upiId.lowercase() }
            if (rowKeys.any { it in known }) return true
        }
        return false
    }

    /**
     * Applies [plan]. [selections] maps an index into [ImportPlan.unresolved] to the id of the
     * existing transaction the user ticked; unticked groups become new pending transactions.
     *
     * All writes land in one database transaction, mirroring
     * [com.varun.upitracker.domain.transactionentry.persistence.TransactionPersistenceService].
     */
    suspend fun commit(plan: ImportPlan, selections: Map<Int, Long>): ImportResult {
        var enriched = 0
        var created = 0
        var skipped = 0

        db.runInTransaction {
            runBlocking {
                for (entry in plan.resolved) {
                    if (enrich(entry.row, entry.transaction, plan.accountId)) enriched++
                }

                plan.unresolved.forEachIndexed { index, group ->
                    val selectedId = selections[index]
                    if (selectedId != null) {
                        val existing = db.transactionDao().getTransactionById(selectedId)
                        if (existing != null && enrich(group.row, existing, plan.accountId)) {
                            enriched++
                        } else {
                            skipped++
                        }
                    } else if (insertPending(group.row, plan.accountId)) {
                        created++
                    } else {
                        skipped++
                    }
                }
            }
        }

        return ImportResult(enriched = enriched, created = created, skipped = skipped)
    }

    /**
     * Stamps a statement row onto an existing transaction and registers what the statement taught
     * us about the counterparty. Never overwrites something the user already set.
     */
    private suspend fun enrich(row: StatementRow, transaction: Transaction, accountId: String): Boolean {
        val meIsPayer = transaction.payerActorType == ActorType.ME
        val counterpartyLabel = if (meIsPayer) transaction.payeeRawLabel else transaction.payerRawLabel
        val statementLabel = row.rawName ?: row.upiId

        val updated = transaction.copy(
            payerRawLabel = if (!meIsPayer && counterpartyLabel.isNullOrBlank()) {
                statementLabel ?: transaction.payerRawLabel
            } else {
                transaction.payerRawLabel
            },
            payeeRawLabel = if (meIsPayer && counterpartyLabel.isNullOrBlank()) {
                statementLabel ?: transaction.payeeRawLabel
            } else {
                transaction.payeeRawLabel
            },
            upiRefId = transaction.upiRefId ?: row.upiRefId,
            statementRefNo = transaction.statementRefNo ?: row.statementRefNo,
            myAccountId = transaction.myAccountId ?: accountId
        )

        if (updated != transaction) {
            try {
                db.transactionDao().update(updated)
            } catch (error: SQLiteConstraintException) {
                // Another transaction already owns this upiRefId; keep the row as it was.
                Log.w(TAG, "Could not enrich transaction ${transaction.id}: ${error.message}")
                return false
            }
        }

        registerAliases(row, transaction)
        return true
    }

    /**
     * Teaches the alias tables the raw name and VPA this statement row carries, so a future SMS
     * naming either one resolves without asking. Both DAO inserts are `OnConflictStrategy.IGNORE`,
     * so a string another entity already owns is a silent no-op.
     */
    private suspend fun registerAliases(row: StatementRow, transaction: Transaction) {
        val friendId = transaction.payerFriendId ?: transaction.payeeFriendId
        if (friendId != null) {
            row.rawName?.let { db.friendDao().insertRawName(FriendRawName(friendId = friendId, rawName = it)) }
            row.upiId?.let { db.friendDao().insertUpiId(FriendUpiId(friendId = friendId, upiId = it)) }
            return
        }
        val merchantId = transaction.payerMerchantId ?: transaction.payeeMerchantId
        if (merchantId != null) {
            row.rawName?.let { db.merchantDao().insertRawName(MerchantRawName(merchantId = merchantId, rawName = it)) }
            row.upiId?.let { db.merchantDao().insertUpiId(MerchantUpiId(merchantId = merchantId, upiId = it)) }
        }
    }

    /**
     * Shaped exactly like [com.varun.upitracker.sms.receiver.SmsReceiver]'s insert: pending, no
     * shares, no IOU rows, no ledger posting. The existing review flow completes it.
     */
    private suspend fun insertPending(row: StatementRow, accountId: String): Boolean {
        val resolution = row.resolverKey
            ?.let { AliasResolver(db).resolve(it, row.direction) }
            ?: ResolvedAs.Unknown
        val friendId = (resolution as? ResolvedAs.AsFriend)?.friendId
        val merchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId
        val counterpartyType = when (resolution) {
            is ResolvedAs.AsFriend -> ActorType.FRIEND
            is ResolvedAs.AsMerchant -> ActorType.MERCHANT
            is ResolvedAs.Unknown -> ActorType.UNKNOWN
        }
        val isDebit = row.direction == "DEBIT"

        val transaction = Transaction(
            amountPaise = row.amountPaise,
            payerActorType = if (isDebit) ActorType.ME else counterpartyType,
            payerFriendId = if (isDebit) null else friendId,
            payerMerchantId = if (isDebit) null else merchantId,
            payerRawLabel = if (isDebit) null else row.displayLabel,
            payeeActorType = if (isDebit) counterpartyType else ActorType.ME,
            payeeFriendId = if (isDebit) friendId else null,
            payeeMerchantId = if (isDebit) merchantId else null,
            payeeRawLabel = if (isDebit) row.displayLabel else null,
            reason = row.notes ?: row.narration,
            upiRefId = row.upiRefId,
            statementRefNo = row.statementRefNo,
            myAccountId = accountId,
            dateEpoch = row.dateEpoch,
            source = SOURCE_BANK_STATEMENT,
            isPending = true
        )

        return try {
            db.transactionDao().insert(transaction)
            true
        } catch (error: SQLiteConstraintException) {
            // The unique upiRefId index caught a duplicate we did not see when planning.
            Log.w(TAG, "Skipped duplicate statement row ${row.upiRefId}: ${error.message}")
            false
        }
    }
}
