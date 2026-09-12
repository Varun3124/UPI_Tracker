package com.varun.upitracker.notification.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both bodies here are copied verbatim (via `adb shell dumpsys notification --noredact`) from
 * real Axis Bank alert emails as Gmail's Android app renders them (BigTextStyle).
 */
class NotificationParserTest {

    private val debitText = """
        INR 1.00 was debited from your A/c no. XX1591.
        07-09-2026
        Dear Zeel Trivedi,
        Here's the summary of your transaction:
        Amount Debited:
        INR 1.00
        Account Number:
        XX1591
        Date & Time:
        07-09-26, 15:47:27 IST
        Transaction Info:
        UPI/P2A/625013838137/VARUN IYER
        If this transaction was not initiated by you:
        To block UPI:
        SMS BLOCKUPI <Customer ID> to +919951860002 from your registered mobile number.
        Regards,
        Axis Bank Ltd.
    """.trimIndent()

    private val creditText = """
        INR 40.00 was credited to your A/c.

        07-09-2026
        Dear Zeel Trivedi,
        Here's the summary of your transaction:
        Amount Credited:
        INR 40.00
        Account Number:
        XX1591
        Date & Time:
        07-09-26, 15:42:46 IST
        Transaction Info:
        UPI/P2A/625056047549/VARUN IYE/HDFC/UPI
        Feel free to connect with us for any clarification.
        Regards,
        Axis Bank Ltd.
    """.trimIndent()

    @Test
    fun parse_axisGmailDebit() {
        val parsed = NotificationParser.parse("com.google.android.gm", debitText, 1000L)

        assertEquals(100L, parsed?.amountPaise)
        assertEquals("DEBIT", parsed?.direction)
        assertEquals("VARUN IYER", parsed?.payeeRaw)
        assertEquals("625013838137", parsed?.upiRefId)
        assertEquals(1000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_axisGmailCredit() {
        val parsed = NotificationParser.parse("com.google.android.gm", creditText, 2000L)

        assertEquals(4000L, parsed?.amountPaise)
        assertEquals("CREDIT", parsed?.direction)
        assertEquals("VARUN IYE/HDFC/UPI", parsed?.payeeRaw)
        assertEquals("625056047549", parsed?.upiRefId)
        assertEquals(2000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_wrongPackage_returnsNull() {
        val parsed = NotificationParser.parse("com.microsoft.office.outlook", debitText, 3000L)

        assertNull(parsed)
    }

    @Test
    fun parse_gmailNonBankEmail_returnsNull() {
        val parsed = NotificationParser.parse(
            "com.google.android.gm",
            "Your flight to Mumbai is confirmed for tomorrow.",
            4000L
        )

        assertNull(parsed)
    }
}
