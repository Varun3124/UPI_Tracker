package com.varun.upitracker.domain

import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceDeltaCalculatorTest {

    @Test
    fun transactionDelta_subtractsWhenUserPays() {
        val delta = BalanceDeltaCalculator.transactionDelta(
            TransactionDeltaInput(
                amountPaise = 12_300,
                payerActorType = ActorType.ME,
                payeeActorType = ActorType.MERCHANT
            )
        )

        assertEquals(-12_300, delta)
    }

    @Test
    fun transactionDelta_addsWhenUserReceives() {
        val delta = BalanceDeltaCalculator.transactionDelta(
            TransactionDeltaInput(
                amountPaise = 7_500,
                payerActorType = ActorType.FRIEND,
                payeeActorType = ActorType.ME
            )
        )

        assertEquals(7_500, delta)
    }

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
}
