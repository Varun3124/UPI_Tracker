package com.varun.upitracker.domain.transactionentry.share

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
        val maxOver = maxOf(payerSummedPaise - amountPaise, payeeSummedPaise - amountPaise, 0L)

        return when {
            maxOver > 0L -> OverallAllocationResult(OverallAllocationState.OVER_ALLOCATED, maxOver)
            payerSummedPaise < amountPaise -> {
                OverallAllocationResult(OverallAllocationState.PAYER_UNALLOCATED, amountPaise - payerSummedPaise)
            }
            payeeSummedPaise < amountPaise -> {
                OverallAllocationResult(OverallAllocationState.PAYEE_UNALLOCATED, amountPaise - payeeSummedPaise)
            }
            amountPaise <= 0L -> OverallAllocationResult(OverallAllocationState.UNALLOCATED, 0L)
            else -> OverallAllocationResult(OverallAllocationState.BALANCED, 0L)
        }
    }

    fun myShareForCategories(
        payerActorType: String,
        payeeActorType: String,
        payerMeSharePaise: Long,
        payeeMeSharePaise: Long
    ): Long {
        return when {
            payerActorType == ActorType.MERCHANT -> payeeMeSharePaise
            payeeActorType == ActorType.MERCHANT -> payerMeSharePaise
            else -> 0L
        }
    }
}
