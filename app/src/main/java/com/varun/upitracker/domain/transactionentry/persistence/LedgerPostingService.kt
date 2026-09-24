package com.varun.upitracker.domain.transactionentry.persistence

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.ledger.LedgerPort
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType

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

        val legs = IouLegs.legs(payer, payee, shares, amountPaise, ledgerEffect, iouRecovery)
        IouLegs.netByFriend(legs).forEach { (friendId, deltaPaise) ->
            ledger.recordBalanceChange(transactionId, friendId, deltaPaise)
        }
    }
}
