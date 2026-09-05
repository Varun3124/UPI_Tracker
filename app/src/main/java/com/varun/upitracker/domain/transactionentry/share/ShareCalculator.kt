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

        // Direction comes from the transaction's shape, never from which side happens to carry a
        // non-zero share. Amounts are typed after the actors are picked, so reading the direction
        // off them made a fresh merchant purchase offer INCOME categories until the first
        // keystroke, then silently swap them for EXPENSE ones.
        val kind = when {
            // A refund puts ME on the payee side, but it is an expense running backwards: its
            // pills are the purchase's own expense categories.
            isLinkedRefund -> CategoryKind.EXPENSE
            payeeActorType == ActorType.MERCHANT -> CategoryKind.EXPENSE
            payerActorType == ActorType.MERCHANT -> CategoryKind.INCOME
            payerActorType == ActorType.ME -> CategoryKind.EXPENSE
            payeeActorType == ActorType.ME -> CategoryKind.INCOME
            // Neither side is ME or a merchant: ME is only a secondary sharer, so fall back to
            // whichever side actually carries ME's money.
            payerMeSharePaise > 0L -> CategoryKind.EXPENSE
            else -> CategoryKind.INCOME
        }

        // Pick a side rather than summing. ME can hold a share on both sides at once -- the "Me"
        // option is offered per side -- and summing would double-count.
        val sharePaise = if (kind == CategoryKind.EXPENSE && !isLinkedRefund) {
            payerMeSharePaise
        } else {
            payeeMeSharePaise
        }
        return CategoryTargeting(sharePaise, kind)
    }
}
