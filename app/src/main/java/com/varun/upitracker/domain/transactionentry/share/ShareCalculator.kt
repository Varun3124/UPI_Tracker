package com.varun.upitracker.domain.transactionentry.share

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.ui.ActorType

enum class SectionBalanceState {
    BALANCED,
    OVER,
    REMAINING
}

data class SectionBalanceResult(
    val state: SectionBalanceState,
    val deltaPaise: Long
)

enum class OverallAllocationState {
    OVER_ALLOCATED,
    PAYER_UNALLOCATED,
    PAYEE_UNALLOCATED,
    UNALLOCATED,
    BALANCED
}

data class OverallAllocationResult(
    val state: OverallAllocationState,
    val deltaPaise: Long
)

/** How much ME can categorise on a transaction, and which direction those categories measure. */
data class CategoryTargeting(
    val sharePaise: Long,
    val kind: CategoryKind
)

class ShareCalculator {

    fun suggestShareAmount(totalPaise: Long, existingRowAmounts: List<Long>): Long {
        val remaining = (totalPaise - existingRowAmounts.sum()).coerceAtLeast(0L)
        return if (remaining > 0L) remaining else totalPaise
    }

    fun computeSectionBalance(totalPaise: Long, summedPaise: Long): SectionBalanceResult {
        return when {
            summedPaise == totalPaise -> SectionBalanceResult(SectionBalanceState.BALANCED, 0L)
            summedPaise > totalPaise -> SectionBalanceResult(SectionBalanceState.OVER, summedPaise - totalPaise)
            else -> SectionBalanceResult(SectionBalanceState.REMAINING, totalPaise - summedPaise)
        }
    }

    fun computeOverallAllocation(
        amountPaise: Long,
        payerActorType: String,
        payeeActorType: String,
        payerSummedPaise: Long,
        payeeSummedPaise: Long
    ): OverallAllocationResult {
        // A merchant side has no share rows of its own; it absorbs the whole amount,
        // so treat it as always matching the total instead of comparing its 0 sentinel.
        val effectivePayerSummed = if (payerActorType == ActorType.MERCHANT) amountPaise else payerSummedPaise
        val effectivePayeeSummed = if (payeeActorType == ActorType.MERCHANT) amountPaise else payeeSummedPaise

        val maxOver = maxOf(effectivePayerSummed - amountPaise, effectivePayeeSummed - amountPaise, 0L)

        return when {
            maxOver > 0L -> OverallAllocationResult(OverallAllocationState.OVER_ALLOCATED, maxOver)
            effectivePayerSummed < amountPaise -> {
                OverallAllocationResult(OverallAllocationState.PAYER_UNALLOCATED, amountPaise - effectivePayerSummed)
            }
            effectivePayeeSummed < amountPaise -> {
                OverallAllocationResult(OverallAllocationState.PAYEE_UNALLOCATED, amountPaise - effectivePayeeSummed)
            }
            amountPaise <= 0L -> OverallAllocationResult(OverallAllocationState.UNALLOCATED, 0L)
            else -> OverallAllocationResult(OverallAllocationState.BALANCED, 0L)
        }
    }

    /**
     * How much of this transaction ME can attribute to categories, and which direction those
     * categories measure.
     *
     * Zero means the transaction is not categorisable: a plain loan to a friend moves debt around
     * but consumes nothing.
     */
    fun categoryTargeting(
        payerActorType: String,
        payeeActorType: String,
        ledgerEffect: LedgerEffect,
        isLinkedRefund: Boolean,
        payerMeSharePaise: Long,
        payeeMeSharePaise: Long
    ): CategoryTargeting {
        val merchantInvolved = payerActorType == ActorType.MERCHANT || payeeActorType == ActorType.MERCHANT
        if (!merchantInvolved && ledgerEffect != LedgerEffect.NONE) {
            return CategoryTargeting(0L, CategoryKind.EXPENSE)
        }

        // A refund puts ME on the payee side, but it is a negative expense, not income: its pills
        // are the original purchase's expense categories.
        if (isLinkedRefund) return CategoryTargeting(payeeMeSharePaise, CategoryKind.EXPENSE)

        // Pick a side rather than summing. ME can hold a share on both sides at once -- the "Me"
        // option is offered per side -- and summing would both double-count and leave the
        // direction ambiguous.
        return if (payerMeSharePaise > 0L) {
            CategoryTargeting(payerMeSharePaise, CategoryKind.EXPENSE)
        } else {
            CategoryTargeting(payeeMeSharePaise, CategoryKind.INCOME)
        }
    }
}
