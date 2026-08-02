package com.varun.upitracker.domain.transactionentry.actor

import com.varun.upitracker.ui.ActorType

data class ActorToggleTransition(
    val actorType: String,
    val friendId: Long?,
    val merchantId: Long?,
    val shouldClearShares: Boolean,
    val shouldSeedBaseShare: Boolean
)

class ActorSelectionService {

    fun onActorTypeSelected(
        selectedType: String,
        currentActorType: String,
        otherSideActorType: String,
        currentFriendId: Long?,
        currentMerchantId: Long?
    ): ActorToggleTransition {
        if (selectedType == currentActorType) {
            return ActorToggleTransition(
                actorType = currentActorType,
                friendId = currentFriendId,
                merchantId = currentMerchantId,
                shouldClearShares = false,
                shouldSeedBaseShare = false
            )
        }

        val friendId = when (selectedType) {
            ActorType.FRIEND -> currentFriendId
            else -> null
        }
        val merchantId = when (selectedType) {
            ActorType.MERCHANT -> currentMerchantId
            else -> null
        }

        return ActorToggleTransition(
            actorType = selectedType,
            friendId = friendId,
            merchantId = merchantId,
            shouldClearShares = true,
            shouldSeedBaseShare = true
        )
    }

    fun onMerchantToggleChanged(
        isMerchant: Boolean,
        currentActorType: String,
        otherSideActorType: String,
        currentFriendId: Long?,
        currentMerchantId: Long?
    ): ActorToggleTransition {
        val selectedType = if (isMerchant) ActorType.MERCHANT else {
            if (currentActorType == ActorType.MERCHANT) {
                if (otherSideActorType == ActorType.ME) ActorType.FRIEND else ActorType.ME
            } else {
                currentActorType
            }
        }
        return onActorTypeSelected(
            selectedType = selectedType,
            currentActorType = currentActorType,
            otherSideActorType = otherSideActorType,
            currentFriendId = currentFriendId,
            currentMerchantId = currentMerchantId
        )
    }
}
