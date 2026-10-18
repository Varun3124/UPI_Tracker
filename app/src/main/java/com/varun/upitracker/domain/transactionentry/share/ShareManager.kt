package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ui.ActorType

data class ShareRowModel(
    val key: String,
    val participantType: String,
    val friendId: Long?,
    val label: String,
    val initials: String,
    val amountPaise: Long,
    val keepPayeeLeg: Boolean = true,
    val keepPayerLeg: Boolean = true,
    /** Who each of the row's IOUs runs through, or null for straight. See [com.varun.upitracker.domain.iou.IouLegs]. */
    val payeeVia: IouParty? = null,
    val payerVia: IouParty? = null
)

class ShareManager {

    /**
     * The row's IOU choices survive only while it names the same person: someone new starts with
     * every IOU kept and running straight, as a fresh row does.
     */
    fun updateParticipant(
        rows: List<ShareRowModel>,
        index: Int,
        participantType: String,
        friendId: Long?,
        label: String,
        initials: String
    ): List<ShareRowModel> {
        if (index !in rows.indices) return rows
        val old = rows[index]
        val key = if (participantType == ActorType.ME) "ME" else "F:$friendId"
        val samePerson = old.key == key

        return rows.toMutableList().apply {
            this[index] = ShareRowModel(
                key = key,
                participantType = participantType,
                friendId = friendId,
                label = label,
                initials = initials,
                amountPaise = old.amountPaise,
                keepPayeeLeg = !samePerson || old.keepPayeeLeg,
                keepPayerLeg = !samePerson || old.keepPayerLeg,
                payeeVia = old.payeeVia.takeIf { samePerson },
                payerVia = old.payerVia.takeIf { samePerson }
            )
        }
    }

    fun addDraftRow(rows: List<ShareRowModel>, suggestedAmountPaise: Long): List<ShareRowModel> {
        return rows + ShareRowModel(
            key = "",
            participantType = ActorType.FRIEND,
            friendId = null,
            label = "",
            initials = "?",
            amountPaise = suggestedAmountPaise
        )
    }

    fun removeRow(rows: List<ShareRowModel>, index: Int): List<ShareRowModel> {
        if (index !in rows.indices) return rows
        return rows.toMutableList().apply { removeAt(index) }
    }

    /**
     * A side as a chapter's template fills it on a new entry: whoever the side's actor is, then
     * everyone else in the chapter, each with nothing yet against them.
     *
     * When the side is ME, the members follow ME. When it is a friend, the primary is left as it is
     * -- usually blank, since nobody has been named yet -- and ME joins the members below it: any of
     * them may turn out to be the one who paid. A merchant side holds no people, so it is untouched.
     *
     * Members are matched against [friends]; an id with no friend behind it is skipped.
     */
    fun withChapterMembers(
        primary: ShareRowModel,
        actorType: String,
        memberIds: Collection<Long>,
        friends: List<Friend>,
        meLabel: String
    ): List<ShareRowModel> {
        val members = memberIds.mapNotNull { id -> friends.firstOrNull { it.id == id } }.map { friend ->
            ShareRowModel(
                key = "F:${friend.id}",
                participantType = ActorType.FRIEND,
                friendId = friend.id,
                label = friend.name,
                initials = friend.avatarInitials,
                amountPaise = 0L
            )
        }
        return when (actorType) {
            ActorType.ME -> listOf(primary) + members
            ActorType.FRIEND -> listOf(primary) +
                ShareRowModel("ME", ActorType.ME, null, meLabel, "ME", 0L) +
                members
            else -> listOf(primary)
        }
    }
}
