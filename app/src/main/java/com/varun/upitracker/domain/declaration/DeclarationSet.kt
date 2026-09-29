package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration

/**
 * Which of a pair's declarations are in force, and which one is the checkpoint.
 *
 * The two phones only ever exchange proposals and answers, delivered in whatever order the mailbox
 * hands them over. Their books stay the same because what they derive from those messages does not
 * depend on the order: the accepted proposals form a two-phase set. A declaration id is **added**
 * once (by an accepted DECLARE or AMEND) and **removed** at most once (by an accepted AMEND's or
 * REVOKE's target), and a removal is permanent -- a removal seen before the add still wins. Both
 * phones end up with the same set whatever order they learnt of it in. See
 * docs/declarations-design.md D4 and D5.
 */
object DeclarationSet {

    /** Declarations in force: accepted adds whose id no accepted proposal has removed. Archived ones included. */
    fun live(rows: Collection<IouDeclaration>): List<IouDeclaration> {
        val accepted = rows.filter { it.state == DeclarationState.ACCEPTED }
        val removed = accepted
            .filter { it.kind == DeclarationKind.AMEND || it.kind == DeclarationKind.REVOKE }
            .mapNotNull { it.targetId }
            .toSet()
        return accepted.filter { isAdd(it) && it.id !in removed }
    }

    /**
     * The checkpoint: the live declaration with the greatest `(asOfEpoch, id)`.
     *
     * The id breaks a tie because both phones hold it: two declarations for the same instant --
     * an amendment race, say -- must resolve the same way on both, and nothing else they share would
     * decide it.
     */
    fun effective(rows: Collection<IouDeclaration>): IouDeclaration? =
        live(rows).maxWithOrNull(CHECKPOINT_ORDER)

    /** Every friend's checkpoint, from a mixed list of rows. */
    fun effectiveByFriend(rows: Collection<IouDeclaration>): Map<Long, IouDeclaration> =
        rows.groupBy { it.friendId }
            .mapNotNull { (friendId, own) -> effective(own)?.let { friendId to it } }
            .toMap()

    /** Whether [row], once accepted, adds a declaration to the set rather than only removing one. */
    fun isAdd(row: IouDeclaration): Boolean =
        (row.kind == DeclarationKind.DECLARE || row.kind == DeclarationKind.AMEND) &&
            row.asOfEpoch != null && row.amountPaise != null

    private val CHECKPOINT_ORDER: Comparator<IouDeclaration> =
        compareBy<IouDeclaration>({ it.asOfEpoch ?: Long.MIN_VALUE }, { it.id })
}
