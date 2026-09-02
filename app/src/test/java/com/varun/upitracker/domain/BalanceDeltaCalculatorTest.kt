package com.varun.upitracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceDeltaCalculatorTest {

    @Test
    fun transferDelta_usesAsymmetricInvestmentSaleAmounts() {
        val transfer = TransferDeltaInput(
            fromAccountId = "invested",
            toAccountId = "uninvested",
            amountFromPaise = 10_000,
            amountToPaise = 12_500
        )

        assertEquals(-10_000, BalanceDeltaCalculator.transferDelta("invested", transfer))
        assertEquals(12_500, BalanceDeltaCalculator.transferDelta("uninvested", transfer))
    }

    @Test
    fun transferDelta_subtractsFdReturnPrincipalAndCreditsActualPayout() {
        val transfer = TransferDeltaInput(
            fromAccountId = "fd",
            toAccountId = "savings",
            amountFromPaise = 50_000,
            amountToPaise = 49_500
        )

        assertEquals(-50_000, BalanceDeltaCalculator.transferDelta("fd", transfer))
        assertEquals(49_500, BalanceDeltaCalculator.transferDelta("savings", transfer))
    }

    @Test
    fun expenseDelta_isZeroWhenLegsMatch() {
        val transfer = TransferDeltaInput("savings", "cash", 50_000, 50_000)

        assertEquals(0, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_reportsWithdrawalFee() {
        val transfer = TransferDeltaInput("savings", "cash", 50_000, 49_500)

        assertEquals(500, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_isNegativeOnInvestmentGain() {
        val transfer = TransferDeltaInput("invested", "uninvested", 10_000, 12_500)

        assertEquals(-2_500, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_treatsUntrackedSourceAsIncome() {
        val transfer = TransferDeltaInput(null, "savings", 0, 5_000)

        assertEquals(-5_000, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_treatsUntrackedDestinationAsSpend() {
        val transfer = TransferDeltaInput("savings", null, 5_000, 0)

        assertEquals(5_000, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun transferDelta_selfTransferCreditsInterestOnce() {
        // savings 0 -> savings 200. Both branches fire on the same id, and the row is returned
        // once by getAccountTransfersBetween's OR, so the balance rises by exactly the credit.
        val transfer = TransferDeltaInput("savings", "savings", 0, 20_000)

        assertEquals(20_000, BalanceDeltaCalculator.transferDelta("savings", transfer))
    }

    @Test
    fun expenseDelta_countsInterestCreditAsIncome() {
        val transfer = TransferDeltaInput("savings", "savings", 0, 20_000)

        assertEquals(-20_000, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_countsSelfChargeAsSpend() {
        val transfer = TransferDeltaInput("savings", "savings", 5_000, 0)

        assertEquals(5_000, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun expenseDelta_isNegatedSumOfTransferDeltasOverBothAccounts() {
        val transfer = TransferDeltaInput("savings", "cash", 50_000, 49_500)

        val holdingsChange = BalanceDeltaCalculator.transferDelta("savings", transfer) +
            BalanceDeltaCalculator.transferDelta("cash", transfer)

        assertEquals(-holdingsChange, BalanceDeltaCalculator.expenseDelta(transfer))
    }

    @Test
    fun transactionDelta_payerIsMeDebitsTheAccount() {
        val transaction = TransactionDeltaInput("savings", "ME", "MERCHANT", 15_000)

        assertEquals(-15_000, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    @Test
    fun transactionDelta_payeeIsMeCreditsTheAccount() {
        val transaction = TransactionDeltaInput("savings", "MERCHANT", "ME", 15_000)

        assertEquals(15_000, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    /** The gate the spend query applies. Money paid to a friend does leave the bank. */
    @Test
    fun transactionDelta_countsFriendTransactionsToo() {
        val transaction = TransactionDeltaInput("savings", "ME", "FRIEND", 50_000)

        assertEquals(-50_000, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    /** Statement and SMS rows arrive unresolved and shareless; they still moved the balance. */
    @Test
    fun transactionDelta_countsUnresolvedCounterparties() {
        val transaction = TransactionDeltaInput("savings", "ME", "UNKNOWN", 23_990)

        assertEquals(-23_990, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    @Test
    fun transactionDelta_ignoresAnotherAccountsTransaction() {
        val transaction = TransactionDeltaInput("cash", "ME", "MERCHANT", 15_000)

        assertEquals(0, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    @Test
    fun transactionDelta_ignoresATransactionWithNoAccount() {
        val transaction = TransactionDeltaInput(null, "ME", "MERCHANT", 15_000)

        assertEquals(0, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }

    /** A ME -> ME row is an unconverted transfer; the transfer carries the delta. */
    @Test
    fun transactionDelta_ignoresMeToMe() {
        val transaction = TransactionDeltaInput("savings", "ME", "ME", 15_000)

        assertEquals(0, BalanceDeltaCalculator.transactionDelta("savings", transaction))
    }
}
