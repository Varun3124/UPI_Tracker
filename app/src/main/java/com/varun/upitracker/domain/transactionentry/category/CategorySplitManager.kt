package com.varun.upitracker.domain.transactionentry.category

import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.domain.transactionentry.share.CategoryTargeting
import com.varun.upitracker.ui.ActorType

data class CategoryVisibilityDecision(
    val showCategories: Boolean,
    val shouldClearSelections: Boolean,
    val kind: CategoryKind
)

class CategorySplitManager {

    /**
     * [targeting] already encodes the merchant/ledger-neutral gate, so this only has to act on
     * whether there is anything to attribute.
     */
    fun visibilityDecision(targeting: CategoryTargeting): CategoryVisibilityDecision {
        val show = targeting.sharePaise > 0L
        return CategoryVisibilityDecision(
            showCategories = show,
            shouldClearSelections = !show,
            kind = targeting.kind
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
