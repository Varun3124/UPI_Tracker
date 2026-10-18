package com.varun.upitracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.varun.upitracker.database.entity.EntryDraft
import com.varun.upitracker.database.entity.EntryDraftCategory
import com.varun.upitracker.database.entity.EntryDraftRow

/**
 * Unfinished entry forms, one per pending row. Written whole by
 * [com.varun.upitracker.data.repository.EntryDraftRepository] and read back whole by the entry
 * screen; a merge is the only thing that edits one in place.
 */
@Dao
interface EntryDraftDao {

    @Insert
    suspend fun insert(draft: EntryDraft)

    @Insert
    suspend fun insertRows(rows: List<EntryDraftRow>)

    @Insert
    suspend fun insertCategories(categories: List<EntryDraftCategory>)

    @Query("SELECT * FROM entry_drafts WHERE transactionId = :transactionId")
    suspend fun get(transactionId: Long): EntryDraft?

    @Query("SELECT * FROM entry_draft_rows WHERE transactionId = :transactionId ORDER BY side, position, id")
    suspend fun getRows(transactionId: Long): List<EntryDraftRow>

    @Query("SELECT * FROM entry_draft_categories WHERE transactionId = :transactionId")
    suspend fun getCategories(transactionId: Long): List<EntryDraftCategory>

    // Children first and by hand, so clearing a draft never depends on the foreign-keys pragma.
    @Query("DELETE FROM entry_draft_rows WHERE transactionId = :transactionId")
    suspend fun deleteRows(transactionId: Long)

    @Query("DELETE FROM entry_draft_categories WHERE transactionId = :transactionId")
    suspend fun deleteCategories(transactionId: Long)

    @Query("DELETE FROM entry_drafts WHERE transactionId = :transactionId")
    suspend fun deleteDraft(transactionId: Long)

    @Query("UPDATE entry_drafts SET payerFriendId = :targetId WHERE payerFriendId = :sourceId")
    suspend fun reassignPayerFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE entry_drafts SET payeeFriendId = :targetId WHERE payeeFriendId = :sourceId")
    suspend fun reassignPayeeFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE entry_draft_rows SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignRowFriend(sourceId: Long, targetId: Long)

    @Query(
        """
        UPDATE entry_draft_rows SET
            payeeLegViaFriendId = CASE WHEN payeeLegViaFriendId = :sourceId THEN :targetId ELSE payeeLegViaFriendId END,
            payerLegViaFriendId = CASE WHEN payerLegViaFriendId = :sourceId THEN :targetId ELSE payerLegViaFriendId END
        WHERE payeeLegViaFriendId = :sourceId OR payerLegViaFriendId = :sourceId
        """
    )
    suspend fun reassignRowVia(sourceId: Long, targetId: Long)

    @Query("UPDATE entry_drafts SET payerMerchantId = :targetId WHERE payerMerchantId = :sourceId")
    suspend fun reassignPayerMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE entry_drafts SET payeeMerchantId = :targetId WHERE payeeMerchantId = :sourceId")
    suspend fun reassignPayeeMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE OR IGNORE entry_draft_categories SET categoryId = :targetId WHERE categoryId = :sourceId")
    suspend fun reassignCategory(sourceId: Long, targetId: Long)
}

/**
 * Removes [transactionId]'s draft, if it has one, in the caller's database transaction.
 *
 * Deliberately not a `@Transaction` DAO method. Every caller is already inside a transaction, and
 * the entry screen's save and the notification's confirm hold theirs with a blocking
 * `runInTransaction`. A suspending `@Transaction` method called from there starts a second
 * transaction on Room's transaction thread, which waits for the lock the caller holds while the
 * caller waits for it: the save never finished, and every write queued behind it hung with it.
 * Plain suspending queries run inline on a thread already in a transaction, so these three cannot.
 */
suspend fun EntryDraftDao.clearDraft(transactionId: Long) {
    deleteRows(transactionId)
    deleteCategories(transactionId)
    deleteDraft(transactionId)
}
