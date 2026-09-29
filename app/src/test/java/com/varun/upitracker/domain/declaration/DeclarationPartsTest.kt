package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md D6: which chapter shares each phone counts in an agreement. */
class DeclarationPartsTest {

    private val shares = listOf(
        ChapterShareOf(chapterId = 1L, shareId = null, amountPaise = 30_000L),     // private
        ChapterShareOf(chapterId = 2L, shareId = "goa", amountPaise = -5_000L),    // shared
        ChapterShareOf(chapterId = 3L, shareId = null, amountPaise = 0L)           // even: nothing to count
    )

    private fun declaration(kind: String, byMe: Boolean) = IouDeclaration(
        id = "d", friendId = 7L, kind = kind, targetId = if (kind == DeclarationKind.AMEND) "t" else null,
        asOfEpoch = 1L, amountPaise = 100L, proposedByMe = byMe, proposedEpoch = 1L, state = DeclarationState.ACCEPTED
    )

    @Test
    fun `a proposal counts every chapter share it included`() {
        val parts = DeclarationParts.forProposal("d", shares)
        assertEquals(listOf(1L to null, 2L to "goa"), parts.map { it.chapterId to it.shareId })
        assertEquals(listOf(30_000L, -5_000L), parts.map { it.amountPaise })
    }

    @Test
    fun `the acceptor adds its own private chapters, never shared ones`() {
        val listed = listOf(DeclarationPart(declarationId = "d", chapterId = 2L, shareId = "goa", amountPaise = -4_000L))
        val added = DeclarationParts.onAcceptance(declaration(DeclarationKind.DECLARE, byMe = false), listed, shares, emptyList())
        assertEquals(listOf(1L), added.map { it.chapterId })
        assertTrue(added.all { it.shareId == null })
    }

    @Test
    fun `the proposer's own declaration adds nothing more`() {
        assertTrue(DeclarationParts.onAcceptance(declaration(DeclarationKind.DECLARE, byMe = true), emptyList(), shares, emptyList()).isEmpty())
    }

    @Test
    fun `an amendment keeps what the agreement it replaces counted`() {
        val target = listOf(
            DeclarationPart(id = 9L, declarationId = "t", chapterId = 1L, shareId = null, amountPaise = 30_000L),
            DeclarationPart(id = 10L, declarationId = "t", chapterId = null, shareId = "later", amountPaise = 2_000L)
        )
        val added = DeclarationParts.onAcceptance(declaration(DeclarationKind.AMEND, byMe = false), emptyList(), shares, target)
        assertEquals(listOf("d", "d"), added.map { it.declarationId })
        assertEquals(listOf(0L, 0L), added.map { it.id })
        assertEquals(listOf(30_000L, 2_000L), added.map { it.amountPaise })
    }

    @Test
    fun `a listed part waits until its chapter is here`() {
        val waiting = DeclarationParts.listed("d", ProposalPart("goa", -5_000L), chapterIdHere = null)
        val attached = DeclarationParts.listed("d", ProposalPart("goa", -5_000L), chapterIdHere = 4L)
        assertEquals(null, waiting.chapterId)
        assertEquals(4L, attached.chapterId)
        // Waiting, it counts for nothing in the opening.
        val row = declaration(DeclarationKind.DECLARE, byMe = false)
        assertEquals(100L, Checkpoints.openingAmount(row, listOf(waiting)))
        assertEquals(5_100L, Checkpoints.openingAmount(row, listOf(attached)))
    }
}
