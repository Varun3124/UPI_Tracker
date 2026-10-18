package com.varun.upitracker.domain.dedup

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DuplicateRulesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun tx(
        amountPaise: Long = 50_000L,
        payer: String = ActorType.ME,
        payee: String = ActorType.MERCHANT,
        upiRefId: String? = null,
        statementRefNo: String? = null
    ) = Transaction(
        amountPaise = amountPaise,
        payerActorType = payer,
        payeeActorType = payee,
        dateEpoch = at(2026, 10, 18, 12),
        source = "SMS",
        upiRefId = upiRefId,
        statementRefNo = statementRefNo
    )

    @Test
    fun amountRange_isTenRupeesEitherWayByDefaultAndNeverBelowZero() {
        assertEquals(49_000L..51_000L, DuplicateRules().amountRange(50_000L))
        assertEquals(0L..1_500L, DuplicateRules().amountRange(500L))
        assertEquals(50_000L..50_000L, DuplicateRules(amountTolerancePaise = 0L).amountRange(50_000L))
    }

    @Test
    fun dateRange_countsWholeCalendarDays() {
        val lateEvening = at(2026, 10, 18, 23, 30)

        assertEquals(at(2026, 10, 17)..(at(2026, 10, 20) - 1), DuplicateRules().dateRange(lateEvening, utc))
        // Zero still covers the whole of the day, not just the minute.
        assertEquals(at(2026, 10, 18)..(at(2026, 10, 19) - 1), DuplicateRules(daysEitherSide = 0).dateRange(lateEvening, utc))
    }

    @Test
    fun problemWith_refusesNumbersOutOfRange() {
        assertNull(DuplicateRules.problemWith(1, 1_000L))
        assertNull(DuplicateRules.problemWith(0, 0L))
        assertNotNull(DuplicateRules.problemWith(8, 1_000L))
        assertNotNull(DuplicateRules.problemWith(-1, 1_000L))
        assertNotNull(DuplicateRules.problemWith(1, 100_001L))
    }

    @Test
    fun otherReference_ruleOutsARecordAnotherPaymentAlreadyOwns() {
        assertTrue(DuplicateMatching.carriesOtherReference("UPI1", null, tx(upiRefId = "UPI2")))
        assertTrue(DuplicateMatching.carriesOtherReference(null, "S1", tx(statementRefNo = "S2")))
        assertFalse(DuplicateMatching.carriesOtherReference("UPI1", null, tx(upiRefId = "UPI1")))
        // A record with no reference of its own may well be this payment.
        assertFalse(DuplicateMatching.carriesOtherReference("UPI1", "S1", tx()))
    }

    @Test
    fun meSides_countsAnEndOrAShareOnThatSide() {
        assertEquals(setOf("PAYER"), DuplicateMatching.meSides(tx(), emptyList()))
        assertEquals(setOf("PAYEE"), DuplicateMatching.meSides(tx(payer = ActorType.FRIEND, payee = ActorType.ME), emptyList()))
        val split = TransactionShare(transactionId = 1L, side = "PAYEE", participantType = ActorType.ME, amountPaise = 100L)
        assertEquals(
            setOf("PAYEE"),
            DuplicateMatching.meSides(tx(payer = ActorType.FRIEND, payee = ActorType.MERCHANT), listOf(split))
        )
    }

    @Test
    fun closestFirst_putsTheAgreeingCounterpartyThenTheNearestAmountFirst() {
        data class Candidate(val tx: Transaction, val agrees: Boolean)
        val exact = Candidate(tx(amountPaise = 50_000L), agrees = false)
        val near = Candidate(tx(amountPaise = 50_500L), agrees = false)
        val named = Candidate(tx(amountPaise = 50_900L), agrees = true)

        val sorted = listOf(near, exact, named).sortedWith(
            DuplicateMatching.closestFirst(50_000L, at(2026, 10, 18), { it.tx }, { it.agrees })
        )
        assertEquals(listOf(named, exact, near), sorted)
    }
}
