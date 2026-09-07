package com.varun.upitracker.sms.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsParserTest {

    @Test
    fun parse_hdfcCredit() {
        val body = "Credit Alert!\nRs.40.00 credited to HDFC Bank A/c XX7478 on 07-04-26 from VPA guy9@pingpay (UPI 695909760976)"

        val parsed = SmsParser.parse("HDFCBK", body, 1000L)

        assertEquals(4000L, parsed?.amountPaise)
        assertEquals("CREDIT", parsed?.direction)
        assertEquals("guy9@pingpay", parsed?.payeeRaw)
        assertEquals("695909760976", parsed?.upiRefId)
        assertEquals(1000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_hdfcDebit() {
        val body = "Sent Rs.110.00\nFrom HDFC Bank A/C *7478\nTo GUY UPI USERNAME\nOn 29/03/26\nRef 645440000144\nNot you? Call 18002586161"

        val parsed = SmsParser.parse("HDFCBK", body, 2000L)

        assertEquals(11000L, parsed?.amountPaise)
        assertEquals("DEBIT", parsed?.direction)
        assertEquals("GUY UPI USERNAME", parsed?.payeeRaw)
        assertEquals("645440000144", parsed?.upiRefId)
    }

    @Test
    fun parse_axisDebit() {
        val body = "INR 100.00 debited\nA/c no. XX1591\n06-09-26, 17:59:11\nUPI/P2A/624922603677/HETVEE SAKARIA\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank"

        val parsed = SmsParser.parse("XX-AXISBK-X", body, 3000L)

        assertEquals(10000L, parsed?.amountPaise)
        assertEquals("DEBIT", parsed?.direction)
        assertEquals("HETVEE SAKARIA", parsed?.payeeRaw)
        assertEquals("624922603677", parsed?.upiRefId)
        assertEquals(3000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_iciciCredit() {
        val body = "Dear Customer, Acct XX458 is credited with Rs 691.00 on 16-Jul-26 from HET RASHMINKUMA. UPI:619720049955-ICICI Bank."

        val parsed = SmsParser.parse("XX-ICICIT-X", body, 6000L)

        assertEquals(69100L, parsed?.amountPaise)
        assertEquals("CREDIT", parsed?.direction)
        assertEquals("HET RASHMINKUMA", parsed?.payeeRaw)
        assertEquals("619720049955", parsed?.upiRefId)
        assertEquals(6000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_iciciDebit() {
        val body = "ICICI Bank Acct XX458 debited for Rs 1117.00 on 17-Aug-26; EKLINGJI ENTERP credited. UPI:659555777792. Call 18002662 for dispute. SMS BLOCK 458 to 9215676766."

        val parsed = SmsParser.parse("XX-ICICIT-X", body, 7000L)

        assertEquals(111700L, parsed?.amountPaise)
        assertEquals("DEBIT", parsed?.direction)
        assertEquals("EKLINGJI ENTERP", parsed?.payeeRaw)
        assertEquals("659555777792", parsed?.upiRefId)
        assertEquals(7000L, parsed?.dateEpoch)
    }

    @Test
    fun parse_unknownSender_returnsNull() {
        val parsed = SmsParser.parse("VM-ICICIB-S", "INR 100.00 debited UPI/P2A/123/SOMEONE", 4000L)

        assertNull(parsed)
    }

    @Test
    fun parse_axisNonUpiMessage_returnsNull() {
        val parsed = SmsParser.parse("XX-AXISBK-X", "Your OTP is 123456. Do not share it with anyone.", 5000L)

        assertNull(parsed)
    }
}
