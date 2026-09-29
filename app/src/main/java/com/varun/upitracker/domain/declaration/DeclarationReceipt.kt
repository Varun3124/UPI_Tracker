package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration

/**
 * What a message from the other phone does to this phone's rows. Pure, so the whole exchange can be
 * replayed under JUnit in every order the mailbox might deliver it -- which is the only way to be sure
 * the two phones end up agreeing (docs/declarations-design.md D3, D4).
 *
 * The sender is always the verified, linked friend the caller names. Nothing here trusts a message to
 * say who it is from, or which friend a proposal is about.
 */
object DeclarationReceipt {

    /**
     * The row an incoming proposal becomes, or null when it should be ignored: a repeat delivery, or
     * a proposal whose own withdrawal got here first and left a stub under its id.
     */
    fun rowForProposal(existing: IouDeclaration?, friendId: Long, proposal: DeclarationProposal): IouDeclaration? {
        if (existing != null) return null
        return IouDeclaration(
            id = proposal.id,
            friendId = friendId,
            kind = proposal.kind,
            targetId = proposal.targetId,
            asOfEpoch = proposal.asOfEpoch,
            amountPaise = proposal.amountPaise,
            deltaPaise = proposal.deltaPaise,
            note = proposal.note,
            proposedByMe = false,
            proposedEpoch = proposal.proposedEpoch,
            state = DeclarationState.OPEN,
            auto = proposal.auto
        )
    }

    /**
     * The row after [answer], or null when it changes nothing.
     *
     * ACCEPT and DENY only ever answer a proposal *this* phone made to [friendId]; anything else is
     * ignored. WITHDRAW only ever takes back one [friendId] made -- and when that proposal has not
     * arrived yet, the result is a stub that will swallow it when it does.
     */
    fun applyAnswer(existing: IouDeclaration?, friendId: Long, answer: DeclarationAnswer, now: Long): IouDeclaration? =
        when (answer.verdict) {
            DeclarationVerdict.ACCEPT, DeclarationVerdict.DENY -> {
                val row = existing?.takeIf { it.proposedByMe && it.friendId == friendId && it.kind != null }
                val event = if (answer.verdict == DeclarationVerdict.ACCEPT) DeclarationEvent.ACCEPTED else DeclarationEvent.DENIED
                row?.let { mine ->
                    DeclarationFlow.next(mine.state, event)?.let { next ->
                        // Accepted before the other side learnt the two had unlinked: it counts, and
                        // their copy was archived when they did learn, so this one is archived too.
                        val archived = mine.archived || mine.state == DeclarationState.CLOSED
                        mine.copy(state = next, decidedEpoch = now, replyNote = answer.note, archived = archived)
                    }
                }
            }
            DeclarationVerdict.WITHDRAW -> when {
                existing == null -> stub(answer.proposalId, friendId, now)
                existing.proposedByMe || existing.friendId != friendId -> null
                else -> DeclarationFlow.next(existing.state, DeclarationEvent.WITHDRAWN)?.let { next ->
                    existing.copy(state = next, decidedEpoch = now)
                }
            }
        }

    /** A withdrawal that arrived before the proposal it withdraws. It never becomes a declaration. */
    fun stub(proposalId: String, friendId: Long, now: Long): IouDeclaration = IouDeclaration(
        id = proposalId,
        friendId = friendId,
        kind = null,
        proposedByMe = false,
        proposedEpoch = now,
        state = DeclarationState.WITHDRAWN,
        decidedEpoch = now
    )
}
