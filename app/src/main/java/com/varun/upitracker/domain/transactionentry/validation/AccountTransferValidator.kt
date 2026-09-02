package com.varun.upitracker.domain.transactionentry.validation

data class TransferValidationInput(
    val fromAccountId: String?,
    val toAccountId: String?,
    val amountFromPaise: Long,
    val amountToPaise: Long
)

/**
 * Validates a ME -> ME account transfer entered on the transaction entry screen. Kept separate from
 * [TransactionValidator] so each save path in `handleDone` has its own validator and neither class
 * needs the other's inputs.
 */
class AccountTransferValidator {

    /**
     * Source and destination may be the same account, and either leg may be zero - that is how a
     * credit or charge against a single account is recorded (savings 0 -> savings 200 for monthly
     * interest). Only a row that moves nothing at all is rejected.
     */
    fun validate(input: TransferValidationInput): ValidationResult {
        if (input.fromAccountId.isNullOrBlank()) {
            return ValidationResult.invalid("Pick a source account")
        }
        if (input.toAccountId.isNullOrBlank()) {
            return ValidationResult.invalid("Pick a destination account")
        }
        if (input.amountFromPaise < 0L || input.amountToPaise < 0L) {
            return ValidationResult.invalid("Amounts can't be negative")
        }
        if (input.amountFromPaise == 0L && input.amountToPaise == 0L) {
            return ValidationResult.invalid("Enter a transfer amount")
        }
        return ValidationResult.valid()
    }
}
