package com.varun.upitracker.ui

import android.content.Context
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.util.AmountFormat

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

/**
 * Every name a transaction can be found or labelled by, given name maps already in memory.
 *
 * The two ends' resolved names plus the raw labels the parsers wrote. Both raw labels are kept even
 * where an id resolved: an SMS alias is often what the user remembers typing, and the search box has
 * to find it.
 */
fun Transaction.searchableNames(
    friendNames: Map<Long, String>,
    merchantNames: Map<Long, String>
): List<String?> = listOf(
    payerActorRef().displayName(friendNames, merchantNames),
    payeeActorRef().displayName(friendNames, merchantNames),
    payerRawLabel,
    payeeRawLabel
)

/**
 * [resolveActorDisplayName] without a database, for a list that has already loaded the name maps.
 *
 * A list resolving each row through the suspending version costs one query per row per redraw, which
 * on a filtered list is one query per row per keystroke. The maps are two queries for the whole
 * screen, so every list hands them in instead.
 */
fun ActorRef.displayName(
    friendNames: Map<Long, String>,
    merchantNames: Map<Long, String>
): String = when (actorType) {
    ActorType.ME -> "Me"
    ActorType.FRIEND -> friendId?.let { friendNames[it] } ?: rawLabel ?: "Friend"
    ActorType.MERCHANT -> merchantId?.let { merchantNames[it] } ?: rawLabel ?: "Merchant"
    else -> rawLabel ?: "Unknown"
}

/** [resolvePrimaryDisplay] without a database. See [displayName]. */
fun Transaction.resolvePrimaryDisplay(
    friendNames: Map<Long, String>,
    merchantNames: Map<Long, String>
): String = when {
    payerActorType == ActorType.ME -> payeeActorRef().displayName(friendNames, merchantNames)
    payeeActorType == ActorType.ME -> payerActorRef().displayName(friendNames, merchantNames)
    else -> "${payerActorRef().displayName(friendNames, merchantNames)} -> " +
        payeeActorRef().displayName(friendNames, merchantNames)
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

/**
 * The theme attribute an amount of this direction is painted with. Kept separate from [color] so a
 * caller holding only a `View` can tint without reaching for a Context.
 */
fun AmountPerspective.colorAttr(): Int = when (this) {
    AmountPerspective.OUTGOING -> ThemeAttr.negative
    AmountPerspective.INCOMING -> ThemeAttr.positive
    AmountPerspective.NEUTRAL -> ThemeAttr.amountNeutral
}

fun AmountPerspective.color(context: Context): Int = context.themeColor(colorAttr())

fun Transaction.perspectiveColor(context: Context): Int = amountPerspective().color(context)

/**
 * The UI layer's name for [AmountFormat.rupees]. Kept because a good many call sites read better
 * with it, but it holds no formatting logic of its own -- the rules, including the grouping
 * threshold, live in one place.
 */
fun formatRupees(paise: Long): String = AmountFormat.rupees(paise)

/** Signed by direction: an amount you paid reads `-`, one you received reads `+`. */
fun Transaction.formatPerspectiveAmount(): String {
    val amount = AmountFormat.rupees(amountPaise)
    return when (amountPerspective()) {
        AmountPerspective.OUTGOING -> "-$amount"
        AmountPerspective.INCOMING -> "+$amount"
        AmountPerspective.NEUTRAL -> amount
    }
}
