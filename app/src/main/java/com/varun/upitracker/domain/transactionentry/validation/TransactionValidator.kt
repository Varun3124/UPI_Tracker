package com.varun.upitracker.domain.transactionentry.validation

import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.util.AmountFormat

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
            // ME -> ME is an account transfer, validated by AccountTransferValidator instead.
            val sameActor = payerActorType != ActorType.ME &&
                payerLabel.equals(payeeLabel, ignoreCase = true)
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

    fun validateCategories(
        mandatory: Boolean,
        myShareForCategoriesPaise: Long,
        checkedAmountsPaise: List<Long>
    ): ValidationResult {
        if (!mandatory) return ValidationResult.valid()
        if (checkedAmountsPaise.isEmpty()) {
            return ValidationResult.invalid("Select at least one category")
        }
        if (checkedAmountsPaise.sum() != myShareForCategoriesPaise) {
            return ValidationResult.invalid("Category splits don't add up to your share")
        }
        return ValidationResult.valid()
    }

    /**
     * Checks that no category has been refunded for more than it was spent on.
     *
     * The same check guards both directions, because both are the same statement: a refund must
     * not push a category below zero, and editing a purchase must not drop a category below what
     * has already been refunded against it. Callers just swap what they pass in.
     *
     * This is what keeps every category's net non-negative in every window, which is why the
     * statistics breakdown never has to render a negative slice.
     */
    fun validateRefundCoverage(
        originalAmountsPaise: Map<Long, Long>,
        refundedAmountsPaise: Map<Long, Long>,
        categoryNames: Map<Long, String>
    ): ValidationResult {
        refundedAmountsPaise.forEach { (categoryId, refunded) ->
            if (refunded <= 0L) return@forEach
            val name = categoryNames[categoryId] ?: "this category"
            val original = originalAmountsPaise[categoryId]
                ?: return ValidationResult.invalid("The purchase has nothing under $name to refund.")
            if (refunded > original) {
                return ValidationResult.invalid(
                    "Refunds under $name would exceed the ${formatPaise(original)} spent on it."
                )
            }
        }
        return ValidationResult.valid()
    }

    private fun formatPaise(paise: Long): String = AmountFormat.rupeesExact(paise)
}
