package com.varun.upitracker.receiver

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.IouEntry
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.ShareSide
import com.varun.upitracker.ui.deriveLegacyDirection
import com.varun.upitracker.ui.myShareFromShares
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.resolveActorDisplayName
import com.varun.upitracker.ui.shouldDefaultSmsDebitToMerchant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object PendingTransactionReviewer {

    suspend fun review(context: Context, transactionId: Long): Boolean {
        val db = AppDatabase.getInstance(context)
        val tx = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(transactionId) } ?: return false
        if (!tx.isPending) return true

        val draft = buildDefaultDraft(db, tx)
        if (!validateDraft(draft, tx.amountPaise)) return false

        return withContext(Dispatchers.IO) {
            db.runInTransaction<Boolean> {
                runBlocking {
                    persistReviewedTransaction(db, tx, draft)
                }
            }
        }
    }

    private data class ReviewDraft(
        val payerActorType: String,
        val payerFriendId: Long?,
        val payerMerchantId: Long?,
        val payerLabel: String,
        val payeeActorType: String,
        val payeeFriendId: Long?,
        val payeeMerchantId: Long?,
        val payeeLabel: String,
        val shares: List<TransactionShare>
    )

    private suspend fun buildDefaultDraft(db: AppDatabase, tx: Transaction): ReviewDraft {
        val payerActorType: String
        val payerFriendId: Long?
        val payerMerchantId: Long?
        val payeeActorType: String
        val payeeFriendId: Long?
        val payeeMerchantId: Long?

        if (tx.shouldDefaultSmsDebitToMerchant()) {
            payerActorType = ActorType.ME
            payerFriendId = null
            payerMerchantId = null
            payeeActorType = ActorType.MERCHANT
            payeeFriendId = null
            payeeMerchantId = tx.resolvedMerchantId
        } else {
            payerActorType = tx.payerActorType
            payerFriendId = tx.payerFriendId
            payerMerchantId = tx.payerMerchantId
            payeeActorType = tx.payeeActorType
            payeeFriendId = tx.payeeFriendId
            payeeMerchantId = tx.payeeMerchantId
        }

        val payerLabel = defaultEndpointLabel(
            db = db,
            tx = tx,
            isPayer = true,
            actor = ActorRef(payerActorType, payerFriendId, payerMerchantId, tx.payerRawLabel)
        )
        val payeeLabel = defaultEndpointLabel(
            db = db,
            tx = tx,
            isPayer = false,
            actor = ActorRef(payeeActorType, payeeFriendId, payeeMerchantId, tx.payeeRawLabel)
        )

        val shares = db.transactionShareDao().getSharesForTransaction(tx.id).ifEmpty {
            buildFallbackShares(tx, db)
        }

        return ReviewDraft(
            payerActorType = payerActorType,
            payerFriendId = payerFriendId,
            payerMerchantId = payerMerchantId,
            payerLabel = payerLabel,
            payeeActorType = payeeActorType,
            payeeFriendId = payeeFriendId,
            payeeMerchantId = payeeMerchantId,
            payeeLabel = payeeLabel,
            shares = shares
        )
    }

    private suspend fun defaultEndpointLabel(
        db: AppDatabase,
        tx: Transaction,
        isPayer: Boolean,
        actor: ActorRef
    ): String {
        if (tx.shouldDefaultSmsDebitToMerchant()) {
            return if (isPayer) {
                "Me"
            } else {
                tx.resolvedMerchantId?.let { db.merchantDao().getMerchantById(it)?.name }
                    ?: tx.payeeRawLabel
                    ?: tx.payeeRaw
            }
        }
        return resolveActorDisplayName(db, actor)
    }

    private suspend fun buildFallbackShares(tx: Transaction, db: AppDatabase): List<TransactionShare> {
        val shares = mutableListOf<TransactionShare>()
        val iouEntries = db.iouDao().getEntriesForTransaction(tx.id)
        val partyEntries = db.transactionPartyDao().getPartiesForTransaction(tx.id)
        val fallbackMyAmount = tx.mySharePaise ?: tx.amountPaise
        val fallbackMySide = if (tx.direction == "CREDIT") ShareSide.MEANT_TO_RECEIVE else ShareSide.MEANT_TO_PAY

        if (fallbackMyAmount > 0) {
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.ME,
                shareSide = fallbackMySide,
                amountPaise = fallbackMyAmount
            )
        }

        val positiveFriendAmounts = mutableMapOf<Long, Long>()
        iouEntries.filter { it.amountPaise > 0 }.forEach { entry ->
            positiveFriendAmounts[entry.friendId] = (positiveFriendAmounts[entry.friendId] ?: 0L) + entry.amountPaise
        }
        partyEntries.forEach { entry ->
            positiveFriendAmounts[entry.friendId] = (positiveFriendAmounts[entry.friendId] ?: 0L) + entry.spentOnThemPaise
        }
        positiveFriendAmounts.forEach { (friendId, amount) ->
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.FRIEND,
                friendId = friendId,
                shareSide = ShareSide.MEANT_TO_PAY,
                amountPaise = amount
            )
        }

        if (shares.none { it.participantType == ActorType.FRIEND } && tx.payeeType == "FRIEND" && tx.resolvedFriendId != null) {
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.FRIEND,
                friendId = tx.resolvedFriendId,
                shareSide = if (tx.direction == "CREDIT") ShareSide.MEANT_TO_PAY else ShareSide.MEANT_TO_RECEIVE,
                amountPaise = tx.amountPaise
            )
        }

        return shares
    }

    private fun validateDraft(draft: ReviewDraft, amountPaise: Long): Boolean {
        if (draft.payerActorType != ActorType.ME && draft.payerLabel.isBlank()) return false
        if (draft.payeeActorType != ActorType.ME && draft.payeeLabel.isBlank()) return false

        if (draft.payerActorType == draft.payeeActorType) {
            val sameActor = when (draft.payerActorType) {
                ActorType.ME -> true
                else -> draft.payerLabel.equals(draft.payeeLabel, ignoreCase = true)
            }
            if (sameActor) return false
        }

        val payTotal = draft.shares
            .filter { it.shareSide == ShareSide.MEANT_TO_PAY }
            .sumOf { it.amountPaise } + if (draft.payerActorType == ActorType.MERCHANT) amountPaise else 0L
        val receiveTotal = draft.shares
            .filter { it.shareSide == ShareSide.MEANT_TO_RECEIVE }
            .sumOf { it.amountPaise } + if (draft.payeeActorType == ActorType.MERCHANT) amountPaise else 0L

        return payTotal == amountPaise && receiveTotal == amountPaise
    }

    private suspend fun persistReviewedTransaction(
        db: AppDatabase,
        tx: Transaction,
        draft: ReviewDraft
    ): Boolean {
        val observedDirection = tx.observedDirection ?: tx.direction.takeIf { tx.source == "SMS" }
        val observedRaw = tx.payeeRaw

        val payer = resolveActor(
            db = db,
            actorType = draft.payerActorType,
            typedLabel = draft.payerLabel,
            existingFriendId = draft.payerFriendId,
            existingMerchantId = draft.payerMerchantId,
            isPayer = true,
            observedDirection = observedDirection,
            observedRaw = observedRaw
        )
        val payee = resolveActor(
            db = db,
            actorType = draft.payeeActorType,
            typedLabel = draft.payeeLabel,
            existingFriendId = draft.payeeFriendId,
            existingMerchantId = draft.payeeMerchantId,
            isPayer = false,
            observedDirection = observedDirection,
            observedRaw = observedRaw
        )

        val meShare = myShareFromShares(draft.shares)
        val direction = deriveLegacyDirection(
            payerActorType = payer.actorType,
            payeeActorType = payee.actorType,
            myShareSide = meShare?.shareSide,
            mySharePaise = meShare?.amountPaise ?: 0L
        )
        val source = buildSourceMetadata(tx, payer, payee, observedDirection, observedRaw)

        val updatedTx = tx.copy(
            direction = direction,
            payeeRaw = source.rawLabel,
            payeeType = source.payeeType,
            mySharePaise = meShare?.amountPaise ?: 0L,
            resolvedFriendId = source.resolvedFriendId,
            resolvedMerchantId = source.resolvedMerchantId,
            payerActorType = payer.actorType,
            payerFriendId = payer.friendId,
            payerMerchantId = payer.merchantId,
            payerRawLabel = payer.rawLabel,
            payeeActorType = payee.actorType,
            payeeFriendId = payee.friendId,
            payeeMerchantId = payee.merchantId,
            payeeRawLabel = payee.rawLabel,
            isPending = false
        )

        db.transactionDao().update(updatedTx)
        db.iouDao().deleteForTransaction(tx.id)
        db.transactionPartyDao().deleteForTransaction(tx.id)
        db.categorySplitDao().deleteForTransaction(tx.id)
        db.transactionShareDao().deleteForTransaction(tx.id)
        db.transactionShareDao().insertAll(draft.shares.map { it.copy(transactionId = tx.id) })
        postLedger(db, tx.id, payer, payee, draft.shares, tx.amountPaise)
        return true
    }

    private suspend fun resolveActor(
        db: AppDatabase,
        actorType: String,
        typedLabel: String,
        existingFriendId: Long?,
        existingMerchantId: Long?,
        isPayer: Boolean,
        observedDirection: String?,
        observedRaw: String?
    ): ActorRef {
        return when (actorType) {
            ActorType.ME -> ActorRef(ActorType.ME, rawLabel = "Me")
            ActorType.FRIEND -> {
                val friendId = existingFriendId ?: resolveFriendId(db, typedLabel)
                maybeLinkObservedAliasToFriend(db, friendId, isPayer, observedDirection, observedRaw)
                ActorRef(ActorType.FRIEND, friendId = friendId, rawLabel = typedLabel)
            }
            ActorType.MERCHANT -> {
                val merchantId = existingMerchantId ?: resolveMerchantId(db, typedLabel)
                maybeLinkObservedAliasToMerchant(db, merchantId, isPayer, observedDirection, observedRaw)
                ActorRef(ActorType.MERCHANT, merchantId = merchantId, rawLabel = typedLabel)
            }
            else -> ActorRef(ActorType.UNKNOWN, rawLabel = typedLabel)
        }
    }

    private suspend fun resolveFriendId(db: AppDatabase, typedLabel: String): Long {
        db.friendDao().findByName(typedLabel)?.let { return it.id }
        val initials = typedLabel.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
        return db.friendDao().insertFriend(
            Friend(
                name = typedLabel,
                avatarInitials = initials.ifBlank { "F" },
                addedEpoch = System.currentTimeMillis()
            )
        )
    }

    private suspend fun resolveMerchantId(db: AppDatabase, typedLabel: String): Long {
        db.merchantDao().findByName(typedLabel)?.let { return it.id }
        return db.merchantDao().insertMerchant(
            Merchant(
                name = typedLabel,
                addedEpoch = System.currentTimeMillis()
            )
        )
    }

    private suspend fun maybeLinkObservedAliasToFriend(
        db: AppDatabase,
        friendId: Long,
        isPayer: Boolean,
        observedDirection: String?,
        observedRaw: String?
    ) {
        if (observedRaw.isNullOrBlank() || observedDirection.isNullOrBlank()) return
        val matchesObservedActor = (observedDirection == "CREDIT" && isPayer) || (observedDirection == "DEBIT" && !isPayer)
        if (!matchesObservedActor) return

        if (observedDirection == "CREDIT" || observedRaw.contains("@")) {
            db.friendDao().insertUpiId(FriendUpiId(friendId = friendId, upiId = observedRaw))
        } else {
            db.friendDao().insertRawName(FriendRawName(friendId = friendId, rawName = observedRaw))
        }
    }

    private suspend fun maybeLinkObservedAliasToMerchant(
        db: AppDatabase,
        merchantId: Long,
        isPayer: Boolean,
        observedDirection: String?,
        observedRaw: String?
    ) {
        if (observedRaw.isNullOrBlank() || observedDirection.isNullOrBlank()) return
        val matchesObservedActor = (observedDirection == "CREDIT" && isPayer) || (observedDirection == "DEBIT" && !isPayer)
        if (!matchesObservedActor) return

        if (observedDirection == "CREDIT" || observedRaw.contains("@")) {
            db.merchantDao().insertUpiId(MerchantUpiId(merchantId = merchantId, upiId = observedRaw))
        } else {
            db.merchantDao().insertRawName(MerchantRawName(merchantId = merchantId, rawName = observedRaw))
        }
    }

    private data class SourceMetadata(
        val rawLabel: String,
        val payeeType: String,
        val resolvedFriendId: Long?,
        val resolvedMerchantId: Long?
    )

    private fun buildSourceMetadata(
        tx: Transaction,
        payer: ActorRef,
        payee: ActorRef,
        observedDirection: String?,
        observedRaw: String?
    ): SourceMetadata {
        val observedActor = when (observedDirection) {
            "CREDIT" -> payer
            "DEBIT" -> payee
            else -> if (payee.actorType != ActorType.ME) payee else payer
        }

        return SourceMetadata(
            rawLabel = observedRaw ?: observedActor.rawLabel ?: tx.payeeRaw,
            payeeType = when (observedActor.actorType) {
                ActorType.FRIEND -> "FRIEND"
                ActorType.MERCHANT -> "MERCHANT"
                else -> "UNKNOWN"
            },
            resolvedFriendId = observedActor.friendId,
            resolvedMerchantId = observedActor.merchantId
        )
    }

    private suspend fun postLedger(
        db: AppDatabase,
        transactionId: Long,
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long
    ) {
        if (shares.none { it.participantType == ActorType.ME }) return

        val ledger = LedgerManager(db)
        if (payer.actorType == ActorType.FRIEND && payee.actorType == ActorType.ME && payer.friendId != null) {
            ledger.applyRepayment(transactionId, payer.friendId, amountPaise)
        }
        if (payer.actorType == ActorType.ME && payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            ledger.applyOutgoingSettlement(transactionId, payee.friendId, amountPaise)
        }

        val friendIds = mutableSetOf<Long>()
        payer.friendId?.let { friendIds += it }
        payee.friendId?.let { friendIds += it }
        shares.filter { it.participantType == ActorType.FRIEND }.mapNotNullTo(friendIds) { it.friendId }

        friendIds.forEach { friendId ->
            val actualNet = when {
                payee.actorType == ActorType.FRIEND && payee.friendId == friendId -> amountPaise
                payer.actorType == ActorType.FRIEND && payer.friendId == friendId -> -amountPaise
                else -> 0L
            }
            val intendedPay = shares
                .filter {
                    it.participantType == ActorType.FRIEND &&
                        it.friendId == friendId &&
                        it.shareSide == ShareSide.MEANT_TO_PAY
                }
                .sumOf { it.amountPaise }
            val intendedReceive = shares
                .filter {
                    it.participantType == ActorType.FRIEND &&
                        it.friendId == friendId &&
                        it.shareSide == ShareSide.MEANT_TO_RECEIVE
                }
                .sumOf { it.amountPaise }

            val delta = actualNet - (intendedReceive - intendedPay)
            if (delta != 0L) {
                ledger.recordBalanceChange(transactionId, friendId, delta)
            }
        }
    }
}
