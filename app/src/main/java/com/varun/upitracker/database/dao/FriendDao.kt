package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.varun.upitracker.database.model.FriendAliasBundle

@Dao
interface FriendDao {

    @Insert
    suspend fun insertFriend(friend: com.varun.upitracker.database.entity.Friend): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpiId(upiId: com.varun.upitracker.database.entity.FriendUpiId)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawName(rawName: com.varun.upitracker.database.entity.FriendRawName)

    @Update
    suspend fun updateFriend(friend: com.varun.upitracker.database.entity.Friend)

    @Query("SELECT * FROM friends ORDER BY name ASC")
    fun getAllFriends(): LiveData<List<com.varun.upitracker.database.entity.Friend>>

    @Query("SELECT * FROM friends WHERE id = :id")
    suspend fun getFriendById(id: Long): com.varun.upitracker.database.entity.Friend?

    // Credit resolution — look up by UPI ID
    @Query("SELECT * FROM friend_upi_ids WHERE upiId = :upiId LIMIT 1")
    suspend fun findByUpiId(upiId: String): com.varun.upitracker.database.entity.FriendUpiId?

    // Debit resolution — look up by raw name
    @Query("SELECT * FROM friend_raw_names WHERE rawName = :rawName LIMIT 1")
    suspend fun findByRawName(rawName: String): com.varun.upitracker.database.entity.FriendRawName?

    @Query("SELECT * FROM friend_upi_ids WHERE friendId = :friendId")
    suspend fun getUpiIdsForFriend(friendId: Long): List<com.varun.upitracker.database.entity.FriendUpiId>

    @Query("SELECT * FROM friend_raw_names WHERE friendId = :friendId")
    suspend fun getRawNamesForFriend(friendId: Long): List<com.varun.upitracker.database.entity.FriendRawName>

    @Query("SELECT * FROM friends ORDER BY name ASC")
    suspend fun getAllFriendsSync(): List<com.varun.upitracker.database.entity.Friend>

    @Query("SELECT * FROM friends WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): com.varun.upitracker.database.entity.Friend?

    @Query("SELECT * FROM friends WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByNormalizedName(name: String): com.varun.upitracker.database.entity.Friend?

    @Query("""
    SELECT friends.* FROM friends
    LEFT JOIN (
        SELECT friendId, COUNT(*) as cnt FROM iou_entries GROUP BY friendId
    ) iou ON friends.id = iou.friendId
    LEFT JOIN (
        SELECT friendId, COUNT(*) as cnt FROM transaction_shares
        WHERE friendId IS NOT NULL
        GROUP BY friendId
    ) share ON friends.id = share.friendId
    GROUP BY friends.id
    ORDER BY (COALESCE(iou.cnt,0) + COALESCE(share.cnt,0)) DESC, friends.name ASC
""")
    suspend fun getAllFriendsByFrequency(): List<com.varun.upitracker.database.entity.Friend>

    @Transaction
    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getAliasBundles(): List<com.varun.upitracker.database.model.FriendAliasBundle>

    @Query("UPDATE friend_raw_names SET friendId = :friendId WHERE id = :mappingId")
    suspend fun reassignRawName(mappingId: Long, friendId: Long)

    @Query("UPDATE friend_upi_ids SET friendId = :friendId WHERE id = :mappingId")
    suspend fun reassignUpiId(mappingId: Long, friendId: Long)

    @Query("UPDATE friend_raw_names SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun moveAllRawNames(sourceId: Long, targetId: Long)

    @Query("UPDATE friend_upi_ids SET friendId = :targetId WHERE friendId = :sourceId")
    suspend fun moveAllUpiIds(sourceId: Long, targetId: Long)

    @Delete
    suspend fun deleteFriend(friend: com.varun.upitracker.database.entity.Friend)

    @Delete
    suspend fun deleteUpiId(upiId: com.varun.upitracker.database.entity.FriendUpiId)

    @Delete
    suspend fun deleteRawName(rawName: com.varun.upitracker.database.entity.FriendRawName)
}
