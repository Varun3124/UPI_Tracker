package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.declaration.CheckpointStore
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.declaration.Checkpoints
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.meShareOnSide
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import kotlinx.coroutines.runBlocking

private const val TAG = "IouRecoveryBackfill"

/**
 * Brings transactions from before [Transaction.iouRecovery] existed forward.
 *
 * Each gets the technique the old inference effectively used from ME's seat -- see
 * [IouLegs.fromRole] -- plus a correcting IOU entry for each friend the new legs post differently.
 * Those are the legs the old inference never reached when ME was only in the split, and the share it
 * counted twice when ME was on both sides. Nothing already posted is rewritten, so every settled entry
 * and every balance the two agree about stays exactly as it was.
 *
 * Keyed on the data rather than a preference, unlike the backfills before it: a backup restored from
 * before the column lands with it NULL, and needs exactly this again.
 */
class IouRecoveryBackfill(private val context: Context) {

    suspend fun run() {
        val db = AppDatabase.getInstance(context)
        if (db.transactionDao().countMissingIouRecovery() == 0) return

        var assigned = 0
        var corrected = 0
        db.runInTransaction {
            runBlocking {
                val ledger = LedgerManager(db)
                // A checkpoint already stands for every row dated before it, correction included (D8).
                val asOf = CheckpointStore(db).asOfByFriend(db.declarationDao().friendIdsWithAccepted())
                db.transactionDao().getMissingIouRecovery().forEach { tx ->
                    val shares = db.transactionShareDao().getSharesForTransaction(tx.id)
                    val upgrade = IouRecoveryUpgrade.of(tx, shares)
                    db.transactionDao().setIouRecovery(tx.id, upgrade.recovery)
                    upgrade.corrections.forEach { (friendId, deltaPaise) ->
                        if (!Checkpoints.isSealed(tx.dateEpoch, asOf[friendId])) {
                            ledger.recordBalanceChange(tx.id, friendId, deltaPaise)
                        }
                    }
                    assigned++
                    if (upgrade.corrections.isNotEmpty()) corrected++
                }
            }
        }
        Log.d(TAG, "IOU recovery backfill complete: assigned=$assigned corrected=$corrected")
    }
}

/** What bringing one row forward changes: the technique it is given, and each friend's correction. */
internal data class IouRecoveryUpgrade(
    val recovery: IouRecovery,
    /** Friend id to the entry that squares their balance: positive means they owe ME more than before. */
    val corrections: Map<Long, Long>
) {
    companion object {
        fun of(tx: Transaction, shares: List<TransactionShare>): IouRecoveryUpgrade {
            val payer = tx.payerActorRef()
            val payee = tx.payeeActorRef()
            val recovery = IouLegs.fromRole(payer, payee, shares, IouParty.Me)

            // A pending row has no entries yet, a gift never had any, and with nothing sided both
            // versions post the same thing: settling up, or nothing at all.
            if (tx.isPending || tx.ledgerEffect == LedgerEffect.NONE || shares.none { it.side != null }) {
                return IouRecoveryUpgrade(recovery, emptyMap())
            }

            val before = LegacyIouPosting.netByFriend(payer, payee, shares)
            val after = IouLegs.netByFriend(
                IouLegs.legs(payer, payee, shares, tx.amountPaise, tx.ledgerEffect, recovery)
            )
            val corrections = (before.keys + after.keys)
                .associateWith { friendId -> (after[friendId] ?: 0L) - (before[friendId] ?: 0L) }
                .filterValues { it != 0L }
            return IouRecoveryUpgrade(recovery, corrections)
        }
    }
}

/**
 * What [com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService] posted for a
 * transaction with sided shares before [IouRecovery] existed, as each friend's net.
 *
 * Frozen on purpose, and used for nothing but [IouRecoveryUpgrade]: it is the "before" a correction is
 * measured against, and a backup restored from before the column still holds entries it posted.
 */
internal object LegacyIouPosting {

    fun netByFriend(payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>): Map<Long, Long> {
        val net = linkedMapOf<Long, Long>()
        fun post(friendId: Long, deltaPaise: Long) {
            net[friendId] = (net[friendId] ?: 0L) + deltaPaise
        }

        if (payer.actorType == ActorType.ME) {
            shares
                .filter { it.side == "PAYER" && it.participantType == ActorType.FRIEND && it.friendId != null }
                .forEach { share -> post(share.friendId!!, share.amountPaise) }

            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (payee.actorType == ActorType.FRIEND && payee.friendId != null && mePayerShare > 0L) {
                post(payee.friendId, mePayerShare)
            }
        }

        if (payer.actorType == ActorType.FRIEND && payer.friendId != null) {
            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (mePayerShare > 0L) post(payer.friendId, -mePayerShare)
        }

        if (payee.actorType == ActorType.ME) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (payer.actorType == ActorType.FRIEND && payer.friendId != null && mePayeeShare > 0L) {
                post(payer.friendId, -mePayeeShare)
            }

            shares
                .filter { it.side == "PAYEE" && it.participantType == ActorType.FRIEND && it.friendId != null }
                .forEach { share -> post(share.friendId!!, -share.amountPaise) }
        }

        if (payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (mePayeeShare > 0L) post(payee.friendId, mePayeeShare)
        }

        return net.filterValues { it != 0L }
    }
}
