package com.varun.upitracker.domain.transactionentry.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransferValidatorTest {

    private val validator = AccountTransferValidator()

    @Test
    fun validate_acceptsDistinctAccountsWithPositiveAmounts() {
        val result = validator.validate(input(from = "savings", to = "cash"))

        assertTrue(result.isValid)
    }

    @Test
    fun validate_rejectsMissingSource() {
        assertInvalid("Pick a source account", input(from = null))
        assertInvalid("Pick a source account", input(from = " "))
    }

    @Test
    fun validate_rejectsMissingDestination() {
        assertInvalid("Pick a destination account", input(to = null))
    }

    @Test
    fun validate_rejectsSameAccountOnBothSides() {
        assertInvalid(
            "Source and destination must be different",
            input(from = "savings", to = "savings")
        )
    }

    @Test
    fun validate_rejectsNonPositiveSourceAmount() {
        assertInvalid("Enter the amount leaving the source", input(amountFrom = 0))
    }

    @Test
    fun validate_rejectsNonPositiveDestinationAmount() {
        assertInvalid("Enter the amount reaching the destination", input(amountTo = 0))
    }

    private fun assertInvalid(message: String, input: TransferValidationInput) {
        val result = validator.validate(input)
        assertFalse(result.isValid)
        assertEquals(message, result.message)
    }

    private fun input(
        from: String? = "savings",
        to: String? = "cash",
        amountFrom: Long = 50_000,
        amountTo: Long = 49_500
    ) = TransferValidationInput(from, to, amountFrom, amountTo)
}
