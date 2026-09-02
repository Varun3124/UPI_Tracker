package com.varun.upitracker.domain

import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransferTypeResolverTest {

    @Test
    fun resolve_cashToSavingsIsDeposit() {
        assertResolved(AccountTransferType.CASH_DEPOSIT, AccountType.CASH, AccountType.SAVINGS)
    }

    @Test
    fun resolve_savingsToCashIsWithdrawal() {
        assertResolved(AccountTransferType.CASH_WITHDRAWAL, AccountType.SAVINGS, AccountType.CASH)
    }

    @Test
    fun resolve_fdSourceIsReturn() {
        assertResolved(AccountTransferType.FD_RETURN, AccountType.FD, AccountType.SAVINGS)
    }

    @Test
    fun resolve_fdToCashIsReturnNotWithdrawal() {
        // Clause ordering: the FD source must win over the CASH destination.
        assertResolved(AccountTransferType.FD_RETURN, AccountType.FD, AccountType.CASH)
    }

    @Test
    fun resolve_fdDestinationIsUnsupportedWhenPrincipalMoves() {
        // Guards against ever deriving FD_BOOKING, which createFixedDeposit already emits.
        AccountType.entries.forEach { from ->
            val result = AccountTransferTypeResolver.resolve(from, AccountType.FD, 50_000, 50_000)
            assertTrue(
                "expected $from -> FD to be unsupported but was $result",
                result is TransferTypeResolution.Unsupported
            )
        }
    }

    @Test
    fun resolve_zeroSourceIntoSavingsIsInterestCredit() {
        // The monthly-interest case: savings 0 -> savings 200.
        assertResolved(
            AccountTransferType.SAVINGS_INTEREST_CREDIT,
            AccountType.SAVINGS,
            AccountType.SAVINGS,
            amountFrom = 0,
            amountTo = 20_000
        )
    }

    @Test
    fun resolve_zeroSourceIntoFdIsInterestCreditNotUnsupported() {
        // An FD credit with nothing leaving is interest accruing, not a booking.
        assertResolved(
            AccountTransferType.FD_INTEREST_CREDIT,
            AccountType.FD,
            AccountType.FD,
            amountFrom = 0,
            amountTo = 20_000
        )
    }

    @Test
    fun resolve_zeroSourceIntoCashIsGeneric() {
        assertResolved(
            AccountTransferType.GENERIC_TRANSFER,
            AccountType.CASH,
            AccountType.CASH,
            amountFrom = 0,
            amountTo = 20_000
        )
    }

    @Test
    fun resolve_zeroDestinationIsNotTreatedAsCredit() {
        // Money leaving with nothing arriving is a charge, and keeps the account-type mapping.
        assertResolved(
            AccountTransferType.CASH_DEPOSIT,
            AccountType.CASH,
            AccountType.SAVINGS,
            amountFrom = 5_000,
            amountTo = 0
        )
    }

    @Test
    fun resolve_uninvestedToInvestedIsBuy() {
        assertResolved(
            AccountTransferType.INVESTMENT_BUY,
            AccountType.INVESTMENT_UNINVESTED,
            AccountType.INVESTMENT_INVESTED
        )
    }

    @Test
    fun resolve_investedToUninvestedIsSell() {
        assertResolved(
            AccountTransferType.INVESTMENT_SELL,
            AccountType.INVESTMENT_INVESTED,
            AccountType.INVESTMENT_UNINVESTED
        )
    }

    @Test
    fun resolve_savingsToInvestedIsGeneric() {
        assertResolved(
            AccountTransferType.GENERIC_TRANSFER,
            AccountType.SAVINGS,
            AccountType.INVESTMENT_INVESTED
        )
    }

    private fun assertResolved(
        expected: AccountTransferType,
        from: AccountType,
        to: AccountType,
        amountFrom: Long = 50_000,
        amountTo: Long = 50_000
    ) {
        val result = AccountTransferTypeResolver.resolve(from, to, amountFrom, amountTo)
        assertTrue(
            "expected $from -> $to to resolve but was $result",
            result is TransferTypeResolution.Resolved
        )
        assertEquals(expected, (result as TransferTypeResolution.Resolved).type)
    }
}
