package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.varun.upitracker.database.entity.FriendLink
import com.varun.upitracker.database.entity.LinkInvite
import com.varun.upitracker.database.entity.MailboxMessage
import com.varun.upitracker.database.entity.TransactionDelivery

/** Links, invites, collected messages and deliveries: everything the friends mailbox keeps locally. */
@Dao
interface MailboxDao {

    // --- links --------------------------------------------------------------------------------

    /** Replacing is right here: re-linking a friend overwrites their pins and state in one go. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: FriendLink)

    @Query("SELECT * FROM friend_links WHERE friendId = :friendId")
    suspend fun getLink(friendId: Long): FriendLink?

    @Query("SELECT * FROM friend_links WHERE uid = :uid")
    suspend fun getLinkByUid(uid: String): FriendLink?

    @Query("SELECT * FROM friend_links")
    suspend fun getAllLinks(): List<FriendLink>

    @Query("UPDATE friend_links SET state = :state WHERE friendId = :friendId")
    suspend fun setLinkState(friendId: Long, state: String)

    @Query("DELETE FROM friend_links WHERE friendId = :friendId")
    suspend fun deleteLink(friendId: Long)

    @Query("DELETE FROM friend_links")
    suspend fun deleteAllLinks()

    /** For a friend merge whose source alone is linked. The caller refuses when both are. */
    @Query("UPDATE friend_links SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun moveLink(sourceId: Long, targetId: Long)

    // --- invites ------------------------------------------------------------------------------

    @Insert
    suspend fun insertInvite(invite: LinkInvite)

    @Query("SELECT * FROM link_invites WHERE id = :id")
    suspend fun getInvite(id: String): LinkInvite?

    @Query("SELECT * FROM link_invites WHERE friendId = :friendId ORDER BY createdEpoch DESC")
    suspend fun getInvitesForFriend(friendId: Long): List<LinkInvite>

    @Query("SELECT * FROM link_invites")
    suspend fun getAllInvites(): List<LinkInvite>

    @Query("DELETE FROM link_invites WHERE id = :id")
    suspend fun deleteInvite(id: String)

    @Query("DELETE FROM link_invites")
    suspend fun deleteAllInvites()

    @Query("UPDATE link_invites SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignInvites(sourceId: Long, targetId: Long)

    // --- messages -----------------------------------------------------------------------------

    /**
     * -1 when a message with this id is already here. That is the whole of the at-least-once story:
     * a message whose server copy failed to delete comes back next time and is recognised here.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNew(message: MailboxMessage): Long

    @Query("SELECT * FROM mailbox_messages WHERE id = :id")
    suspend fun getMessage(id: String): MailboxMessage?

    @Query("SELECT * FROM mailbox_messages WHERE state IN (:states) ORDER BY sentEpoch DESC")
    suspend fun getMessagesInStates(states: List<String>): List<MailboxMessage>

    @Query("SELECT * FROM mailbox_messages WHERE state IN (:states) ORDER BY sentEpoch DESC")
    fun observeMessagesInStates(states: List<String>): LiveData<List<MailboxMessage>>

    @Query("SELECT COUNT(*) FROM mailbox_messages WHERE state = :state")
    suspend fun countInState(state: String): Int

    @Query("UPDATE mailbox_messages SET state = :state WHERE id = :id")
    suspend fun setMessageState(id: String, state: String)

    /** A held message finally opened: it gains a body and a friend, and loses the sealed copy. */
    @Query(
        """
        UPDATE mailbox_messages
        SET body = :body, friendId = :friendId, state = :state, ciphertext = NULL
        WHERE id = :id
        """
    )
    suspend fun resolveHeldMessage(id: String, body: String?, friendId: Long?, state: String)

    @Query("DELETE FROM mailbox_messages WHERE state = :state AND receivedEpoch < :beforeEpoch")
    suspend fun deleteMessagesInStateBefore(state: String, beforeEpoch: Long)

    @Query("DELETE FROM mailbox_messages")
    suspend fun deleteAllMessages()

    // --- deliveries ---------------------------------------------------------------------------

    @Insert
    suspend fun insertDeliveries(deliveries: List<TransactionDelivery>)

    @Query("SELECT * FROM transaction_deliveries WHERE transactionId IN (:transactionIds)")
    suspend fun getDeliveriesFor(transactionIds: List<Long>): List<TransactionDelivery>

    @Query("SELECT DISTINCT messageId FROM transaction_deliveries WHERE friendId = :friendId")
    suspend fun getDeliveredMessageIdsFor(friendId: Long): List<String>

    @Query("SELECT DISTINCT friendId, messageId FROM transaction_deliveries")
    suspend fun getAllDeliveredMessages(): List<DeliveredMessage>

    @Query("DELETE FROM transaction_deliveries")
    suspend fun deleteAllDeliveries()

    @Query("UPDATE transaction_deliveries SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun reassignDeliveries(sourceId: Long, targetId: Long)
}

/** A message id and whose inbox it went to. */
data class DeliveredMessage(val friendId: Long, val messageId: String)
