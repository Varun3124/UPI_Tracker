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
    fun validate_allowsSelfTransferCreditForMonthlyInterest() {
        // savings 0 -> savings 200: interest arriving with nothing leaving.
        val result = validator.validate(
            input(from = "savings", to = "savings", amountFrom = 0, amountTo = 20_000)
        )

        assertTrue(result.isValid)
    }

    @Test
    fun validate_allowsZeroDestinationForAccountCharge() {
        val result = validator.validate(input(amountFrom = 5_000, amountTo = 0))

        assertTrue(result.isValid)
    }

    @Test
    fun validate_rejectsBothLegsZero() {
        assertInvalid("Enter a transfer amount", input(amountFrom = 0, amountTo = 0))
    }

    @Test
    fun validate_rejectsNegativeAmounts() {
        assertInvalid("Amounts can't be negative", input(amountFrom = -1))
        assertInvalid("Amounts can't be negative", input(amountTo = -1))
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
