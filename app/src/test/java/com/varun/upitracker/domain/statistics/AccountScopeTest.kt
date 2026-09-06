package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountScopeTest {

    private fun account(id: String, type: AccountType, archived: Boolean = false) =
        Account(id = id, type = type, label = id, addedEpoch = 0L, isArchived = archived)

    private val savings = account("savings", AccountType.SAVINGS)
    private val cash = account("cash", AccountType.CASH)
    private val fd = account("fd", AccountType.FD)
    private val invested = account("invested", AccountType.INVESTMENT_INVESTED)
    private val oldSavings = account("old-savings", AccountType.SAVINGS, archived = true)

    private val accounts = listOf(savings, cash, fd, invested, oldSavings)

    @Test
    fun liquidIsCashAndSavingsOnly() {
        assertEquals(setOf("savings", "cash"), AccountScope.Liquid.resolve(accounts))
    }

    @Test
    fun totalReachesDepositsAndHoldings() {
        assertEquals(setOf("savings", "cash", "fd", "invested"), AccountScope.Total.resolve(accounts))
    }

    /**
     * Liquid has to match the balance row on the transactions screen, which is built from active
     * accounts; Total follows the same rule so the two cannot disagree about what an account is.
     */
    @Test
    fun neitherPolicyScopeCountsAnArchivedAccount() {
        listOf(AccountScope.Liquid, AccountScope.Total).forEach { scope ->
            assertEquals("$scope", false, oldSavings.id in scope.resolve(accounts))
        }
    }

    /** Naming an account outright is a different act from describing a set of them. */
    @Test
    fun namingAnArchivedAccountHonoursIt() {
        assertEquals(setOf("old-savings"), AccountScope.Single("old-savings").resolve(accounts))
        assertEquals(
            setOf("old-savings", "cash"),
            AccountScope.Custom(setOf("old-savings", "cash")).resolve(accounts)
        )
    }

    /** A stored custom scope outlives the account it names. */
    @Test
    fun anIdThatNoLongerExistsIsDroppedRatherThanThrowing() {
        assertEquals(setOf("cash"), AccountScope.Custom(setOf("cash", "deleted")).resolve(accounts))
        assertEquals(emptySet<String>(), AccountScope.Single("deleted").resolve(accounts))
    }

    @Test
    fun anEmptyCustomScopeIsEmptyRatherThanEverything() {
        assertEquals(emptySet<String>(), AccountScope.Custom(emptySet()).resolve(accounts))
    }

    @Test
    fun noAccountsAtAllResolvesEmptyForEveryScope() {
        listOf(
            AccountScope.Liquid,
            AccountScope.Total,
            AccountScope.Single("savings"),
            AccountScope.Custom(setOf("savings"))
        ).forEach { scope ->
            assertEquals("$scope", emptySet<String>(), scope.resolve(emptyList()))
        }
    }
}
