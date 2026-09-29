package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.IouDeclaration

/**
 * A friend's checkpoint, reduced to what their base ledger needs: the instant it covers up to, and
 * the opening entry it becomes.
 */
data class Opening(
    val declarationId: String,
    val friendId: Long,
    val asOfEpoch: Long,
    /** Positive means the friend owes ME. See [Checkpoints.openingAmount]. */
    val amountPaise: Long
)

/**
 * What a checkpoint does to a friend's base ledger. See docs/declarations-design.md D7 and D8.
 *
 * A checkpoint replaces everything before it with one agreed figure. That figure covers the whole
 * balance, chapters included, but chapters stay live and keep writing their own contributions. So the
 * base ledger opens with the figure *minus* the chapter shares it included, and a chapter only moves
 * the balance by what has changed in it since.
 */
object Checkpoints {

    /**
     * The opening entry: the agreed amount minus the parts of chapters this phone holds.
     *
     * A part whose chapter has not reached this phone yet is left out. That chapter is not
     * contributing here yet either, and subtracting its share now would leave a hole until it
     * arrived.
     */
    fun openingAmount(declaration: IouDeclaration, parts: Collection<DeclarationPart>): Long {
        val amount = requireNotNull(declaration.amountPaise) { "Only a declaration with an amount opens a ledger." }
        return amount - parts.filter { it.chapterId != null }.sumOf { it.amountPaise }
    }

    fun openingOf(declaration: IouDeclaration, parts: Collection<DeclarationPart>): Opening = Opening(
        declarationId = declaration.id,
        friendId = declaration.friendId,
        asOfEpoch = requireNotNull(declaration.asOfEpoch) { "Only a declaration with an instant opens a ledger." },
        amountPaise = openingAmount(declaration, parts)
    )

    /**
     * D8: a row dated at or before the checkpoint is covered by it, however late it was recorded.
     * Inclusive, like `AccountRepository.getBalance`'s snapshot: the checkpoint speaks for the instant
     * it names.
     */
    fun isSealed(dateEpoch: Long, asOfEpoch: Long?): Boolean = asOfEpoch != null && dateEpoch <= asOfEpoch

    /** Which of [friendIds] a row dated [dateEpoch] still moves. */
    fun unsealed(friendIds: Collection<Long>, dateEpoch: Long, asOfByFriend: Map<Long, Long>): Set<Long> =
        friendIds.filterTo(linkedSetOf()) { !isSealed(dateEpoch, asOfByFriend[it]) }

    /** Which of [friendIds] a row dated [dateEpoch] no longer moves. */
    fun sealed(friendIds: Collection<Long>, dateEpoch: Long, asOfByFriend: Map<Long, Long>): Set<Long> =
        friendIds.filterTo(linkedSetOf()) { isSealed(dateEpoch, asOfByFriend[it]) }

    /**
     * The date a replay of [friendIds] can start after: every row at or before it is sealed for all of
     * them. Null when any of them has no checkpoint, since then every row counts for someone.
     */
    fun replayFloor(friendIds: Collection<Long>, asOfByFriend: Map<Long, Long>): Long? {
        if (friendIds.isEmpty()) return null
        var floor = Long.MAX_VALUE
        friendIds.forEach { friendId ->
            val asOf = asOfByFriend[friendId] ?: return null
            if (asOf < floor) floor = asOf
        }
        return floor
    }
}
