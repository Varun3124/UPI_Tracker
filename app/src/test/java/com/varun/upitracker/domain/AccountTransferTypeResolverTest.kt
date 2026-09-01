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
    fun resolve_fdDestinationIsUnsupported() {
        // Guards against ever deriving FD_BOOKING, which createFixedDeposit already emits.
        AccountType.entries.forEach { from ->
            val result = AccountTransferTypeResolver.resolve(from, AccountType.FD)
            assertTrue(
                "expected $from -> FD to be unsupported but was $result",
                result is TransferTypeResolution.Unsupported
            )
        }
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

    private fun assertResolved(expected: AccountTransferType, from: AccountType, to: AccountType) {
        val result = AccountTransferTypeResolver.resolve(from, to)
        assertTrue(
            "expected $from -> $to to resolve but was $result",
            result is TransferTypeResolution.Resolved
        )
        assertEquals(expected, (result as TransferTypeResolution.Resolved).type)
    }
}
