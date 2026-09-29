package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.IouDeclaration

/** One chapter's share of a friend's balance on this phone, and the id it goes by elsewhere if shared. */
data class ChapterShareOf(val chapterId: Long, val shareId: String?, val amountPaise: Long)

/**
 * Which chapter shares a declaration counted, on each phone. See docs/declarations-design.md D6.
 *
 * A declaration's amount is the whole balance, chapters included, but chapters stay live. So each phone
 * notes how much of the amount each chapter was, and the opening is the amount less those parts: from
 * then on a chapter only moves the balance by how much it has changed. What matters is that both phones
 * note the same figure for every chapter they both hold -- which is what the rules below arrange.
 */
object DeclarationParts {

    /** The proposer: every chapter share its amount counted, shared or private, as they stand now. */
    fun forProposal(declarationId: String, shares: List<ChapterShareOf>): List<DeclarationPart> =
        shares.filter { it.amountPaise != 0L }.map {
            DeclarationPart(declarationId = declarationId, chapterId = it.chapterId, shareId = it.shareId, amountPaise = it.amountPaise)
        }

    /**
     * The parts [declaration] gains on this phone when it is accepted, beside the [have] it already holds.
     *
     *  - A DECLARE this phone received gains this phone's own private chapters' shares, as they stand
     *    now: the proposer could not see those, so the agreed figure can only include them this way. The
     *    shared chapters it counted arrived listed with it, and a shared chapter it did not list counts
     *    in full on both phones.
     *  - An AMEND keeps whatever the agreement it replaces had counted: only the amount changes.
     *  - The proposer's own DECLARE already holds everything it counted.
     */
    fun onAcceptance(
        declaration: IouDeclaration,
        have: List<DeclarationPart>,
        ownShares: List<ChapterShareOf>,
        targetParts: List<DeclarationPart>
    ): List<DeclarationPart> = when (declaration.kind) {
        DeclarationKind.DECLARE -> if (declaration.proposedByMe) emptyList() else {
            ownShares
                .filter { it.amountPaise != 0L && it.shareId == null }
                .filter { share -> have.none { it.chapterId == share.chapterId } }
                .map { DeclarationPart(declarationId = declaration.id, chapterId = it.chapterId, shareId = null, amountPaise = it.amountPaise) }
        }
        DeclarationKind.AMEND -> targetParts
            .filter { part -> have.none { it.chapterId == part.chapterId && it.shareId == part.shareId } }
            .map { it.copy(id = 0L, declarationId = declaration.id) }
        else -> emptyList()
    }

    /**
     * A part that arrived listed in a proposal (from the reader's seat already), attached to this phone's
     * copy of the chapter when it has one. Until it does, the part waits and counts for nothing.
     */
    fun listed(declarationId: String, part: ProposalPart, chapterIdHere: Long?): DeclarationPart =
        DeclarationPart(declarationId = declarationId, chapterId = chapterIdHere, shareId = part.shareId, amountPaise = part.amountPaise)
}
