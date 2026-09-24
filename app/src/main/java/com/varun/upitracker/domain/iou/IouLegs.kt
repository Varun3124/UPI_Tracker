package com.varun.upitracker.domain.iou

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/** Someone a leg names. */
sealed interface IouParty {

    data object Me : IouParty

    data class Friend(val friendId: Long) : IouParty

    /**
     * A person this database cannot identify. A leg naming them is still a real IOU -- it just has
     * nowhere to be posted here.
     */
    data class Person(val label: String?) : IouParty

    /**
     * A shop or an unclassified counterparty. A leg naming one is never an IOU: it is the part of the
     * money that was spent, or came in, rather than lent.
     */
    data class Counterparty(val label: String?) : IouParty
}

/** [debtor] owes [creditor] [amountPaise]. */
data class IouLeg(val debtor: IouParty, val creditor: IouParty, val amountPaise: Long)

/**
 * One of a share row's two legs: [owesPayer] is the one where the row's participant owes the payer,
 * otherwise it is the one where the payee owes them.
 */
data class IouLegSource(val shareIndex: Int, val owesPayer: Boolean)

/**
 * One "who owes whom" line: every leg between the same two people, folded. [kept] is whether all of
 * them are recorded, and [sources] are the share-row legs that turning the line on or off flips.
 */
data class IouLine(
    val debtor: IouParty,
    val creditor: IouParty,
    val amountPaise: Long,
    val kept: Boolean,
    val sources: List<IouLegSource>
)

/**
 * Who owes whom on one transaction, worked out the same way on every phone that holds it.
 *
 * The payer and payee are the transaction's two ends, and [IouRecovery] picks which side's share rows
 * describe the split. Every row on that side reads as "the payee owes this person" and "this person
 * owes the payer", each for their share, dropping whichever leg would have someone owe themselves.
 * The payer ends up owed the whole amount, the payee owing it, and everyone else on the chosen side at
 * zero -- they only pass their share through. Either leg of a row can be left out, and a left-out leg
 * is simply not owed.
 *
 * Nothing here asks where ME sits, which is the point: a friend holding the same transaction, with ME
 * and SENDER swapped, derives exactly the same legs.
 */
object IouLegs {

    private const val PAYER = "PAYER"
    private const val PAYEE = "PAYEE"

    /** A candidate leg, whether or not the user kept it. */
    private class Candidate(val leg: IouLeg, val source: IouLegSource?, val kept: Boolean)

    /** The legs actually recorded: every kept one, or none at all for a transaction that moves no debt. */
    fun legs(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        ledgerEffect: LedgerEffect,
        recovery: IouRecovery
    ): List<IouLeg> {
        if (ledgerEffect == LedgerEffect.NONE) return emptyList()
        return candidates(payer, payee, shares, amountPaise, recovery).filter { it.kept }.map { it.leg }
    }

    /**
     * Every line between two people this transaction could record, kept or not, in the order each pair
     * first appears. Legs with a shop at one end are left out: those are spending, not IOUs.
     */
    fun lines(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        recovery: IouRecovery
    ): List<IouLine> {
        val groups = linkedMapOf<Set<IouParty>, MutableList<Candidate>>()
        candidates(payer, payee, shares, amountPaise, recovery)
            .filter { isPerson(it.leg.debtor) && isPerson(it.leg.creditor) }
            .forEach { groups.getOrPut(setOf(it.leg.debtor, it.leg.creditor)) { mutableListOf() }.add(it) }

        return groups.values.mapNotNull { group ->
            val first = group.first().leg
            val net = group.fold(0L) { total, candidate ->
                total + if (candidate.leg.debtor == first.debtor) candidate.leg.amountPaise else -candidate.leg.amountPaise
            }
            val kept = group.all { it.kept }
            val sources = group.mapNotNull { it.source }
            when {
                net > 0L -> IouLine(first.debtor, first.creditor, net, kept, sources)
                net < 0L -> IouLine(first.creditor, first.debtor, -net, kept, sources)
                else -> null
            }
        }
    }

    /**
     * NONE once there are lines and every one of them has been left out -- the transaction moves nobody's
     * balance -- and DEBT otherwise, including when there is nothing to leave out.
     */
    fun ledgerEffect(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        recovery: IouRecovery
    ): LedgerEffect {
        val lines = lines(payer, payee, shares, amountPaise, recovery)
        return if (lines.isNotEmpty() && lines.none { it.kept }) LedgerEffect.NONE else LedgerEffect.DEBT
    }

    /**
     * What the transaction leaves ME up or down once [keptLegs] are counted: money through ME's own
     * account, plus what ME is now owed, minus what ME now owes. Zero when every rupee is an IOU;
     * otherwise ME's own expense (negative) or income (positive), which is what categories measure.
     */
    fun meNet(payer: ActorRef, payee: ActorRef, amountPaise: Long, keptLegs: List<IouLeg>): Long {
        var net = 0L
        if (payer.actorType == ActorType.ME) net -= amountPaise
        if (payee.actorType == ActorType.ME) net += amountPaise
        keptLegs.forEach { leg ->
            if (leg.creditor == IouParty.Me && isPerson(leg.debtor)) net += leg.amountPaise
            if (leg.debtor == IouParty.Me && isPerson(leg.creditor)) net -= leg.amountPaise
        }
        return net
    }

    /** What [legs] do to each friend's balance with ME: positive means they end up owing ME. */
    fun netByFriend(legs: List<IouLeg>): Map<Long, Long> {
        val net = linkedMapOf<Long, Long>()
        legs.forEach { leg ->
            val debtor = leg.debtor
            val creditor = leg.creditor
            if (debtor is IouParty.Friend && creditor == IouParty.Me) {
                net[debtor.friendId] = (net[debtor.friendId] ?: 0L) + leg.amountPaise
            } else if (debtor == IouParty.Me && creditor is IouParty.Friend) {
                net[creditor.friendId] = (net[creditor.friendId] ?: 0L) - leg.amountPaise
            }
        }
        return net.filterValues { it != 0L }
    }

    /**
     * [chosen], unless a shop is at one end: a shop holds no share rows, so the split can only be read
     * off the people's side.
     */
    fun effectiveRecovery(payer: ActorRef, payee: ActorRef, chosen: IouRecovery): IouRecovery = when {
        payer.actorType == ActorType.MERCHANT -> IouRecovery.FROM_SECONDARY_PAYEES
        payee.actorType == ActorType.MERCHANT -> IouRecovery.FROM_SECONDARY_PAYERS
        else -> chosen
    }

    /**
     * What a new transaction starts out recovering from: the side that is actually split, or the payer
     * side when both or neither are -- and when neither is, the two give the same legs anyway.
     */
    fun shapeDefault(payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>): IouRecovery {
        val payerSplit = hasSecondaries(payer, shares.filter { it.side == PAYER })
        val payeeSplit = hasSecondaries(payee, shares.filter { it.side == PAYEE })
        val chosen = if (payeeSplit && !payerSplit) {
            IouRecovery.FROM_SECONDARY_PAYEES
        } else {
            IouRecovery.FROM_SECONDARY_PAYERS
        }
        return effectiveRecovery(payer, payee, chosen)
    }

    /**
     * The technique the old inference effectively used from [party]'s seat: the side they were on, a
     * primary role before a share.
     *
     * Brings a row from before [IouRecovery] existed forward without moving its balances, and reads a
     * parcel from an app that never wrote one the way that app's own ledger did. Someone the row does
     * not involve had no inference to preserve, and gets [shapeDefault].
     */
    fun fromRole(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        party: IouParty
    ): IouRecovery {
        fun holdsShareOn(side: String) =
            shares.any { it.side == side && it.amountPaise > 0L && partyOfShare(it) == party }

        val chosen = when {
            partyOf(payer) == party -> IouRecovery.FROM_SECONDARY_PAYERS
            partyOf(payee) == party -> IouRecovery.FROM_SECONDARY_PAYEES
            holdsShareOn(PAYER) -> IouRecovery.FROM_SECONDARY_PAYERS
            holdsShareOn(PAYEE) -> IouRecovery.FROM_SECONDARY_PAYEES
            else -> return shapeDefault(payer, payee, shares)
        }
        return effectiveRecovery(payer, payee, chosen)
    }

    /** Whether anyone besides [primary] holds a share among [sideShares]. */
    fun hasSecondaries(primary: ActorRef, sideShares: List<TransactionShare>): Boolean =
        sideShares.any { it.amountPaise > 0L && !rowMatches(it, primary) }

    /** Whether the two techniques can post different legs here -- the only time asking is worth it. */
    fun choiceMatters(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        ledgerEffect: LedgerEffect
    ): Boolean {
        if (ledgerEffect == LedgerEffect.NONE) return false
        if (payer.actorType == ActorType.MERCHANT || payee.actorType == ActorType.MERCHANT) return false
        return hasSecondaries(payer, shares.filter { it.side == PAYER }) ||
            hasSecondaries(payee, shares.filter { it.side == PAYEE })
    }

    /**
     * The technique [transaction] posts under. A row from before [Transaction.iouRecovery] existed
     * reads as what ME's seat implied, which is exactly what
     * [com.varun.upitracker.maintenance.IouRecoveryBackfill] stores for it.
     */
    fun resolve(transaction: Transaction, shares: List<TransactionShare>): IouRecovery {
        val payer = transaction.payerActorRef()
        val payee = transaction.payeeActorRef()
        return effectiveRecovery(
            payer,
            payee,
            transaction.iouRecovery ?: fromRole(payer, payee, shares, IouParty.Me)
        )
    }

    fun partyOf(actor: ActorRef): IouParty = when (actor.actorType) {
        ActorType.ME -> IouParty.Me
        ActorType.FRIEND -> actor.friendId?.let { IouParty.Friend(it) } ?: IouParty.Person(actor.rawLabel)
        else -> IouParty.Counterparty(actor.rawLabel)
    }

    private fun isPerson(party: IouParty): Boolean = party !is IouParty.Counterparty

    private fun candidates(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        recovery: IouRecovery
    ): List<Candidate> {
        val payerParty = partyOf(payer)
        val payeeParty = partyOf(payee)
        val side = sideOf(effectiveRecovery(payer, payee, recovery))
        val sideRows = shares.withIndex().filter { it.value.side == side }

        if (sideRows.isEmpty()) {
            // The other side is split but this one was left to its primary, who holds the whole
            // amount. Nothing sided at all is a legacy sideless split, which says nothing about who
            // owes what.
            if (shares.none { it.side != null } || amountPaise <= 0L || payeeParty == payerParty) return emptyList()
            return listOf(Candidate(IouLeg(payeeParty, payerParty, amountPaise), source = null, kept = true))
        }

        return sideRows.flatMap { (index, share) ->
            if (share.amountPaise <= 0L) return@flatMap emptyList()
            val party = partyOfRow(share, payer, payee)
            listOfNotNull(
                Candidate(
                    IouLeg(payeeParty, party, share.amountPaise),
                    IouLegSource(index, owesPayer = false),
                    share.keepPayeeLeg
                ).takeIf { party != payeeParty },
                Candidate(
                    IouLeg(party, payerParty, share.amountPaise),
                    IouLegSource(index, owesPayer = true),
                    share.keepPayerLeg
                ).takeIf { party != payerParty }
            )
        }
    }

    private fun partyOfShare(share: TransactionShare): IouParty = when {
        share.participantType == ActorType.ME -> IouParty.Me
        share.friendId != null -> IouParty.Friend(share.friendId)
        else -> IouParty.Person(share.rawLabel)
    }

    /** Who a share row belongs to. A row that is one of the two ends takes that end's identity. */
    private fun partyOfRow(share: TransactionShare, payer: ActorRef, payee: ActorRef): IouParty {
        val (ownEnd, otherEnd) = if (share.side == PAYEE) payee to payer else payer to payee
        return when {
            rowMatches(share, ownEnd) -> partyOf(ownEnd)
            rowMatches(share, otherEnd) -> partyOf(otherEnd)
            else -> partyOfShare(share)
        }
    }

    /**
     * Whether [share] is [actor]'s own row. By id when the share has one, otherwise by name: an
     * imported row naming someone this database did not know keeps that name on the share even after
     * saving it on the entry screen gave the actor itself an id.
     */
    private fun rowMatches(share: TransactionShare, actor: ActorRef): Boolean = when (actor.actorType) {
        ActorType.ME -> share.participantType == ActorType.ME
        ActorType.FRIEND -> share.participantType == ActorType.FRIEND && if (share.friendId != null) {
            share.friendId == actor.friendId
        } else {
            normalisedName(share.rawLabel) == normalisedName(actor.rawLabel)
        }
        else -> false
    }

    private fun normalisedName(label: String?): String = label?.trim()?.lowercase().orEmpty()

    private fun sideOf(recovery: IouRecovery): String =
        if (recovery == IouRecovery.FROM_SECONDARY_PAYERS) PAYER else PAYEE
}
