package com.varun.upitracker.domain

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The user's savings account is "hdfc"; "zerodha" is where the money went or came from. */
class ImportedTransferMatcherTest {

    private val day = 86_400_000L
    private val at = 1_700_000_000_000L

    private fun transfer(
        id: String = "t1",
        from: String? = "hdfc",
        to: String? = "zerodha",
        amountFrom: Long = 500_000L,
        amountTo: Long = 500_000L,
        dateEpoch: Long = at,
        source: EntrySource = EntrySource.SMS,
        statementRefNo: String? = null,
        upiRefId: String? = null
    ) = AccountTransfer(
        id = id,
        fromAccountId = from,
        toAccountId = to,
        amountFromPaise = amountFrom,
        amountToPaise = amountTo,
        type = AccountTransferType.entries.first(),
        dateEpoch = dateEpoch,
        source = source,
        statementRefNo = statementRefNo,
        upiRefId = upiRefId
    )

    private fun pick(
        transfers: List<AccountTransfer>,
        amountPaise: Long = 500_000L,
        isDebit: Boolean = true,
        accountIds: List<String> = listOf("hdfc"),
        allowStatementRef: Boolean = true,
        claimedIds: Set<String> = emptySet()
    ) = ImportedTransferMatcher.pick(
        transfers = transfers,
        amountPaise = amountPaise,
        isDebit = isDebit,
        accountIds = accountIds,
        fromEpoch = at - day,
        toEpoch = at + day,
        atEpoch = at,
        allowStatementRef = allowStatementRef,
        claimedIds = claimedIds
    )

    @Test
    fun `a debit matches the leg that left the account`() {
        assertEquals("t1", pick(listOf(transfer()))?.id)
    }

    @Test
    fun `a credit matches the leg that reached the account`() {
        val incoming = transfer(from = "zerodha", to = "hdfc")
        assertEquals("t1", pick(listOf(incoming), isDebit = false)?.id)
        // The same transfer read as money leaving hdfc is the wrong direction.
        assertNull(pick(listOf(incoming), isDebit = true))
    }

    @Test
    fun `each leg is checked with its own amount`() {
        // A 10 rupee fee: 5000 left hdfc, 4990 reached zerodha.
        val withFee = transfer(amountFrom = 500_000L, amountTo = 499_000L)
        assertEquals("t1", pick(listOf(withFee), amountPaise = 500_000L)?.id)
        assertNull(pick(listOf(withFee), amountPaise = 499_000L))
    }

    @Test
    fun `another account or amount does not match`() {
        assertNull(pick(listOf(transfer(from = "icici"))))
        assertNull(pick(listOf(transfer(amountFrom = 400_000L))))
    }

    @Test
    fun `a transfer typed in by hand is never matched`() {
        assertNull(pick(listOf(transfer(source = EntrySource.MANUAL))))
    }

    @Test
    fun `every import channel's conversions can be matched`() {
        listOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.BANK_STATEMENT).forEach { source ->
            assertEquals(source.name, "t1", pick(listOf(transfer(source = source)))?.id)
        }
    }

    @Test
    fun `a transfer that already holds a UPI reference is taken`() {
        assertNull(pick(listOf(transfer(upiRefId = "123456789012"))))
    }

    @Test
    fun `a statement reference blocks only when the caller says so`() {
        val fromStatement = transfer(source = EntrySource.BANK_STATEMENT, statementRefNo = "0000123")
        assertEquals("t1", pick(listOf(fromStatement), allowStatementRef = true)?.id)
        assertNull(pick(listOf(fromStatement), allowStatementRef = false))
    }

    @Test
    fun `the window is inclusive at both ends and closed beyond them`() {
        assertEquals("early", pick(listOf(transfer(id = "early", dateEpoch = at - day)))?.id)
        assertEquals("late", pick(listOf(transfer(id = "late", dateEpoch = at + day)))?.id)
        assertNull(pick(listOf(transfer(dateEpoch = at - day - 1))))
        assertNull(pick(listOf(transfer(dateEpoch = at + day + 1))))
    }

    @Test
    fun `the nearest in time wins`() {
        val transfers = listOf(
            transfer(id = "far", dateEpoch = at - 20 * 3_600_000L),
            transfer(id = "near", dateEpoch = at + 3_600_000L)
        )
        assertEquals("near", pick(transfers)?.id)
    }

    @Test
    fun `a transfer claimed by an earlier row is left for the next one`() {
        val transfers = listOf(
            transfer(id = "first", dateEpoch = at),
            transfer(id = "second", dateEpoch = at + 3_600_000L)
        )
        assertEquals("second", pick(transfers, claimedIds = setOf("first"))?.id)
        assertNull(pick(transfers, claimedIds = setOf("first", "second")))
    }
}
