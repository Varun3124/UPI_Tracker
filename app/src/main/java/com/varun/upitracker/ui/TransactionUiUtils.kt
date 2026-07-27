package com.varun.upitracker.ui

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare

object ActorType {
    const val ME = "ME"
    const val FRIEND = "FRIEND"
    const val MERCHANT = "MERCHANT"
    const val UNKNOWN = "UNKNOWN"
}

data class ActorRef(
    val actorType: String,
    val friendId: Long? = null,
    val merchantId: Long? = null,
    val rawLabel: String? = null
)

suspend fun resolveActorDisplayName(db: AppDatabase, actor: ActorRef): String {
    return when (actor.actorType) {
        ActorType.ME -> "Me"
        ActorType.FRIEND -> actor.friendId
            ?.let { db.friendDao().getFriendById(it)?.name }
            ?: actor.rawLabel
            ?: "Friend"
        ActorType.MERCHANT -> actor.merchantId
            ?.let { db.merchantDao().getMerchantById(it)?.name }
            ?: actor.rawLabel
            ?: "Merchant"
        else -> actor.rawLabel ?: "Unknown"
    }
}

fun myShareFromShares(shares: List<TransactionShare>): TransactionShare? =
    shares.firstOrNull { it.participantType == ActorType.ME }

fun meShareOnSide(shares: List<TransactionShare>, side: String): Long =
    shares.firstOrNull { it.side == side && it.participantType == ActorType.ME }?.amountPaise ?: 0L

fun Transaction.payerActorRef(): ActorRef = ActorRef(
    actorType = payerActorType,
    friendId = payerFriendId,
    merchantId = payerMerchantId,
    rawLabel = payerRawLabel
)

fun Transaction.payeeActorRef(): ActorRef = ActorRef(
    actorType = payeeActorType,
    friendId = payeeFriendId,
    merchantId = payeeMerchantId,
    rawLabel = payeeRawLabel
)

suspend fun Transaction.resolvePrimaryDisplay(db: AppDatabase): String {
    return when {
        payerActorType == ActorType.ME -> resolveActorDisplayName(db, payeeActorRef())
        payeeActorType == ActorType.ME -> resolveActorDisplayName(db, payerActorRef())
        else -> {
            val payer = resolveActorDisplayName(db, payerActorRef())
            val payee = resolveActorDisplayName(db, payeeActorRef())
            "$payer -> $payee"
        }
    }
}

fun Transaction.resolveTypeLabel(): String {
    return when {
        isPending -> "Pending"
        payerActorType == ActorType.MERCHANT || payeeActorType == ActorType.MERCHANT -> "Merchant"
        payerActorType == ActorType.FRIEND || payeeActorType == ActorType.FRIEND -> "Friend"
        else -> "Transaction"
    }
}

enum class AmountPerspective {
    OUTGOING,
    INCOMING,
    NEUTRAL
}

fun Transaction.amountPerspective(): AmountPerspective {
    return when {
        payerActorType == ActorType.ME -> AmountPerspective.OUTGOING
        payeeActorType == ActorType.ME -> AmountPerspective.INCOMING
        else -> AmountPerspective.NEUTRAL
    }
}

fun Transaction.formatPerspectiveAmount(): String {
    val amount = "Rs${"%.0f".format(amountPaise / 100.0)}"
    return when (amountPerspective()) {
        AmountPerspective.OUTGOING -> "-$amount"
        AmountPerspective.INCOMING -> "+$amount"
        AmountPerspective.NEUTRAL -> amount
    }
}
