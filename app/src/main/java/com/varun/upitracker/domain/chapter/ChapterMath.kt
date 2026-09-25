package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLeg
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef

/** A transaction tagged to a chapter, with the share rows that describe its split. */
data class TaggedTx(val transaction: Transaction, val shares: List<TransactionShare>)

/** [debtor] owes [creditor], between two people a chapter can hold a balance for. */
data class ChapterLeg(val debtor: ChapterParty, val creditor: ChapterParty, val amountPaise: Long)

/** One payment in a chapter's plan: [debtor] hands [creditor] [amountPaise] and they are square. */
data class ChapterPayment(val debtor: ChapterParty, val creditor: ChapterParty, val amountPaise: Long)

/** Everything a chapter works out from its own transactions. */
data class ChapterResult(
    /** Non-zero only, in [CHAPTER_PARTY_ORDER]. Positive means they are owed. Sums to zero. */
    val nets: Map<ChapterParty, Long>,

    /** The fewest payments that zero every net. */
    val plan: List<ChapterPayment>,

    /** What the plan does to each friend's balance with ME. Positive means they owe ME. Non-zero only. */
    val contributions: Map<Long, Long>,

    /** Tagged transactions still awaiting review. They count for nothing until reviewed. */
    val pendingCount: Int,

    /** Every net is zero and nothing is pending. Derived, never stored. */
    val settled: Boolean,

    /**
     * Legs thrown away because someone in them is unidentified.
     *
     * Should always be zero: [ChapterEligibility] refuses to tag a transaction naming anyone this
     * database cannot place. A non-zero count means a row got in another way -- a friend deleted
     * from under it, say -- and the chapter is quietly under-counting.
     */
    val unidentifiedLegCount: Int
)

/**
 * What a chapter's transactions say about who owes whom, and the fewest payments that settle it.
 *
 * A chapter works out its own nets from its own transactions only, then reduces them to a plan. The
 * plan's payments that involve ME become each friend's [ChapterResult.contributions], which is the
 * only way a tagged transaction reaches the base ledger -- see docs/chapters-design.md R15.
 *
 * Simplification is why a chapter can say "Dan pays you 700" when the two never transacted. The
 * plan is recomputed from scratch after every change, so it must be a pure function of the
 * transactions and nothing else: no insertion order, no iteration order, no clock.
 */
object ChapterMath {

    private const val PAYER = "PAYER"
    private const val PAYEE = "PAYEE"

    fun compute(transactions: List<TaggedTx>): ChapterResult {
        var unidentified = 0
        val legs = mutableListOf<ChapterLeg>()
        transactions.forEach { tagged ->
            rawLegsFor(tagged).forEach { leg ->
                val debtor = leg.debtor.toChapterParty()
                val creditor = leg.creditor.toChapterParty()
                if (debtor == null || creditor == null) {
                    // A shop at one end is spending, not an IOU, and never counted as a loss.
                    if (leg.debtor is IouParty.Person || leg.creditor is IouParty.Person) unidentified++
                } else if (debtor != creditor && leg.amountPaise > 0L) {
                    legs += ChapterLeg(debtor, creditor, leg.amountPaise)
                }
            }
        }

        // An unordered set of unordered pairs: order-independent by construction, which is what
        // makes the tie-breaks below immune to the order the transactions arrived in.
        val transacted: Set<Set<ChapterParty>> = legs.map { setOf(it.debtor, it.creditor) }.toSet()

        val totals = mutableMapOf<ChapterParty, Long>()
        legs.forEach { leg ->
            totals[leg.creditor] = (totals[leg.creditor] ?: 0L) + leg.amountPaise
            totals[leg.debtor] = (totals[leg.debtor] ?: 0L) - leg.amountPaise
        }
        val order = totals.keys.filter { totals.getValue(it) != 0L }.sortedWith(CHAPTER_PARTY_ORDER)
        val nets = linkedMapOf<ChapterParty, Long>()
        order.forEach { nets[it] = totals.getValue(it) }

        val plan = simplify(nets, order, transacted)

        val contributions = linkedMapOf<Long, Long>()
        plan.forEach { payment ->
            val debtor = payment.debtor
            val creditor = payment.creditor
            if (debtor is ChapterParty.Friend && creditor is ChapterParty.Me) {
                contributions[debtor.friendId] = (contributions[debtor.friendId] ?: 0L) + payment.amountPaise
            } else if (debtor is ChapterParty.Me && creditor is ChapterParty.Friend) {
                contributions[creditor.friendId] = (contributions[creditor.friendId] ?: 0L) - payment.amountPaise
            }
        }

        val pendingCount = transactions.count { it.transaction.isPending }
        return ChapterResult(
            nets = nets,
            plan = plan,
            contributions = contributions.filterValues { it != 0L },
            pendingCount = pendingCount,
            settled = nets.isEmpty() && pendingCount == 0,
            unidentifiedLegCount = unidentified
        )
    }

    /**
     * Every friend a transaction names: either end, or a sided share row.
     *
     * Feeds R5's "add everyone in it to the chapter" and the set of friends whose base ledger a
     * tag or untag has to replay. Sided rows only -- a legacy sideless share produces no legs, so
     * nobody is in the transaction by virtue of one.
     */
    fun friendsIn(tx: Transaction, shares: List<TransactionShare>): Set<Long> {
        val friends = linkedSetOf<Long>()
        if (tx.payerActorType == ActorType.FRIEND) tx.payerFriendId?.let { friends += it }
        if (tx.payeeActorType == ActorType.FRIEND) tx.payeeFriendId?.let { friends += it }
        shares.forEach { share ->
            if (share.side == PAYER || share.side == PAYEE) {
                if (share.participantType == ActorType.FRIEND) share.friendId?.let { friends += it }
            }
        }
        return friends
    }

    /**
     * R12 and R13: the legs one tagged transaction contributes, still in [IouParty].
     *
     * Pending rows and gifts count for nothing, exactly as they do in the base ledger. A direct
     * payment -- two ends and nothing split -- is the one shape [IouLegs.legs] deliberately says
     * nothing about, so its single leg is written here; for ME and a friend that reproduces what
     * `applyRepayment` / `applyOutgoingSettlement` do to the net, and for two friends it is the
     * genuinely new case a chapter exists to record.
     */
    private fun rawLegsFor(tagged: TaggedTx): List<IouLeg> {
        val tx = tagged.transaction
        if (tx.isPending || tx.ledgerEffect == LedgerEffect.NONE) return emptyList()

        val payer = tx.payerActorRef()
        val payee = tx.payeeActorRef()
        val direct = IouLegs.directPaymentParties(payer, payee, tagged.shares, tx.amountPaise)
        if (direct != null) {
            val (payerParty, payeeParty) = direct
            return listOf(IouLeg(payeeParty, payerParty, tx.amountPaise))
        }
        return IouLegs.legs(
            payer,
            payee,
            tagged.shares,
            tx.amountPaise,
            tx.ledgerEffect,
            IouLegs.resolve(tx, tagged.shares)
        )
    }

    /**
     * The fewest payments that zero every net, reached the same way every time.
     *
     * Exact matches go first: two parties whose amounts cancel exactly are always worth pairing,
     * and doing so can only reduce the count. What is left is settled greedily, largest debt first.
     * Every round zeroes at least one party and a zeroed party never comes back, so the plan is at
     * most one payment shorter than the number of parties, and no pair is ever paid twice.
     *
     * Greedy is not always the true minimum -- that needs a subset search -- but it is within one or
     * two payments on group sizes that occur, and it is stable, which matters more: the plan is
     * recomputed live, and a plan that reshuffles on every edit is worse than a plan one payment
     * longer. Preferring pairs who actually transacted keeps it recognisable.
     */
    private fun simplify(
        nets: Map<ChapterParty, Long>,
        order: List<ChapterParty>,
        transacted: Set<Set<ChapterParty>>
    ): List<ChapterPayment> {
        val remaining = linkedMapOf<ChapterParty, Long>()
        order.forEach { remaining[it] = nets.getValue(it) }
        val rank = order.withIndex().associate { (index, party) -> party to index }
        fun rankOf(party: ChapterParty): Int = rank[party] ?: Int.MAX_VALUE
        fun transacted(a: ChapterParty, b: ChapterParty): Boolean = setOf(a, b) in transacted

        val plan = mutableListOf<ChapterPayment>()

        // Never iterate `remaining` to make a choice: re-derive both sides from `order` each round,
        // so nothing depends on the order legs happened to arrive in.
        fun debtors() = order.filter { (remaining[it] ?: 0L) < 0L }
        fun creditors() = order.filter { (remaining[it] ?: 0L) > 0L }

        while (true) {
            val pair = debtors()
                .flatMap { debtor ->
                    creditors()
                        .filter { -remaining.getValue(debtor) == remaining.getValue(it) }
                        .map { debtor to it }
                }
                .minWithOrNull(
                    compareBy(
                        { if (transacted(it.first, it.second)) 0 else 1 },
                        { if (it.first is ChapterParty.Me || it.second is ChapterParty.Me) 0 else 1 },
                        { rankOf(it.first) },
                        { rankOf(it.second) }
                    )
                ) ?: break
            plan += ChapterPayment(pair.first, pair.second, remaining.getValue(pair.second))
            remaining.remove(pair.first)
            remaining.remove(pair.second)
        }

        while (true) {
            val debtor = debtors().minWithOrNull(
                compareBy({ remaining.getValue(it) }, { rankOf(it) })
            ) ?: break
            val need = -remaining.getValue(debtor)
            val creditor = creditors().minWithOrNull(
                compareBy(
                    { if (remaining.getValue(it) == need) 0 else 1 },
                    { if (transacted(debtor, it)) 0 else 1 },
                    { -remaining.getValue(it) },
                    { rankOf(it) }
                )
            ) ?: break

            val paid = minOf(need, remaining.getValue(creditor))
            plan += ChapterPayment(debtor, creditor, paid)
            remaining[debtor] = remaining.getValue(debtor) + paid
            remaining[creditor] = remaining.getValue(creditor) - paid
            if (remaining.getValue(debtor) == 0L) remaining.remove(debtor)
            if (remaining.getValue(creditor) == 0L) remaining.remove(creditor)
        }

        return plan
    }
}
