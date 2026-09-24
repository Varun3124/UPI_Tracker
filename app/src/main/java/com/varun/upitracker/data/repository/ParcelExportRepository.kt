package com.varun.upitracker.data.repository

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelCodec
import com.varun.upitracker.domain.parcel.ParcelEligibility
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.sms.SmsBacklogScanner
import java.util.UUID

/** Why a transaction on this friend's page cannot be shared with them. */
data class ExportEligibility(
    val exportable: Set<Long>,
    val blockedReasons: Map<Long, String>
)

/**
 * Builds the parcel a friend receives.
 *
 * Reads only; nothing about exporting changes this database. The one piece of state it keeps is the
 * origin token, and that is per recipient rather than per install: it has to be stable so the same
 * transaction sent twice carries the same reference and dedups on the far side, but a single token
 * in every parcel would be a permanent fingerprint of this phone, which two recipients comparing
 * notes could use to prove they share a sender.
 */
class ParcelExportRepository(private val db: AppDatabase, private val context: Context) {

    companion object {
        /** Enough to stay pasteable in a chat message; well past any real evening out. */
        const val MAX_TRANSACTIONS = 100

        private const val ORIGIN_KEY_PREFIX = "parcel_origin_"
    }

    /**
     * Splits this friend's transactions into the ones worth sending and the ones that are not,
     * with something to say about each of the latter. The blocked ones stay on screen, greyed --
     * they were visible a moment before the user tapped Share, and silently dropping them would
     * read as a bug.
     */
    fun eligibility(transactions: List<Transaction>): ExportEligibility {
        val exportable = mutableSetOf<Long>()
        val blocked = mutableMapOf<Long, String>()
        transactions.forEach { tx ->
            val reason = ParcelEligibility.pasteBlockedReason(tx)
            if (reason == null) exportable.add(tx.id) else blocked[tx.id] = reason
        }
        return ExportEligibility(exportable, blocked)
    }

    /**
     * The encoded parcel for [transactionIds], written from [friendId]'s point of view.
     *
     * [friendId] need not be in any of them -- see [ParcelEligibility.pasteBlockedReason]. Ids not
     * eligible are skipped rather than refused: the selection came from a screen that already
     * disabled them, so anything left is a race worth ignoring.
     */
    suspend fun buildParcel(friendId: Long, transactionIds: Set<Long>): String {
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }

        val rows = transactionIds
            .mapNotNull { db.transactionDao().getTransactionById(it) }
            .filter { tx -> ParcelEligibility.pasteBlockedReason(tx) == null }
            .sortedBy { it.dateEpoch }
            .take(MAX_TRANSACTIONS)
            .map { tx ->
                ParcelPerspective.flipForRecipient(
                    transaction = tx,
                    shares = db.transactionShareDao().getSharesForTransaction(tx.id),
                    recipientFriendId = friendId,
                    friendName = { friendNames[it] },
                    merchantName = { merchantNames[it] }
                )
            }

        return ParcelCodec.encode(Parcel(ParcelFormat.VERSION, originTokenFor(friendId), rows))
    }

    /**
     * The token already minted for [friendId], or null when no parcel was ever pasted to them.
     *
     * Never mints one. The mailbox only asks so its rows can also carry the reference a pasted copy
     * would have landed under, letting the reader recognise a row they imported by paste.
     */
    fun existingOriginToken(friendId: Long): String? =
        context.getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
            .getString(ORIGIN_KEY_PREFIX + friendId, null)

    private fun originTokenFor(friendId: Long): String {
        val prefs = context.getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
        val key = ORIGIN_KEY_PREFIX + friendId
        prefs.getString(key, null)?.let { return it }
        // Short enough not to dominate a small parcel, long enough that two installs will not
        // collide. Hyphens dropped because they buy nothing here.
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(key, token).apply()
        return token
    }
}
