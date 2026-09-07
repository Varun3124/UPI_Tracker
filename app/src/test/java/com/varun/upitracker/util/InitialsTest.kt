package com.varun.upitracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * This rule is not purely cosmetic: `SettingsRepository` stores its result in
 * `Friend.avatarInitials`, so the fallback in particular is pinned here rather than left to whatever
 * the three former copies happened to do.
 */
class InitialsTest {

    @Test
    fun `takes the first letter of up to two words`() {
        assertEquals("JS", initialsOf("Jayneel Shah"))
        assertEquals("Z", initialsOf("Zeel"))
        assertEquals("AB", initialsOf("Anita Bose Chatterjee"))
    }

    @Test
    fun `uppercases and ignores extra whitespace`() {
        assertEquals("JS", initialsOf("jayneel  shah"))
        assertEquals("JS", initialsOf("  jayneel shah  "))
        assertEquals("JS", initialsOf("jayneel\tshah"))
    }

    @Test
    fun `falls back rather than returning an empty circle`() {
        assertEquals("?", initialsOf(""))
        assertEquals("?", initialsOf("   "))
        assertEquals("?", initialsOf(null))
    }

    /** The persisted callers ask for "F", which is what the pre-existing rows already hold. */
    @Test
    fun `fallback is caller-chosen`() {
        assertEquals("F", initialsOf("", fallback = "F"))
        assertEquals("F", initialsOf(null, fallback = "F"))
        assertEquals("ME", initialsOf(null, fallback = "ME"))
    }

    @Test
    fun `non-letter names still yield something`() {
        assertEquals("7", initialsOf("7-Eleven"))
        assertEquals("@S", initialsOf("@swiggy store"))
    }
}
