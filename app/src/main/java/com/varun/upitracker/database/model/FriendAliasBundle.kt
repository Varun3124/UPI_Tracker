package com.varun.upitracker.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId

data class FriendAliasBundle(
    @Embedded
    val friend: Friend,
    @Relation(parentColumn = "id", entityColumn = "friendId")
    val rawNames: List<FriendRawName>,
    @Relation(parentColumn = "id", entityColumn = "friendId")
    val upiIds: List<FriendUpiId>
)
