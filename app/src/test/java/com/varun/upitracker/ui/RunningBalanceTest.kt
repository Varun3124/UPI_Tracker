package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-card running balance on All Transactions. Entries arrive newest-first, the way the
 * screen shows them, and accumulation runs oldest-first underneath.
 */
class RunningBalanceTest {

    @Test
    fun accumulatesOldestFirstFromTheOpeningBalance() {
        val entries = listOf(
            debit(id = 3, epoch = 300, amountPaise = 1_000),
            credit(id = 2, epoch = 200, amountPaise = 5_000),
            debit(id = 1, epoch = 100, amountPaise = 2_000)
        )

        val balances = runningBalances(entries, setOf(SAVINGS), openingPaise = 10_000)

        assertEquals(8_000L, balances["T:1"])
        assertEquals(13_000L, balances["T:2"])
        assertEquals(12_000L, balances["T:3"])
    }

    @Test
    fun closingEqualsOpeningPlusEveryDelta() {
        val entries = listOf(
            debit(id = 2, epoch = 200, amountPaise = 3_300),
            credit(id = 1, epoch = 100, amountPaise = 1_200)
        )

        val balances = runningBalances(entries, setOf(SAVINGS), openingPaise = 50_000)

        // The newest entry carries the closing balance the summary row shows.
        assertEquals(50_000L - 3_300L + 1_200L, balances[entries.first().stableId()])
    }

    /** Pending rows have no shares, and used to contribute nothing to any balance. */
    @Test
    fun countsPendingTransactions() {
        val entries = listOf(debit(id = 1, epoch = 100, amountPaise = 4_000, isPending = true))

        val balances = runningBalances(entries, setOf(SAVINGS), openingPaise = 10_000)

        assertEquals(6_000L, balances["T:1"])
    }

    /** A transaction the reviewer never attributed to an account cannot move one. */
    @Test
    fun ignoresTransactionsWithNoAccount() {
        val entries = listOf(debit(id = 1, epoch = 100, amountPaise = 4_000, accountId = null))

        val balances = runningBalances(entries, setOf(SAVINGS), openingPaise = 10_000)

        assertEquals(10_000L, balances["T:1"])
    }

    /** Under a combined cash + savings scope, moving money between them changes nothing. */
    @Test
    fun transferBetweenTwoInScopeAccountsNetsToZero() {
        val entries = listOf(transfer(id = "t1", epoch = 100, amountPaise = 20_000))

        val combined = runningBalances(entries, setOf(SAVINGS, CASH), openingPaise = 60_000)
        assertEquals(60_000L, combined["X:t1"])

        val savingsOnly = runningBalances(entries, setOf(SAVINGS), openingPaise = 60_000)
        assertEquals(40_000L, savingsOnly["X:t1"])
    }

    /**
     * The screen filters after accumulating, so a hidden entry still moves the balance of the cards
     * around it. Accumulating over the filtered list instead would silently drop its movement.
     */
    @Test
    fun hiddenEntriesStillMoveTheBalanceOfVisibleOnes() {
        val all = listOf(
            debit(id = 3, epoch = 300, amountPaise = 1_000, isPending = true),
            debit(id = 2, epoch = 200, amountPaise = 7_000),
            debit(id = 1, epoch = 100, amountPaise = 2_000, isPending = true)
        )

        val balances = runningBalances(all, setOf(SAVINGS), openingPaise = 20_000)
        val visible = all.filter { (it as LedgerEntry.Tx).transaction.isPending }

        assertEquals(18_000L, balances[visible.last().stableId()])
        // Jumps by 8_000, not the 1_000 on the card: entry 2 is hidden but still spent.
        assertEquals(10_000L, balances[visible.first().stableId()])
    }

    private fun debit(
        id: Long,
        epoch: Long,
        amountPaise: Long,
        isPending: Boolean = false,
        accountId: String? = SAVINGS
    ) = LedgerEntry.Tx(
        Transaction(
            id = id,
            amountPaise = amountPaise,
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.MERCHANT,
            myAccountId = accountId,
            dateEpoch = epoch,
            source = "MANUAL",
            isPending = isPending
        )
    )

    private fun credit(id: Long, epoch: Long, amountPaise: Long) = LedgerEntry.Tx(
        Transaction(
            id = id,
            amountPaise = amountPaise,
            payerActorType = ActorType.MERCHANT,
            payeeActorType = ActorType.ME,
            myAccountId = SAVINGS,
            dateEpoch = epoch,
            source = "MANUAL"
        )
    )

    private fun transfer(id: String, epoch: Long, amountPaise: Long) = LedgerEntry.Transfer(
        AccountTransfer(
            id = id,
            fromAccountId = SAVINGS,
            toAccountId = CASH,
            amountFromPaise = amountPaise,
            amountToPaise = amountPaise,
            type = AccountTransferType.CASH_WITHDRAWAL,
            dateEpoch = epoch,
            source = EntrySource.MANUAL
        )
    )

    private companion object {
        const val SAVINGS = "savings"
        const val CASH = "cash"
    }
}
