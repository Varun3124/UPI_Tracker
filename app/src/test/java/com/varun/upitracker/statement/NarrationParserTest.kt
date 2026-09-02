package com.varun.upitracker.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every narration here is copied verbatim from a real HDFC statement export. They are chosen for
 * the traps, not for coverage: hyphens inside VPAs are the norm, not the exception.
 */
class NarrationParserTest {

    @Test
    fun parse_plainUpiNarration() {
        val parsed = NarrationParser.parse(
            "UPI-JIO-RELIANCERETAIL2.EASEBUZZ@KOTAKPAY-KKBK0JPUPIA-126864903466-PAY",
            "0000126864903466"
        )

        assertTrue(parsed.isUpi)
        assertEquals("JIO", parsed.rawName)
        assertEquals("RELIANCERETAIL2.EASEBUZZ@KOTAKPAY", parsed.upiId)
        assertEquals("KKBK0JPUPIA", parsed.ifsc)
        assertEquals("126864903466", parsed.upiRefId)
        assertEquals("PAY", parsed.notes)
    }

    @Test
    fun parse_hyphenInsideVpa() {
        val parsed = NarrationParser.parse(
            "UPI-SACHIN JAVANJI THAKO-9023631695-2@YBL-PUNB0930200-127526357460-UPI",
            "0000127526357460"
        )

        assertEquals("SACHIN JAVANJI THAKO", parsed.rawName)
        assertEquals("9023631695-2@YBL", parsed.upiId)
        assertEquals("PUNB0930200", parsed.ifsc)
        assertEquals("127526357460", parsed.upiRefId)
    }

    @Test
    fun parse_gpayPrefixedVpa() {
        val parsed = NarrationParser.parse(
            "UPI-BHAVINBHAI JENTIBHAI-GPAY-12189961159@OKBIZAXIS-UTIB0000553-127511390012-UPI",
            "0000127511390012"
        )

        assertEquals("BHAVINBHAI JENTIBHAI", parsed.rawName)
        assertEquals("GPAY-12189961159@OKBIZAXIS", parsed.upiId)
        assertEquals("127511390012", parsed.upiRefId)
    }

    @Test
    fun parse_multiWordNotesAndTruncatedName() {
        val parsed = NarrationParser.parse(
            "UPI-CRAZZY PRODUCT PRIVA-CRAZZYPRODUCT@YBL-YESB0YBLUPI-127409542028-PAYMENT FOR 901441",
            "0000127409542028"
        )

        assertEquals("CRAZZY PRODUCT PRIVA", parsed.rawName)
        assertEquals("CRAZZYPRODUCT@YBL", parsed.upiId)
        assertEquals("PAYMENT FOR 901441", parsed.notes)
    }

    @Test
    fun parse_trimsTrailingSpacesInName() {
        val parsed = NarrationParser.parse(
            "UPI-ASHVAJEET  -SUNNYJAAT4724@OKSBI-SBIN0014975-623079421055-UPI",
            "0000623079421055"
        )

        assertEquals("ASHVAJEET", parsed.rawName)
        assertEquals("SUNNYJAAT4724@OKSBI", parsed.upiId)
    }

    @Test
    fun parse_hyphenInBothNameLikeSegmentAndVpa() {
        val parsed = NarrationParser.parse(
            "UPI-AR91922461OKAXIS-AR9192246-1@OKAXIS-UBIN0577537-623682054211-UPI",
            "0000623682054211"
        )

        assertEquals("AR91922461OKAXIS", parsed.rawName)
        assertEquals("AR9192246-1@OKAXIS", parsed.upiId)
        assertEquals("UBIN0577537", parsed.ifsc)
    }

    @Test
    fun parse_keepsInternalDoubleSpaces() {
        val parsed = NarrationParser.parse(
            "UPI-JHPL  HIRISE PDPU-Q922447010@YBL-YESB0YBLUPI-127925871086-UPI",
            "0000127925871086"
        )

        assertEquals("JHPL  HIRISE PDPU", parsed.rawName)
    }

    @Test
    fun parse_fallsBackToNarrationWhenRefColumnIsUnusable() {
        val parsed = NarrationParser.parse(
            "UPI-JIO-RELIANCERETAIL2.EASEBUZZ@KOTAKPAY-KKBK0JPUPIA-126864903466-PAY",
            ""
        )

        assertEquals("126864903466", parsed.upiRefId)
        assertEquals("RELIANCERETAIL2.EASEBUZZ@KOTAKPAY", parsed.upiId)
    }

    @Test
    fun parse_upiReversalIsNotAUpiRow() {
        val parsed = NarrationParser.parse("UPIRET-24072026- 126847085900", "000000000000000")

        assertFalse(parsed.isUpi)
        assertNull(parsed.rawName)
        assertNull(parsed.upiId)
    }

    @Test
    fun parse_debitCardStandingInstructionIsNotAUpiRow() {
        val parsed = NarrationParser.parse(
            "ME DC SI 416021XXXXXX3379 ANTHROPIC* CLAUDE SUB",
            "0000624321476587"
        )

        assertFalse(parsed.isUpi)
        assertNull(parsed.rawName)
    }

    @Test
    fun parse_thirdPartyTransferWithHyphensIsNotAUpiRow() {
        val parsed = NarrationParser.parse(
            "04161500000750-TPT-RICK FARE BAL AC-RAGHAVAN SHIVAKUMAR",
            "0000000270570737"
        )

        assertFalse(parsed.isUpi)
    }

    @Test
    fun parse_achCreditIsNotAUpiRow() {
        assertFalse(NarrationParser.parse("ACH C- ITC LIMITED-2834344", "0000002508902808").isUpi)
    }

    @Test
    fun normaliseRefNo_dropsBlankAndAllZeroFiller() {
        assertNull(NarrationParser.normaliseRefNo(null))
        assertNull(NarrationParser.normaliseRefNo("   "))
        assertNull(NarrationParser.normaliseRefNo("000000000000000"))
        assertEquals("0000126864903466", NarrationParser.normaliseRefNo(" 0000126864903466 "))
    }

    @Test
    fun rupeesToPaise_roundsInsteadOfTruncating() {
        assertEquals(1999L, rupeesToPaise(19.99))
        assertEquals(4400L, rupeesToPaise(44.0))
        assertEquals(31800L, rupeesToPaise(318.0))
        assertEquals(1L, rupeesToPaise(0.01))
        assertEquals(0L, rupeesToPaise(0.0))
    }

    @Test
    fun upiRefIdFromRefNo_stripsZeroPadding() {
        assertEquals("126864903466", NarrationParser.upiRefIdFromRefNo("0000126864903466"))
        assertNull(NarrationParser.upiRefIdFromRefNo("EPR2722331142690"))
        assertNull(NarrationParser.upiRefIdFromRefNo("000000000000000"))
    }
}
