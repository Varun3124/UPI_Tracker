package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.ui.ActorType

data class ShareRowModel(
    val key: String,
    val participantType: String,
    val friendId: Long?,
    val label: String,
    val initials: String,
    val amountPaise: Long
)

class ShareManager {

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

        return rows.toMutableList().apply {
            this[index] = ShareRowModel(
                key = key,
                participantType = participantType,
                friendId = friendId,
                label = label,
                initials = initials,
                amountPaise = old.amountPaise
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
