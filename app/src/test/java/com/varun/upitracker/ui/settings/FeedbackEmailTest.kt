package com.varun.upitracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a suggestion looks like by the time it reaches the feedback inbox. */
class FeedbackEmailTest {

    @Test
    fun `send waits for something other than whitespace`() {
        assertFalse(FeedbackEmail.canSend(""))
        assertFalse(FeedbackEmail.canSend("  \n\t "))
        assertTrue(FeedbackEmail.canSend(" Add a dark widget "))
    }

    @Test
    fun `body is the trimmed suggestion, then the build and device below a rule`() {
        val body = FeedbackEmail.body(
            suggestion = "\n  Show totals per chapter.  \n",
            appVersion = "1.5 (6)",
            androidVersion = "14 (API 34)",
            device = "Google Pixel 7"
        )
        assertEquals(
            "Show totals per chapter.\n\n" +
                "--\n" +
                "App version: 1.5 (6)\n" +
                "Android: 14 (API 34)\n" +
                "Device: Google Pixel 7\n",
            body
        )
    }

    @Test
    fun `every suggestion goes to the feedback inbox`() {
        assertEquals("dhanmoney.feedback@gmail.com", FeedbackEmail.RECIPIENT)
    }
}
