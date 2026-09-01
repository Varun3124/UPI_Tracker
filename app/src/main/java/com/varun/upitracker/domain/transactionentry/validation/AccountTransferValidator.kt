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

    fun validate(input: TransferValidationInput): ValidationResult {
        if (input.fromAccountId.isNullOrBlank()) {
            return ValidationResult.invalid("Pick a source account")
        }
        if (input.toAccountId.isNullOrBlank()) {
            return ValidationResult.invalid("Pick a destination account")
        }
        if (input.fromAccountId == input.toAccountId) {
            return ValidationResult.invalid("Source and destination must be different")
        }
        if (input.amountFromPaise <= 0L) {
            return ValidationResult.invalid("Enter the amount leaving the source")
        }
        if (input.amountToPaise <= 0L) {
            return ValidationResult.invalid("Enter the amount reaching the destination")
        }
        return ValidationResult.valid()
    }
}
