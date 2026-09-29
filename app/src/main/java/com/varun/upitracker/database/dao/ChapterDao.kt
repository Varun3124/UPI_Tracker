package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterBalance
import com.varun.upitracker.database.entity.ChapterMember
import com.varun.upitracker.database.entity.ChapterPerson
import com.varun.upitracker.database.entity.ChapterShare
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Transaction

/** One chapter's contribution to a friend's balance, with enough of the chapter to label the row. */
data class FriendChapterBalanceRow(
    val chapterId: Long,
    val name: String,
    val state: ChapterState,
    val amountPaise: Long
)

/** A chapter a friend belongs to, whether or not they come out owing anything. */
data class FriendChapterMembershipRow(
    val chapterId: Long,
    val name: String,
    val state: ChapterState,
    val amountPaise: Long?
)

/** Chapters, their members, and the balances their plans feed back into the base ledger. */
@Dao
interface ChapterDao {

    // --- chapters -----------------------------------------------------------------------------

    @Insert
    suspend fun insert(chapter: Chapter): Long

    @Update
    suspend fun update(chapter: Chapter)

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getById(chapterId: Long): Chapter?

    @Query("SELECT * FROM chapters ORDER BY createdEpoch DESC, id DESC")
    suspend fun getAll(): List<Chapter>

    @Query("SELECT * FROM chapters WHERE state = 'OPEN' ORDER BY createdEpoch DESC, id DESC")
    suspend fun getOpen(): List<Chapter>

    @Query("SELECT * FROM chapters WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): Chapter?

    // --- sharing ------------------------------------------------------------------------------

    @Query("SELECT * FROM chapters WHERE shareId = :shareId LIMIT 1")
    suspend fun getByShareId(shareId: String): Chapter?

    /** This phone's own chapters that go to their members after each change. */
    @Query("SELECT * FROM chapters WHERE ownerFriendId IS NULL AND shareMode = 'SHARED'")
    suspend fun sharedByMe(): List<Chapter>

    /** Copies of [friendId]'s chapters held here, live or not. */
    @Query("SELECT * FROM chapters WHERE ownerFriendId = :friendId")
    suspend fun copiesOf(friendId: Long): List<Chapter>

    /**
     * Every change a member's copy would show is counted here, so the phone knows who is behind.
     * Counted on a chapter that is not shared too: a copy pasted to someone later has to read as newer
     * than the one they were pasted before. A copy of a friend's chapter holds their count, not ours.
     */
    @Query("UPDATE chapters SET shareVersion = shareVersion + 1 WHERE id = :chapterId AND ownerFriendId IS NULL")
    suspend fun bumpShareVersion(chapterId: Long)

    /** An agreement with [friendId] changed, and with it the hint every chapter shared with them carries (S8). */
    @Query(
        """
        UPDATE chapters SET shareVersion = shareVersion + 1
        WHERE ownerFriendId IS NULL AND shareMode = 'SHARED'
          AND id IN (SELECT chapterId FROM chapter_members WHERE friendId = :friendId)
        """
    )
    suspend fun bumpSharedChaptersWith(friendId: Long)

    /** S7: the two unlinked, so [friendId]'s copies stop updating -- and keep counting. */
    @Query("UPDATE chapters SET shareMode = 'FROZEN' WHERE ownerFriendId = :friendId AND shareMode = 'REPLICA'")
    suspend fun freezeCopiesOf(friendId: Long)

    /** S7 for every friend at once: this phone's links are gone. */
    @Query("UPDATE chapters SET shareMode = 'FROZEN' WHERE shareMode = 'REPLICA'")
    suspend fun freezeAllCopies()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShare(share: ChapterShare)

    @Query("SELECT * FROM chapter_shares WHERE chapterId = :chapterId")
    suspend fun sharesFor(chapterId: Long): List<ChapterShare>

    @Query("DELETE FROM chapter_shares WHERE chapterId = :chapterId")
    suspend fun deleteShares(chapterId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: ChapterPerson)

    @Query("SELECT * FROM chapter_people WHERE chapterId = :chapterId")
    suspend fun peopleFor(chapterId: Long): List<ChapterPerson>

    @Query("DELETE FROM chapter_people WHERE chapterId = :chapterId AND personKey = :personKey")
    suspend fun deletePerson(chapterId: Long, personKey: String)

    /** A copy's members are whoever its snapshot names that this phone can place; rebuilt with it. */
    @Query("DELETE FROM chapter_members WHERE chapterId = :chapterId")
    suspend fun deleteMembers(chapterId: Long)

    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun deleteById(chapterId: Long)

    /**
     * R10, in one statement: at most one chapter is active, and only an open one. Passing an id that
     * is closed -- or 0, which no row has -- simply clears the flag everywhere.
     */
    @Query("UPDATE chapters SET isActive = (id = :chapterId AND state = 'OPEN')")
    suspend fun setActive(chapterId: Long)

    // --- members ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<ChapterMember>)

    @Query("SELECT friendId FROM chapter_members WHERE chapterId = :chapterId")
    suspend fun memberIds(chapterId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM chapter_members WHERE chapterId = :chapterId")
    suspend fun memberCount(chapterId: Long): Int

    @Query("DELETE FROM chapter_members WHERE chapterId = :chapterId AND friendId = :friendId")
    suspend fun removeMember(chapterId: Long, friendId: Long)

    @Query("SELECT COUNT(*) FROM chapter_members WHERE friendId = :friendId")
    suspend fun countMembershipsForFriend(friendId: Long): Int

    @Query("SELECT DISTINCT chapterId FROM chapter_members WHERE friendId IN (:friendIds)")
    suspend fun chapterIdsForFriends(friendIds: List<Long>): List<Long>

    // --- friend merge -------------------------------------------------------------------------

    /**
     * The composite primary key collides wherever both friends are already members, so the source's
     * duplicate rows go first and only the rows with no counterpart are moved. Run in this order.
     */
    @Query(
        """
        DELETE FROM chapter_members
        WHERE friendId = :sourceId
          AND chapterId IN (SELECT chapterId FROM chapter_members WHERE friendId = :targetId)
        """
    )
    suspend fun dropDuplicateMemberships(sourceId: Long, targetId: Long)

    @Query("UPDATE chapter_members SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignMemberships(sourceId: Long, targetId: Long)

    @Query("UPDATE chapters SET ownerFriendId = :targetId WHERE ownerFriendId = :sourceId")
    suspend fun reassignOwner(sourceId: Long, targetId: Long)

    /** As [dropDuplicateMemberships], for the owner's record of who a chapter went to. */
    @Query(
        """
        DELETE FROM chapter_shares
        WHERE friendId = :sourceId
          AND chapterId IN (SELECT chapterId FROM chapter_shares WHERE friendId = :targetId)
        """
    )
    suspend fun dropDuplicateShares(sourceId: Long, targetId: Long)

    @Query("UPDATE chapter_shares SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignShares(sourceId: Long, targetId: Long)

    @Query("UPDATE chapter_people SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignPeople(sourceId: Long, targetId: Long)

    // --- balances -----------------------------------------------------------------------------

    @Insert
    suspend fun insertBalances(balances: List<ChapterBalance>)

    @Query("DELETE FROM chapter_balances WHERE chapterId = :chapterId")
    suspend fun deleteBalancesForChapter(chapterId: Long)

    @Query("DELETE FROM chapter_balances WHERE friendId = :friendId")
    suspend fun deleteBalancesForFriend(friendId: Long)

    @Query("SELECT * FROM chapter_balances WHERE chapterId = :chapterId")
    suspend fun balancesForChapter(chapterId: Long): List<ChapterBalance>

    /** Every friend any chapter's plan currently moves. The other half of getAllSummaries. */
    @Query("SELECT DISTINCT friendId FROM chapter_balances")
    suspend fun friendIdsWithBalance(): List<Long>

    @Query(
        """
        SELECT b.chapterId AS chapterId, c.name AS name, c.state AS state, b.amountPaise AS amountPaise
        FROM chapter_balances b
        INNER JOIN chapters c ON c.id = b.chapterId
        WHERE b.friendId = :friendId
        ORDER BY c.createdEpoch DESC, c.id DESC
        """
    )
    suspend fun balancesForFriend(friendId: Long): List<FriendChapterBalanceRow>

    /**
     * Every chapter a friend is in, with their contribution or null when they come out even.
     *
     * The friend page needs the even ones too, and `chapter_balances` only holds non-zero rows -- so
     * this has to start from the membership, not the balance.
     */
    @Query(
        """
        SELECT m.chapterId AS chapterId, c.name AS name, c.state AS state, b.amountPaise AS amountPaise
        FROM chapter_members m
        INNER JOIN chapters c ON c.id = m.chapterId
        LEFT JOIN chapter_balances b ON b.chapterId = m.chapterId AND b.friendId = m.friendId
        WHERE m.friendId = :friendId
        ORDER BY c.createdEpoch DESC, c.id DESC
        """
    )
    suspend fun membershipsForFriend(friendId: Long): List<FriendChapterMembershipRow>

    // --- tagged transactions ------------------------------------------------------------------

    @Query("SELECT * FROM transactions WHERE chapterId = :chapterId ORDER BY dateEpoch DESC, id DESC")
    suspend fun taggedTransactions(chapterId: Long): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE chapterId = :chapterId")
    suspend fun countTaggedTransactions(chapterId: Long): Int

    /**
     * How many of a chapter's transactions involve one friend. R7's "Dan is in 3 transactions here".
     *
     * Sided shares only: a legacy sideless share produces no legs, so it cannot be why someone
     * belongs here.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT t.id) FROM transactions t
        LEFT JOIN transaction_shares s
            ON s.transactionId = t.id AND s.friendId = :friendId AND s.side IS NOT NULL
        WHERE t.chapterId = :chapterId
          AND (t.payerFriendId = :friendId OR t.payeeFriendId = :friendId OR s.friendId = :friendId)
        """
    )
    suspend fun countTaggedInvolving(chapterId: Long, friendId: Long): Int

    /**
     * The newest tagged transaction a friend appears in.
     *
     * Once a friend's whole history is tagged they own no `iou_entries` at all, and
     * [IouDao.getLastActivityEpoch] -- which reads exactly that table -- returns null. Without this
     * the friend page would lose its date the moment a chapter took over.
     */
    @Query(
        """
        SELECT MAX(t.dateEpoch) FROM transactions t
        LEFT JOIN transaction_shares s
            ON s.transactionId = t.id AND s.friendId = :friendId AND s.side IS NOT NULL
        WHERE t.chapterId IS NOT NULL
          AND (t.payerFriendId = :friendId OR t.payeeFriendId = :friendId OR s.friendId = :friendId)
        """
    )
    suspend fun lastTaggedActivityEpoch(friendId: Long): Long?
}
