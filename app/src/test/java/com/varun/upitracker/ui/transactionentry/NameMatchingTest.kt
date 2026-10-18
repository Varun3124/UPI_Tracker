package com.varun.upitracker.ui.transactionentry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchingTest {

    private val me = "Asha(Me)"

    @Test
    fun theUsersOwnEntryMatchesAnyPartOfItself() {
        listOf("a", "sha", "(m", "me", "ME)", "Asha(Me)").forEach { typed ->
            assertTrue("'$typed' should find $me", NameMatching.matches(me, typed, me))
        }
        assertFalse(NameMatching.matches(me, "bob", me))
    }

    @Test
    fun friendsStillMatchByNameOrWordPrefix() {
        assertTrue(NameMatching.matches("Ravi Kumar", "rav", me))
        assertTrue(NameMatching.matches("Ravi Kumar", "kum", me))
        assertFalse(NameMatching.matches("Ravi Kumar", "avi", me))
    }

    @Test
    fun nothingTypedMatchesEveryone() {
        assertTrue(NameMatching.matches("Ravi", "", me))
        assertTrue(NameMatching.matches(me, null, me))
    }
}
