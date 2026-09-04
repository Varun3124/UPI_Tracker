package com.varun.upitracker.domain.transactionentry.category

import com.varun.upitracker.ui.ActorType

data class CategoryVisibilityDecision(
    val showCategories: Boolean,
    val shouldClearSelections: Boolean
)

class CategorySplitManager {

    fun visibilityDecision(
        payerActorType: String,
        payeeActorType: String,
        mySharePaise: Long
    ): CategoryVisibilityDecision {
        val merchantInvolved = payerActorType == ActorType.MERCHANT || payeeActorType == ActorType.MERCHANT
        val show = merchantInvolved && mySharePaise > 0L
        return CategoryVisibilityDecision(
            showCategories = show,
            shouldClearSelections = !show
        )
    }

    fun selectedMerchantId(
        payerActorType: String,
        payeeActorType: String,
        payerMerchantId: Long?,
        payeeMerchantId: Long?
    ): Long? {
        return when {
            payerActorType == ActorType.MERCHANT -> payerMerchantId
            payeeActorType == ActorType.MERCHANT -> payeeMerchantId
            else -> null
        }
    }

}
