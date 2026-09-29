package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.IouDeclaration

/** Proposals and declarations between linked friends, and the chapter parts each declaration counted. */
@Dao
interface DeclarationDao {

    // --- declarations -------------------------------------------------------------------------

    /**
     * -1 when a row with this id is already here. That is how a proposal delivered twice, or one
     * arriving after the stub its own withdrawal left, is ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(declaration: IouDeclaration): Long

    @Update
    suspend fun update(declaration: IouDeclaration)

    @Query("SELECT * FROM iou_declarations WHERE id = :id")
    suspend fun get(id: String): IouDeclaration?

    @Query("SELECT * FROM iou_declarations WHERE friendId = :friendId ORDER BY proposedEpoch DESC, id DESC")
    suspend fun forFriend(friendId: Long): List<IouDeclaration>

    @Query("SELECT * FROM iou_declarations WHERE friendId IN (:friendIds)")
    suspend fun forFriends(friendIds: List<Long>): List<IouDeclaration>

    /** Every friend with at least one accepted declaration: the only ones a checkpoint can apply to. */
    @Query("SELECT DISTINCT friendId FROM iou_declarations WHERE state = 'ACCEPTED'")
    suspend fun friendIdsWithAccepted(): List<Long>

    /** Proposals this phone made that have not reached the other side yet. */
    @Query("SELECT * FROM iou_declarations WHERE proposedByMe = 1 AND state = 'OPEN' AND sentEpoch IS NULL ORDER BY proposedEpoch ASC")
    suspend fun unsent(): List<IouDeclaration>

    /** Open proposals the other side made, oldest first: what the inbox asks the user to answer. */
    @Query("SELECT * FROM iou_declarations WHERE proposedByMe = 0 AND state = 'OPEN' AND kind IS NOT NULL ORDER BY proposedEpoch ASC")
    suspend fun openIncoming(): List<IouDeclaration>

    @Query("SELECT COUNT(*) FROM iou_declarations WHERE proposedByMe = 0 AND state = 'OPEN' AND kind IS NOT NULL")
    suspend fun countOpenIncoming(): Int

    @Query("SELECT COUNT(*) FROM iou_declarations WHERE friendId = :friendId")
    suspend fun countForFriend(friendId: Long): Int

    /** D12: nobody can answer these any more. */
    @Query("UPDATE iou_declarations SET state = 'CLOSED', decidedEpoch = :now WHERE friendId = :friendId AND state = 'OPEN'")
    suspend fun closeOpen(friendId: Long, now: Long)

    /** D12: they keep anchoring the balance, but no proposal can change them. */
    @Query("UPDATE iou_declarations SET archived = 1 WHERE friendId = :friendId AND state = 'ACCEPTED'")
    suspend fun archiveAccepted(friendId: Long)

    @Query("DELETE FROM iou_declarations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE iou_declarations SET sentEpoch = :sentEpoch WHERE id = :id")
    suspend fun markSent(id: String, sentEpoch: Long)

    /** D12 for every friend at once: this phone's links are gone, for another account or for good. */
    @Query("UPDATE iou_declarations SET state = 'CLOSED', decidedEpoch = :now WHERE state = 'OPEN'")
    suspend fun closeAllOpen(now: Long)

    @Query("UPDATE iou_declarations SET archived = 1 WHERE state = 'ACCEPTED'")
    suspend fun archiveAllAccepted()

    /** For a friend merge. Parts follow by their declaration id. */
    @Query("UPDATE iou_declarations SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignFriend(sourceId: Long, targetId: Long)

    // --- parts --------------------------------------------------------------------------------

    @Insert
    suspend fun insertPart(part: DeclarationPart): Long

    @Insert
    suspend fun insertParts(parts: List<DeclarationPart>)

    @Update
    suspend fun updatePart(part: DeclarationPart)

    @Query("SELECT * FROM declaration_parts WHERE declarationId = :declarationId")
    suspend fun partsFor(declarationId: String): List<DeclarationPart>

    @Query("SELECT * FROM declaration_parts WHERE declarationId IN (:declarationIds)")
    suspend fun partsForAll(declarationIds: List<String>): List<DeclarationPart>

    @Query("SELECT * FROM declaration_parts WHERE declarationId = :declarationId AND chapterId = :chapterId")
    suspend fun partForChapter(declarationId: String, chapterId: Long): DeclarationPart?

    @Query("SELECT * FROM declaration_parts WHERE declarationId = :declarationId AND shareId = :shareId")
    suspend fun partForShare(declarationId: String, shareId: String): DeclarationPart?

    /** Every declaration that counted this chapter, so the friends whose opening depends on it can be replayed. */
    @Query("SELECT DISTINCT d.friendId FROM declaration_parts p INNER JOIN iou_declarations d ON d.id = p.declarationId WHERE p.chapterId = :chapterId")
    suspend fun friendIdsCountingChapter(chapterId: Long): List<Long>
}
