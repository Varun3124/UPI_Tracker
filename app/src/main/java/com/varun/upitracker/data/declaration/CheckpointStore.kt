package com.varun.upitracker.data.declaration

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.declaration.Checkpoints
import com.varun.upitracker.domain.declaration.DeclarationSet
import com.varun.upitracker.domain.declaration.Opening

/**
 * Reads each friend's checkpoint, and keeps the chapter parts of one up to date.
 *
 * Plain suspend calls with no transaction of their own and no dispatcher switch, like the
 * `…InTransaction` half of [com.varun.upitracker.data.repository.ChapterRepository]: everything here
 * runs inside a save, a replay or a recompute that already holds one.
 */
class CheckpointStore(private val db: AppDatabase) {

    suspend fun effective(friendId: Long): IouDeclaration? =
        DeclarationSet.effective(db.declarationDao().forFriend(friendId))

    /** Each friend's checkpoint, for those of [friendIds] that have one. */
    suspend fun effective(friendIds: Collection<Long>): Map<Long, IouDeclaration> {
        if (friendIds.isEmpty()) return emptyMap()
        return DeclarationSet.effectiveByFriend(db.declarationDao().forFriends(friendIds.distinct()))
    }

    /** The instant each friend's checkpoint covers up to, for those that have one. */
    suspend fun asOfByFriend(friendIds: Collection<Long>): Map<Long, Long> =
        effective(friendIds).mapValues { requireNotNull(it.value.asOfEpoch) }

    /** Which of [friendIds] a row dated [dateEpoch] no longer moves (D8). */
    suspend fun sealedFriends(friendIds: Collection<Long>, dateEpoch: Long): Set<Long> =
        Checkpoints.sealed(friendIds, dateEpoch, asOfByFriend(friendIds))

    /** The opening entry each friend's checkpoint becomes. */
    suspend fun openings(friendIds: Collection<Long>): Map<Long, Opening> {
        val effective = effective(friendIds)
        if (effective.isEmpty()) return emptyMap()
        val parts = db.declarationDao()
            .partsForAll(effective.values.map { it.id })
            .groupBy { it.declarationId }
        return effective.mapValues { (_, declaration) ->
            Checkpoints.openingOf(declaration, parts[declaration.id].orEmpty())
        }
    }

    /**
     * D9: counts [deltaPaise] of [chapterId]'s share towards [friendId]'s checkpoint, so the change it
     * came from does not move their balance. False when they have no checkpoint to count it in.
     *
     * The caller replays the friend afterwards; the opening only follows on a replay.
     */
    suspend fun absorb(friendId: Long, chapterId: Long, shareId: String?, deltaPaise: Long): Boolean {
        if (deltaPaise == 0L) return false
        val checkpoint = effective(friendId) ?: return false
        val part = db.declarationDao().partForChapter(checkpoint.id, chapterId)
        if (part == null) {
            db.declarationDao().insertPart(
                DeclarationPart(
                    declarationId = checkpoint.id,
                    chapterId = chapterId,
                    shareId = shareId,
                    amountPaise = deltaPaise
                )
            )
        } else {
            db.declarationDao().updatePart(part.copy(amountPaise = part.amountPaise + deltaPaise))
        }
        return true
    }
}
