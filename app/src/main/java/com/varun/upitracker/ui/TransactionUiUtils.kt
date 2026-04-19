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

object ShareSide {
    const val MEANT_TO_PAY = "MEANT_TO_PAY"
    const val MEANT_TO_RECEIVE = "MEANT_TO_RECEIVE"
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

fun deriveLegacyDirection(
    payerActorType: String,
    payeeActorType: String,
    myShareSide: String?,
    mySharePaise: Long
): String {
    return when {
        payerActorType == ActorType.ME -> "DEBIT"
        payeeActorType == ActorType.ME -> "CREDIT"
        mySharePaise > 0 && myShareSide == ShareSide.MEANT_TO_PAY -> "DEBIT"
        mySharePaise > 0 && myShareSide == ShareSide.MEANT_TO_RECEIVE -> "CREDIT"
        else -> "DEBIT"
    }
}

fun myShareFromShares(shares: List<TransactionShare>): TransactionShare? =
    shares.firstOrNull { it.participantType == ActorType.ME }

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

fun Transaction.shouldDefaultSmsDebitToMerchant(): Boolean {
    val observed = observedDirection ?: direction
    return source == "SMS" && isPending && observed == "DEBIT"
}
