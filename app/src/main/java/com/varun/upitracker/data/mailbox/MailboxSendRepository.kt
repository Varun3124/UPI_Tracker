package com.varun.upitracker.data.mailbox

import android.content.Context
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.TransactionDelivery
import com.varun.upitracker.domain.mailbox.MailboxCrypto
import com.varun.upitracker.domain.mailbox.MailboxEnvelope
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.mailbox.PublicMailboxKeys
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelEligibility
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.ParcelPerspective
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Someone a selection could go to, and what they would get. */
data class RecipientOption(
    val friendId: Long,
    val name: String,
    /** Which of the selected transactions they are in and may be sent through the mailbox. */
    val eligibleTransactionIds: Set<Long>,
    /**
     * Which of the selected transactions may be pasted to them. Anyone can be handed a parcel,
     * whether or not they are in it -- see [ParcelEligibility.pasteBlockedReason].
     */
    val pasteTransactionIds: Set<Long>,
    /** True when they are at either end of, or in the split of, any of the selected transactions. */
    val involved: Boolean,
    /** One of [FriendLinkState], or null when they are not linked. */
    val linkState: String?,
    /** The last time any of the selected transactions went to them, if ever. */
    val lastSentEpoch: Long?
) {
    val isLinked: Boolean get() = linkState == FriendLinkState.LINKED
}

data class SendOutcome(
    val deliveredTo: List<String>,
    /** One sentence per recipient that did not get it, saying why. */
    val problems: List<String>
)

/**
 * Sends transactions to one or more linked friends at once, each their own sealed copy.
 *
 * Each recipient gets the transactions written from their point of view, just as a pasted parcel
 * is. What the mailbox adds is that people sent the same transaction together are named in each
 * other's copies by account, so each of them can tell who the others are without matching a name.
 */
class MailboxSendRepository(private val context: Context, private val db: AppDatabase) {

    private val services = MailboxServices.get(context)
    private val identities = MailboxIdentityRepository(context, db)

    suspend fun recipientOptions(transactionIds: Set<Long>): List<RecipientOption> = withContext(Dispatchers.IO) {
        val transactions = transactionIds.mapNotNull { db.transactionDao().getTransactionById(it) }
        val sharesById = transactions.associate { it.id to db.transactionShareDao().getSharesForTransaction(it.id) }
        val deliveries = if (transactions.isEmpty()) emptyList() else db.mailboxDao().getDeliveriesFor(transactions.map { it.id })

        val involvedIds = transactions.flatMap { tx ->
            listOfNotNull(tx.payerFriendId, tx.payeeFriendId) +
                sharesById[tx.id].orEmpty().filter { it.side != null }.mapNotNull { it.friendId }
        }.toSet()
        // The same for everyone: a paste does not depend on who it goes to.
        val pasteIds = transactions.filter { ParcelEligibility.pasteBlockedReason(it) == null }.map { it.id }.toSet()

        // Everyone, not only the people in these: any of them can be handed a parcel to paste.
        db.friendDao().getAllFriendsSync().map { friend ->
            val friendId = friend.id
            RecipientOption(
                friendId = friendId,
                name = friend.name,
                eligibleTransactionIds = transactions
                    .filter { ParcelEligibility.blockedReason(it, sharesById[it.id].orEmpty(), friendId) == null }
                    .map { it.id }
                    .toSet(),
                pasteTransactionIds = pasteIds,
                involved = friendId in involvedIds,
                linkState = db.mailboxDao().getLink(friendId)?.state,
                lastSentEpoch = deliveries.filter { it.friendId == friendId }.maxOfOrNull { it.sentEpoch }
            )
        }.sortedWith(
            compareByDescending<RecipientOption> { it.involved }
                .thenByDescending { it.involved && it.isLinked }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Sends [transactionIds] to each of [recipientFriendIds] that is linked, in its own message.
     *
     * One create per recipient rather than one batch, so that one friend who has unlinked -- the
     * rules refuse the write -- does not stop the rest getting theirs.
     */
    suspend fun send(transactionIds: Set<Long>, recipientFriendIds: Set<Long>): SendOutcome = withContext(Dispatchers.IO) {
        val identity = identities.identity()
            ?: throw MailboxException("Turn the friends mailbox on first, in Settings.", MailboxException.Kind.NOT_READY)
        val transactions = transactionIds.mapNotNull { db.transactionDao().getTransactionById(it) }
        val sharesById = transactions.associate { it.id to db.transactionShareDao().getSharesForTransaction(it.id) }
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }
        val export = ParcelExportRepository(db, context)

        val delivered = mutableListOf<String>()
        val problems = mutableListOf<String>()

        // Who can be sent to right now, with their keys checked against the ones pinned at linking.
        val ready = mutableListOf<Pair<FriendLink, PublicMailboxKeys>>()
        recipientFriendIds.forEach { friendId ->
            val name = friendNames[friendId] ?: return@forEach
            val link = db.mailboxDao().getLink(friendId)
            if (link == null || link.state != FriendLinkState.LINKED) {
                problems += "$name is not linked yet."
                return@forEach
            }
            val published = try {
                services.firestore.publishedKeys(services.auth.idToken(), link.uid)
            } catch (error: MailboxException) {
                problems += "Could not check $name's keys. ${error.message}"
                return@forEach
            }
            when {
                published == null -> problems += "$name has turned their friends mailbox off."
                published.fingerprint != link.fingerprint -> {
                    db.mailboxDao().setLinkState(friendId, FriendLinkState.KEY_CHANGED)
                    problems += "$name's security key has changed. Link with them again to keep sending."
                }
                else -> ready += link to published
            }
        }

        fun eligibleFor(friendId: Long) = transactions.filter {
            ParcelEligibility.blockedReason(it, sharesById[it.id].orEmpty(), friendId) == null
        }

        // Every row that goes anywhere gets its reference before any copy leaves, so all copies agree.
        val going = ready.flatMap { (link, _) -> eligibleFor(link.friendId) }.distinctBy { it.id }
        going.filter { it.shareRef == null }.forEach { db.transactionDao().assignShareRef(it.id, MailboxIds.newRandomId()) }
        val shareRefs = going.associate { it.id to db.transactionDao().getTransactionById(it.id)?.shareRef }

        // People sent this together may know each other by account; nobody else is named that way.
        val sentTogether = ready.associate { (link, _) -> link.friendId to link.uid }

        ready.forEach { (link, keys) ->
            val name = friendNames[link.friendId] ?: "your friend"
            val rows = eligibleFor(link.friendId).sortedBy { it.dateEpoch }.take(ParcelExportRepository.MAX_TRANSACTIONS)
            if (rows.isEmpty()) {
                problems += "Nothing you picked involves $name."
                return@forEach
            }
            val pasteToken = export.existingOriginToken(link.friendId)
            val parcelRows = rows.mapNotNull { tx ->
                val shareRef = shareRefs[tx.id] ?: return@mapNotNull null
                ParcelPerspective.flipForRecipient(
                    transaction = tx,
                    shares = sharesById[tx.id].orEmpty(),
                    recipientFriendId = link.friendId,
                    friendName = { friendNames[it] },
                    merchantName = { merchantNames[it] },
                    linkedUidOf = { sentTogether[it] }
                ).copy(
                    sourceId = 0L,
                    shareRef = shareRef,
                    legacyRef = pasteToken?.let { ParcelPerspective.sharedRefIdFor(it, tx.id) }
                )
            }

            val messageId = MailboxIds.newRandomId()
            val now = System.currentTimeMillis()
            val envelope = MailboxEnvelope(
                kind = MailboxKind.PARCEL,
                messageId = messageId,
                senderUid = identity.uid,
                recipientUid = link.uid,
                createdEpoch = now,
                body = ParcelFormat.format(Parcel(ParcelFormat.MAILBOX_VERSION, null, parcelRows))
            )
            try {
                services.firestore.create(
                    services.auth.idToken(),
                    "inbox/${link.uid}/messages",
                    messageId,
                    mapOf(
                        "from" to FirestoreValue.Text(identity.uid),
                        "ciphertext" to FirestoreValue.Bytes(MailboxCrypto.seal(envelope, identity.keys, keys))
                    )
                )
                db.mailboxDao().insertDeliveries(
                    rows.map { TransactionDelivery(transactionId = it.id, friendId = link.friendId, messageId = messageId, sentEpoch = now) }
                )
                delivered += name
            } catch (error: MailboxException) {
                problems += when (error.kind) {
                    MailboxException.Kind.PERMISSION_DENIED -> "$name is not accepting messages from you. Ask them to link with you again."
                    MailboxException.Kind.TRANSIENT -> "Could not reach the mailbox to send to $name. Try again."
                    else -> "Could not send to $name. ${error.message}"
                }
            }
        }
        SendOutcome(delivered, problems)
    }
}
