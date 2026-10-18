package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.varun.upitracker.database.entity.ChapterOutsider

/** Who each tagged transaction keeps outside its chapter. See [ChapterOutsider]. */
@Dao
interface ChapterOutsiderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(outsiders: List<ChapterOutsider>)

    @Query("SELECT friendId FROM chapter_outsiders WHERE transactionId = :transactionId")
    suspend fun forTransaction(transactionId: Long): List<Long>

    @Query("SELECT * FROM chapter_outsiders WHERE transactionId IN (:transactionIds)")
    suspend fun forTransactions(transactionIds: List<Long>): List<ChapterOutsider>

    @Query("DELETE FROM chapter_outsiders WHERE transactionId = :transactionId")
    suspend fun clear(transactionId: Long)

    @Query("SELECT COUNT(*) FROM chapter_outsiders WHERE friendId = :friendId")
    suspend fun countForFriend(friendId: Long): Int

    /** A merge: the target may already be outside the same row, which IGNORE leaves as it is. */
    @Query("UPDATE OR IGNORE chapter_outsiders SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignFriend(sourceId: Long, targetId: Long)
}

/** Replaces [transactionId]'s outsiders with [friendIds], inside the caller's transaction. */
suspend fun ChapterOutsiderDao.replace(transactionId: Long, friendIds: Set<Long>) {
    clear(transactionId)
    if (friendIds.isNotEmpty()) insertAll(friendIds.map { ChapterOutsider(transactionId, it) })
}
