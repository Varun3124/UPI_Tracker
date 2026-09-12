package com.varun.upitracker.parser

import android.content.Context
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.resolver.AliasResolver
import com.varun.upitracker.resolver.ResolvedAs
import com.varun.upitracker.sms.receiver.TransactionNotificationHelper
import com.varun.upitracker.ui.ActorType

private const val TAG = "TransactionSaver"

/**
 * Shared by every capture channel (SMS, Gmail notification, ...). upiRefId carries a unique DB
 * index, so this is also where cross-channel dedup happens: whichever channel's message for a
 * given transaction is processed first wins, and the other is dropped here.
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

    private fun ResolvedAs.actorType(): String = when (this) {
        is ResolvedAs.AsFriend -> ActorType.FRIEND
        is ResolvedAs.AsMerchant -> ActorType.MERCHANT
        is ResolvedAs.Unknown -> ActorType.UNKNOWN
    }
}
