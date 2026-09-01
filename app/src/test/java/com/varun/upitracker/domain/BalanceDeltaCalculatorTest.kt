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
    fun expenseDelta_isNegatedSumOfTransferDeltasOverBothAccounts() {
        val transfer = TransferDeltaInput("savings", "cash", 50_000, 49_500)

        val holdingsChange = BalanceDeltaCalculator.transferDelta("savings", transfer) +
            BalanceDeltaCalculator.transferDelta("cash", transfer)

        assertEquals(-holdingsChange, BalanceDeltaCalculator.expenseDelta(transfer))
    }
}
