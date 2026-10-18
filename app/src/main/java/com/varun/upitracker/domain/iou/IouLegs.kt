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
 * Part of a line that is a debt run through a go-between: [amountPaise] of it is really between the
 * go-between's other side and [farEnd]. "Bob owes you 500" routed from Bob's debt to Asha has Asha as
 * its far end; "You owe Asha 1000" carrying it has Bob.
 */
data class RoutedPart(val source: IouLegSource, val amountPaise: Long, val farEnd: IouParty)

/**
 * One "who owes whom" line: every leg straight between the same two people, folded -- or every one
 * that reaches them through a go-between, which is a line of its own. [kept] is whether all of them
 * are recorded, and [sources] are the share-row legs that turning the line on or off flips.
 *
 * [routed] says what a line through a go-between is passing on, and is empty on a straight one.
 * [routable] is whether a straight line could run through ME instead: it is between two people who
 * are not ME, and every leg in it comes from a share row, which is where the choice is kept.
 */
data class IouLine(
    val debtor: IouParty,
    val creditor: IouParty,
    val amountPaise: Long,
    val kept: Boolean,
    val sources: List<IouLegSource>,
    val routed: List<RoutedPart> = emptyList(),
    val routable: Boolean = false
)

/**
 * Who owes whom on one transaction, worked out the same way on every phone that holds it.
 *
 * Any leg may be routed through a go-between instead -- see [TransactionShare.payeeLegVia]. Bob owing
 * Asha 500, routed through ME, becomes Bob owing ME 500 and ME owing Asha 500: everyone ends up up or
 * down by exactly what they were, and Bob and Asha simply never owe each other. Both halves stand or
 * fall together, kept or left out as the leg is.
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

    /**
     * A candidate leg, whether or not the user kept it. [farEnd] is set on each half of a routed leg:
     * the party on the far side of the go-between.
     */
    private class Candidate(
        val leg: IouLeg,
        val source: IouLegSource?,
        val kept: Boolean,
        val farEnd: IouParty? = null
    )

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
        // Debts passed on through a go-between fold apart from the ones straight between the same two
        // people. One routed leg's halves sit in two lines, so leaving it out from either has to leave
        // the other's straight debts as they were -- which one pill folding both could not show.
        val groups = linkedMapOf<Pair<Set<IouParty>, Boolean>, MutableList<Candidate>>()
        candidates(payer, payee, shares, amountPaise, recovery)
            .filter { isPerson(it.leg.debtor) && isPerson(it.leg.creditor) }
            .forEach {
                groups.getOrPut(setOf(it.leg.debtor, it.leg.creditor) to (it.farEnd != null)) { mutableListOf() }.add(it)
            }

        return groups.values.mapNotNull { group ->
            val first = group.first().leg
            val net = group.fold(0L) { total, candidate ->
                total + if (candidate.leg.debtor == first.debtor) candidate.leg.amountPaise else -candidate.leg.amountPaise
            }
            val kept = group.all { it.kept }
            val sources = group.mapNotNull { it.source }
            val routed = group.mapNotNull { candidate ->
                val source = candidate.source ?: return@mapNotNull null
                val farEnd = candidate.farEnd ?: return@mapNotNull null
                RoutedPart(source, candidate.leg.amountPaise, farEnd)
            }
            val routable = first.debtor != IouParty.Me && first.creditor != IouParty.Me &&
                group.all { it.source != null && it.farEnd == null }
            when {
                net > 0L -> IouLine(first.debtor, first.creditor, net, kept, sources, routed, routable)
                net < 0L -> IouLine(first.creditor, first.debtor, -net, kept, sources, routed, routable)
                else -> null
            }
        }
    }

    /**
     * [shares] with every IOU that has someone in [outsiders] at one end, and anyone but ME or another
     * outsider at the other, set to run through ME -- so the outsiders owe and are owed by ME alone.
     * Null when one of those IOUs comes from no share row and so cannot be routed: the transaction's
     * whole amount owed straight from one end to the other.
     *
     * A line between two outsiders is left as it is: it is neither ME's business nor the chapter's.
     * Left-out IOUs are routed too, so turning one back on later does not bring the conflict back.
     */
    fun routeOutsidersThroughMe(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        recovery: IouRecovery,
        outsiders: Set<IouParty>
    ): List<TransactionShare>? {
        if (outsiders.isEmpty()) return shares
        if (directPaymentParties(payer, payee, shares, amountPaise)?.let { (a, b) -> crossesOutside(IouLeg(b, a, amountPaise), outsiders) } == true) {
            return null
        }
        val routed = shares.toMutableList()
        rawCandidates(payer, payee, shares, amountPaise, recovery).forEach { candidate ->
            val needsRoute = expand(candidate, viaOfSource(shares, candidate.source)).any { half ->
                crossesOutside(half.leg, outsiders)
            }
            if (!needsRoute) return@forEach
            val source = candidate.source ?: return null
            routed[source.shareIndex] = withVia(routed[source.shareIndex], source.owesPayer, IouParty.Me)
        }
        return routed
    }

    /**
     * Everyone in [outsiders] still owing or owed, on a kept IOU, by someone who is neither ME nor
     * outside. Empty is what lets a transaction keep them out of its chapter.
     */
    fun outsidersOwingInside(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        ledgerEffect: LedgerEffect,
        recovery: IouRecovery,
        outsiders: Set<IouParty>
    ): Set<IouParty> {
        if (outsiders.isEmpty() || ledgerEffect == LedgerEffect.NONE) return emptySet()
        val legs = directPaymentParties(payer, payee, shares, amountPaise)
            ?.let { (payerParty, payeeParty) -> listOf(IouLeg(payeeParty, payerParty, amountPaise)) }
            ?: legs(payer, payee, shares, amountPaise, ledgerEffect, recovery)
        return legs.filter { crossesOutside(it, outsiders) }
            .flatMap { listOf(it.debtor, it.creditor) }
            .filter { it in outsiders }
            .toSet()
    }

    /** One end outside, the other neither outside nor ME: a debt a chapter could not keep out. */
    private fun crossesOutside(leg: IouLeg, outsiders: Set<IouParty>): Boolean {
        if (!isPerson(leg.debtor) || !isPerson(leg.creditor)) return false
        val debtorOut = leg.debtor in outsiders
        val creditorOut = leg.creditor in outsiders
        if (debtorOut == creditorOut) return false
        val inside = if (debtorOut) leg.creditor else leg.debtor
        return inside != IouParty.Me
    }

    /** Who one of [share]'s two IOUs runs through, or null when it runs straight. */
    fun viaOf(share: TransactionShare, owesPayer: Boolean): IouParty? {
        val type = if (owesPayer) share.payerLegVia else share.payeeLegVia
        val friendId = if (owesPayer) share.payerLegViaFriendId else share.payeeLegViaFriendId
        return when (type) {
            ActorType.ME -> IouParty.Me
            ActorType.FRIEND -> friendId?.let { IouParty.Friend(it) }
            else -> null
        }
    }

    /** [share] with one of its IOUs set to run through [via], or straight again when null. */
    fun withVia(share: TransactionShare, owesPayer: Boolean, via: IouParty?): TransactionShare {
        val type = when (via) {
            IouParty.Me -> ActorType.ME
            is IouParty.Friend -> ActorType.FRIEND
            else -> null
        }
        val friendId = (via as? IouParty.Friend)?.friendId
        return if (owesPayer) {
            share.copy(payerLegVia = type, payerLegViaFriendId = friendId)
        } else {
            share.copy(payeeLegVia = type, payeeLegViaFriendId = friendId)
        }
    }

    /** Friends a debt on [shares] runs through. They are in the transaction as surely as anyone on it. */
    fun viaFriendIds(shares: List<TransactionShare>): Set<Long> =
        shares.flatMap { share -> listOfNotNull(viaOf(share, owesPayer = false), viaOf(share, owesPayer = true)) }
            .filterIsInstance<IouParty.Friend>()
            .map { it.friendId }
            .toSet()

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

    /**
     * The shape that settles up directly -- two ends and nothing split at all -- as (payer, payee),
     * or null when this is not that shape. Non-null means exactly one leg: the payee owes the payer
     * the whole amount.
     *
     * The test is `shares.isEmpty()`, deliberately, and not "no share carries a side". A legacy
     * sideless split says nothing about who owes what, so [legs] posts nothing for it -- and so must
     * anything claiming to agree with [legs]. Deriving it the other way would give a tagged
     * transaction a contribution the base ledger never had, and tagging it would silently move a
     * balance. See docs/chapters-design.md R13.
     *
     * [com.varun.upitracker.domain.transactionentry.persistence.LedgerPostingService] reads its two
     * settle-up branches off this, and [com.varun.upitracker.domain.chapter.ChapterMath] reads the
     * chapter's single leg off it, so the two cannot drift apart.
     */
    fun directPaymentParties(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long
    ): Pair<IouParty, IouParty>? {
        if (shares.isNotEmpty() || amountPaise <= 0L) return null
        val payerParty = partyOf(payer)
        val payeeParty = partyOf(payee)
        return if (payerParty == payeeParty) null else payerParty to payeeParty
    }

    fun partyOf(actor: ActorRef): IouParty = when (actor.actorType) {
        ActorType.ME -> IouParty.Me
        ActorType.FRIEND -> actor.friendId?.let { IouParty.Friend(it) } ?: IouParty.Person(actor.rawLabel)
        else -> IouParty.Counterparty(actor.rawLabel)
    }

    private fun isPerson(party: IouParty): Boolean = party !is IouParty.Counterparty

    /** Every candidate leg, each routed one already split into its two halves. */
    private fun candidates(
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long,
        recovery: IouRecovery
    ): List<Candidate> =
        rawCandidates(payer, payee, shares, amountPaise, recovery).flatMap { expand(it, viaOfSource(shares, it.source)) }

    private fun viaOfSource(shares: List<TransactionShare>, source: IouLegSource?): IouParty? =
        source?.let { viaOf(shares[it.shareIndex], it.owesPayer) }

    /**
     * [candidate] as it is recorded: as it stands, or as two halves through [via]. A go-between who is
     * already one of its two ends changes nothing, and neither can a shop at either end -- that is
     * spending, not a debt anyone could pass on.
     */
    private fun expand(candidate: Candidate, via: IouParty?): List<Candidate> {
        val leg = candidate.leg
        if (via == null || via == leg.debtor || via == leg.creditor) return listOf(candidate)
        if (!isPerson(leg.debtor) || !isPerson(leg.creditor)) return listOf(candidate)
        return listOf(
            Candidate(IouLeg(leg.debtor, via, leg.amountPaise), candidate.source, candidate.kept, farEnd = leg.creditor),
            Candidate(IouLeg(via, leg.creditor, leg.amountPaise), candidate.source, candidate.kept, farEnd = leg.debtor)
        )
    }

    /** Every candidate leg as the split describes it, before any is routed. */
    private fun rawCandidates(
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
