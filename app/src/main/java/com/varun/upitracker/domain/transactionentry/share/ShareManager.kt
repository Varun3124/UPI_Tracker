package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.ui.ActorType

data class ShareRowModel(
    val key: String,
    val participantType: String,
    val friendId: Long?,
    val label: String,
    val initials: String,
    val amountPaise: Long,
    val keepPayeeLeg: Boolean = true,
    val keepPayerLeg: Boolean = true
)

class ShareManager {

    /**
     * The row's IOU choices survive only while it names the same person: someone new starts with
     * every IOU kept, as a fresh row does.
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
                keepPayerLeg = !samePerson || old.keepPayerLeg
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
}
