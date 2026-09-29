package com.varun.upitracker.domain.chapter

import com.varun.upitracker.domain.parcel.ParcelActor

/** Who one of a shared chapter's people is on this phone. */
sealed interface ReplicaParty {

    data object Me : ReplicaParty

    data class Friend(val friendId: Long) : ReplicaParty

    /** Nobody here yet. Named only, until the user says who they are (S4). */
    data class Unresolved(val key: String, val name: String) : ReplicaParty
}

/** A payment between ME and someone this phone cannot place: shown, but not counted. */
data class UnresolvedPayment(
    val person: ReplicaParty.Unresolved,
    /** Positive when they owe ME. */
    val amountPaise: Long
)

/** What a copy's plan does here. */
data class ReplicaBalances(
    /** Each friend's contribution: positive means they owe ME, the sign `chapter_balances` uses. Non-zero only. */
    val contributions: Map<Long, Long>,
    val unresolved: List<UnresolvedPayment>
)

/**
 * A copy of someone else's chapter, turned into this phone's balances. See docs/declarations-design.md S4.
 *
 * Only the plan's payments between ME and someone this phone can place move anything here. A payment
 * between two other people is theirs to count; a payment between ME and someone nobody has placed yet
 * waits until the user says who they are -- a matching name is never enough, for the reason
 * [com.varun.upitracker.database.entity.TransactionShare.rawLabel] gives.
 */
object ReplicaMath {

    /** The key a hand-made mapping is stored under, or null for the two roles that need none. */
    fun personKey(actor: ParcelActor): String? = when (actor) {
        is ParcelActor.Linked -> "uid:${actor.uid}"
        is ParcelActor.Person -> nameKey(actor.name)
        else -> null
    }

    private fun nameKey(name: String): String = "name:${name.trim().lowercase()}"

    /**
     * [ownerFriendId] is who the owner is here. [linkedFriendOf] maps an account to this phone's own
     * friend linked with it -- linking was an explicit act by both, which is what makes it enough.
     * [mapped] holds the user's own decisions, by [personKey].
     */
    fun resolve(
        actor: ParcelActor,
        ownerFriendId: Long,
        linkedFriendOf: (String) -> Long?,
        mapped: Map<String, Long>
    ): ReplicaParty = when (actor) {
        ParcelActor.Me -> ReplicaParty.Me
        ParcelActor.Sender -> ReplicaParty.Friend(ownerFriendId)
        is ParcelActor.Linked -> (linkedFriendOf(actor.uid) ?: mapped["uid:${actor.uid}"] ?: mapped[nameKey(actor.name)])
            ?.let { ReplicaParty.Friend(it) }
            ?: ReplicaParty.Unresolved("uid:${actor.uid}", actor.name)
        is ParcelActor.Person -> mapped[nameKey(actor.name)]?.let { ReplicaParty.Friend(it) }
            ?: ReplicaParty.Unresolved(nameKey(actor.name), actor.name)
        is ParcelActor.Shop -> ReplicaParty.Unresolved("shop:${actor.name}", actor.name)
        is ParcelActor.Unnamed -> ReplicaParty.Unresolved("unnamed:${actor.label}", actor.label)
    }

    fun balances(plan: List<SnapshotPayment>, resolve: (ParcelActor) -> ReplicaParty): ReplicaBalances {
        val contributions = linkedMapOf<Long, Long>()
        val unresolved = mutableListOf<UnresolvedPayment>()
        plan.forEach { payment ->
            val debtor = resolve(payment.debtor)
            val creditor = resolve(payment.creditor)
            when {
                debtor == creditor -> Unit
                debtor is ReplicaParty.Friend && creditor is ReplicaParty.Me ->
                    contributions[debtor.friendId] = (contributions[debtor.friendId] ?: 0L) + payment.amountPaise
                debtor is ReplicaParty.Me && creditor is ReplicaParty.Friend ->
                    contributions[creditor.friendId] = (contributions[creditor.friendId] ?: 0L) - payment.amountPaise
                debtor is ReplicaParty.Unresolved && creditor is ReplicaParty.Me ->
                    unresolved += UnresolvedPayment(debtor, payment.amountPaise)
                debtor is ReplicaParty.Me && creditor is ReplicaParty.Unresolved ->
                    unresolved += UnresolvedPayment(creditor, -payment.amountPaise)
            }
        }
        return ReplicaBalances(contributions.filterValues { it != 0L }, unresolved)
    }
}
