package com.varun.upitracker.domain.transactionentry.validation

import com.varun.upitracker.ui.ActorType

data class ValidationResult(
    val isValid: Boolean,
    val message: String? = null
) {
    companion object {
        fun valid(): ValidationResult = ValidationResult(true)
        fun invalid(message: String): ValidationResult = ValidationResult(false, message)
    }
}

data class ShareValidationRow(
    val key: String,
    val label: String,
    val amountPaise: Long,
    val isPrimary: Boolean
)

class TransactionValidator {

    fun validateActors(
        payerActorType: String,
        payeeActorType: String,
        payerLabel: String,
        payeeLabel: String
    ): ValidationResult {
        if (payerLabel.isBlank()) {
            return ValidationResult.invalid("Enter a payer")
        }
        if (payeeLabel.isBlank()) {
            return ValidationResult.invalid("Enter a payee")
        }
        if (payerActorType == payeeActorType) {
            val sameActor = when (payerActorType) {
                ActorType.ME -> true
                else -> payerLabel.equals(payeeLabel, ignoreCase = true)
            }
            if (sameActor) {
                return ValidationResult.invalid("Payer and payee must be different")
            }
        }
        return ValidationResult.valid()
    }

    fun validateShares(
        amountPaise: Long,
        payerActorType: String,
        payeeActorType: String,
        payerRows: List<ShareValidationRow>,
        payeeRows: List<ShareValidationRow>
    ): ValidationResult {
        fun validateSide(rows: List<ShareValidationRow>, sideLabel: String): ValidationResult {
            if (rows.isEmpty()) return ValidationResult.invalid("Add a $sideLabel share row")
            rows.forEachIndexed { index, row ->
                if (row.label.isBlank()) {
                    return ValidationResult.invalid("Enter an alias for every $sideLabel row")
                }
                if (index > 0 && row.key.isBlank()) {
                    return ValidationResult.invalid("Select a person for every extra $sideLabel row")
                }
            }
            val summed = rows.sumOf { it.amountPaise }
            if (summed != amountPaise) {
                return ValidationResult.invalid("$sideLabel shares don't match total")
            }
            return ValidationResult.valid()
        }

        return validateSide(payerRows, "payer").takeIf { !it.isValid }
            ?: validateSide(payeeRows, "payee")
    }
}
