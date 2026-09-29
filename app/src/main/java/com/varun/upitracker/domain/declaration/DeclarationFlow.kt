package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationState

/** Something that happened to a proposal. */
enum class DeclarationEvent {

    /** The recipient accepted: their ACCEPT arrived here, or this phone accepted theirs and said so. */
    ACCEPTED,

    /** The recipient said no. */
    DENIED,

    /** The proposer took it back. */
    WITHDRAWN,

    /** The two phones are no longer linked, so nobody can answer it any more. */
    UNLINKED
}

/**
 * How a proposal's state moves, the same on both phones.
 *
 * The one rule that is not obvious is D3, **accept beats withdraw**. A proposal can be accepted on
 * one phone while it is being withdrawn on the other, and the two messages cross. The recipient only
 * ever sends ACCEPT for a proposal that was still open on their phone, so an ACCEPT that reaches the
 * proposer after they withdrew is a genuine agreement their book has already applied -- and this one
 * has to apply it too, or the two books part ways. For the same reason an ACCEPT still counts after
 * the two unlinked: it was sent while they were linked, and the other phone applied it.
 *
 * Everything else is first-come: a proposal that is no longer open ignores a second answer.
 */
object DeclarationFlow {

    /** The state after [event], or null when it changes nothing. */
    fun next(state: String, event: DeclarationEvent): String? = when (event) {
        DeclarationEvent.ACCEPTED -> when (state) {
            DeclarationState.OPEN,
            DeclarationState.WITHDRAWN,
            DeclarationState.CLOSED -> DeclarationState.ACCEPTED
            else -> null
        }
        DeclarationEvent.DENIED -> DeclarationState.DENIED.takeIf { state == DeclarationState.OPEN }
        DeclarationEvent.WITHDRAWN -> DeclarationState.WITHDRAWN.takeIf { state == DeclarationState.OPEN }
        DeclarationEvent.UNLINKED -> DeclarationState.CLOSED.takeIf { state == DeclarationState.OPEN }
    }

    /**
     * Whether this phone may accept or deny [state] itself. Narrower than [next]: accepting a
     * proposal its sender withdrew, or one left open when the two unlinked, would be agreeing to
     * something the other side has already let go of.
     */
    fun canAnswer(state: String): Boolean = state == DeclarationState.OPEN
}
