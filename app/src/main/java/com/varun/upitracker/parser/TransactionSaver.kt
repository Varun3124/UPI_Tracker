package com.varun.upitracker.parser

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.ImportedTransferMatcher
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.sms.receiver.TransactionNotificationHelper
import com.varun.upitracker.ui.ActorType
import java.util.concurrent.TimeUnit

private const val TAG = "TransactionSaver"

/** SMS and notification timestamps can trail the payment, and fall either side of midnight. */
private val TRANSFER_MATCH_TOLERANCE_MILLIS = TimeUnit.DAYS.toMillis(1)

/**
 * Shared by every capture channel (SMS, Gmail notification, ...). upiRefId carries a unique DB
 * index, so this is also where cross-channel dedup happens: whichever channel's message for a
 * given transaction is processed first wins, and the other is dropped here.
 *
 * A message is also dropped when its payment was already reclassified as a transfer between the
 * user's own accounts -- see [claimImportedTransfer].
 */
object TransactionSaver {

    suspend fun saveIfNew(
        context: Context,
        db: AppDatabase,
        resolver: AliasResolver,
        defaultAccountId: String,
        parsed: ParsedTransaction,
        source: String,
        notify: Boolean
    ): Boolean {
        if (db.transactionDao().findByRefId(parsed.upiRefId) != null) {
            Log.d(TAG, "Duplicate ref ${parsed.upiRefId}, skipping")
            return false
        }
        if (db.accountTransferDao().findByUpiRefId(parsed.upiRefId) != null) {
            Log.d(TAG, "Ref ${parsed.upiRefId} is already a transfer, skipping")
            return false
        }
        if (claimImportedTransfer(db, parsed)) return false

        val resolution = resolver.resolve(parsed.payeeRaw, parsed.direction)
        val matchedFriendId = (resolution as? ResolvedAs.AsFriend)?.friendId
        val matchedMerchantId = (resolution as? ResolvedAs.AsMerchant)?.merchantId
        val resolvedActorType = resolution.actorType()

        val transaction = Transaction(
            amountPaise = parsed.amountPaise,
            payerActorType = if (parsed.direction == "DEBIT") ActorType.ME else resolvedActorType,
            payerFriendId = if (parsed.direction == "CREDIT") matchedFriendId else null,
            payerMerchantId = if (parsed.direction == "CREDIT") matchedMerchantId else null,
            payerRawLabel = if (parsed.direction == "CREDIT") parsed.payeeRaw else null,
            payeeActorType = if (parsed.direction == "CREDIT") ActorType.ME else resolvedActorType,
            payeeFriendId = if (parsed.direction == "DEBIT") matchedFriendId else null,
            payeeMerchantId = if (parsed.direction == "DEBIT") matchedMerchantId else null,
            payeeRawLabel = if (parsed.direction == "DEBIT") parsed.payeeRaw else null,
            upiRefId = parsed.upiRefId,
            myAccountId = defaultAccountId,
            dateEpoch = parsed.dateEpoch,
            source = source,
            isPending = true
        )

        val id = try {
            db.transactionDao().insert(transaction)
        } catch (e: Exception) {
            // Another channel inserted the same ref between our check above and this insert.
            Log.d(TAG, "Insert raced for ref ${parsed.upiRefId}, skipping")
            return false
        }

        Log.d(TAG, "Saved transaction id=$id source=$source actor=$resolvedActorType pending=true")

        if (notify) {
            val displayLabel = when (resolution) {
                is ResolvedAs.AsFriend -> resolution.name
                is ResolvedAs.AsMerchant -> resolution.name
                is ResolvedAs.Unknown -> parsed.payeeRaw
            }
            TransactionNotificationHelper.showPendingTransactionNotification(
                context = context,
                transactionId = id,
                amountPaise = parsed.amountPaise,
                displayLabel = displayLabel,
                needsReview = true
            )
        }

        return true
    }

    /**
     * True when [parsed] is a transfer converted from this same message before transfers kept their
     * UPI reference, in which case the reference is stamped on it so the next scan finds it outright.
     * See [ImportedTransferMatcher] for how narrowly that is decided.
     *
     * Matched against every savings account, not just the default one this message would land on:
     * whoever converted it may have corrected which account it came from.
     */
    private suspend fun claimImportedTransfer(db: AppDatabase, parsed: ParsedTransaction): Boolean {
        val fromEpoch = parsed.dateEpoch - TRANSFER_MATCH_TOLERANCE_MILLIS
        val toEpoch = parsed.dateEpoch + TRANSFER_MATCH_TOLERANCE_MILLIS
        val match = ImportedTransferMatcher.pick(
            // getTransfersBetween is half-open; the matcher applies the inclusive window itself.
            transfers = db.accountTransferDao().getTransfersBetween(fromEpoch, toEpoch + 1),
            amountPaise = parsed.amountPaise,
            isDebit = parsed.direction == "DEBIT",
            accountIds = db.accountDao().getByType(AccountType.SAVINGS).map { it.id },
            fromEpoch = fromEpoch,
            toEpoch = toEpoch,
            atEpoch = parsed.dateEpoch,
            allowStatementRef = true
        ) ?: return false

        try {
            db.accountTransferDao().claimRefs(match.id, upiRefId = parsed.upiRefId, statementRefNo = null)
        } catch (e: SQLiteConstraintException) {
            // Another channel stamped this reference on a transfer first: recorded either way.
            Log.d(TAG, "Transfer claim raced for ref ${parsed.upiRefId}")
        }
        Log.d(TAG, "Ref ${parsed.upiRefId} matched transfer ${match.id}, skipping")
        return true
    }

    private fun ResolvedAs.actorType(): String = when (this) {
        is ResolvedAs.AsFriend -> ActorType.FRIEND
        is ResolvedAs.AsMerchant -> ActorType.MERCHANT
        is ResolvedAs.Unknown -> ActorType.UNKNOWN
    }
}
