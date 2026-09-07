package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.meShareOnSide

class LedgerPostingService {

    /**
     * Applies [transactionId]'s effect on what ME and friends owe each other.
     *
     * [ledgerEffect] is the only explicit input; everything below it still infers
     * settlement-vs-new-debt from actor types and share emptiness, exactly as before.
     */
    suspend fun postLedger(
        ledger: LedgerPort,
        transactionId: Long,
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        ledgerEffect: LedgerEffect
    ) {
        // A gift in either direction. Without this the FRIEND -> ME branch below would treat it
        // as a repayment and settle debt the friend still genuinely owes.
        if (ledgerEffect == LedgerEffect.NONE) return

        if (payer.actorType == ActorType.FRIEND
            && payee.actorType == ActorType.ME
            && shares.isEmpty()
            && payer.friendId != null
        ) {
            ledger.applyRepayment(transactionId, payer.friendId, amountPaise)
            return
        }

        if (payer.actorType == ActorType.ME
            && payee.actorType == ActorType.FRIEND
            && shares.isEmpty()
            && payee.friendId != null
        ) {
            ledger.applyOutgoingSettlement(transactionId, payee.friendId, amountPaise)
            return
        }

        if (payer.actorType == ActorType.ME) {
            shares
                .filter {
                    it.side == "PAYER"
                        && it.participantType == ActorType.FRIEND
                        && it.friendId != null
                }
                .forEach { share ->
                    ledger.recordBalanceChange(transactionId, share.friendId!!, share.amountPaise)
                }

            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (payee.actorType == ActorType.FRIEND && payee.friendId != null && mePayerShare > 0L) {
                ledger.recordBalanceChange(transactionId, payee.friendId, mePayerShare)
            }
        }

        if (payer.actorType == ActorType.FRIEND && payer.friendId != null) {
            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (mePayerShare > 0L) {
                ledger.recordBalanceChange(transactionId, payer.friendId, -mePayerShare)
            }
        }

        if (payee.actorType == ActorType.ME) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (payer.actorType == ActorType.FRIEND && payer.friendId != null && mePayeeShare > 0L) {
                ledger.recordBalanceChange(transactionId, payer.friendId, -mePayeeShare)
            }

            shares
                .filter {
                    it.side == "PAYEE"
                        && it.participantType == ActorType.FRIEND
                        && it.friendId != null
                }
                .forEach { share ->
                    ledger.recordBalanceChange(transactionId, share.friendId!!, -share.amountPaise)
                }
        }

        if (payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (mePayeeShare > 0L) {
                ledger.recordBalanceChange(transactionId, payee.friendId, mePayeeShare)
            }
        }
    }
}
