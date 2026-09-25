package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorRef

class LedgerPostingService {

    /**
     * Applies [transactionId]'s effect on what ME and friends owe each other.
     *
     * Money straight between ME and a friend with no split at all still settles up, oldest debt
     * first. Everything else posts the legs [IouLegs] derives under [iouRecovery] -- only the ones
     * between ME and a friend this database knows. The rest are debts between other people, and are
     * theirs to record.
     */
    suspend fun postLedger(
        ledger: LedgerPort,
        transactionId: Long,
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        ledgerEffect: LedgerEffect,
        iouRecovery: IouRecovery
    ) {
        // A gift in either direction. Without this the FRIEND -> ME branch below would treat it
        // as a repayment and settle debt the friend still genuinely owes.
        if (ledgerEffect == LedgerEffect.NONE) return

        // Read off IouLegs so a chapter deriving the same shape cannot disagree with what was
        // posted here. The two ends being a known friend and ME is exactly what the explicit
        // actorType/friendId checks used to say.
        val direct = IouLegs.directPaymentParties(payer, payee, shares, amountPaise)
        if (direct != null) {
            val (payerParty, payeeParty) = direct
            if (payerParty is IouParty.Friend && payeeParty == IouParty.Me) {
                ledger.applyRepayment(transactionId, payerParty.friendId, amountPaise)
                return
            }
            if (payerParty == IouParty.Me && payeeParty is IouParty.Friend) {
                ledger.applyOutgoingSettlement(transactionId, payeeParty.friendId, amountPaise)
                return
            }
        }

        val legs = IouLegs.legs(payer, payee, shares, amountPaise, ledgerEffect, iouRecovery)
        IouLegs.netByFriend(legs).forEach { (friendId, deltaPaise) ->
            ledger.recordBalanceChange(transactionId, friendId, deltaPaise)
        }
    }
}
