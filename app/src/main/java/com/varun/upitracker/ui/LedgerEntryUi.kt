package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.BalanceDeltaCalculator
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.statistics.BalanceMovement
import com.varun.upitracker.domain.statistics.PayeeRef
import com.varun.upitracker.domain.TransactionDeltaInput
import com.varun.upitracker.domain.TransferDeltaInput
import com.varun.upitracker.util.AmountFormat

/**
 * One row in a chronological ledger list. Transactions and account transfers are stored in separate
 * tables but are shown interleaved on the dashboard and the all-transactions screen.
 */
sealed interface LedgerEntry {
    val dateEpoch: Long

    data class Tx(val transaction: Transaction) : LedgerEntry {
        override val dateEpoch: Long get() = transaction.dateEpoch
    }

    data class Transfer(val transfer: AccountTransfer) : LedgerEntry {
        override val dateEpoch: Long get() = transfer.dateEpoch
    }
}

/** Identifies an entry across the two tables, whose ids are a Long and a UUID string. */
fun LedgerEntry.stableId(): String = when (this) {
    is LedgerEntry.Tx -> "T:${transaction.id}"
    is LedgerEntry.Transfer -> "X:${transfer.id}"
}

/**
 * Whether this entry belongs to [scope], whose accounts resolve to [ids].
 *
 * **A policy scope keeps entries with no account recorded; a named one does not.** Plenty of rows
 * carry no `myAccountId` at all -- an SMS or notification parse has no way to know which account was
 * used -- and Liquid is the default the screen opens on, so excluding them would silently hide a good
 * part of the list. Naming an account is a different act: an unattributed row is not on the account
 * you asked for, and it is left out. Either way the balance row is unaffected, because an entry that
 * moves no account has a [balanceDeltaFor] of zero.
 */
fun LedgerEntry.isInScope(scope: AccountScope, ids: Set<String>): Boolean {
    val named = scope is AccountScope.Single || scope is AccountScope.Custom
    return when (this) {
        is LedgerEntry.Tx -> {
            val account = transaction.myAccountId ?: return !named
            account in ids
        }
        // Either endpoint counts as involvement; an external leg is null and matches nothing.
        is LedgerEntry.Transfer ->
            transfer.fromAccountId in ids || transfer.toAccountId in ids
    }
}

/**
 * Whether the figure this row shows falls within `[minPaise, maxPaise]`, either end optional.
 *
 * Compared against what is on screen rather than against a signed amount: the bounds are typed into
 * a field labelled with a rupee symbol, and a user asking for "over 500" means the number they can
 * see, not a direction.
 */
fun LedgerEntry.matchesAmount(minPaise: Long?, maxPaise: Long?): Boolean {
    if (minPaise == null && maxPaise == null) return true
    val shown = when (this) {
        is LedgerEntry.Tx -> transaction.amountPaise
        is LedgerEntry.Transfer -> transfer.amountFromPaise
    }
    val magnitude = kotlin.math.abs(shown)
    if (minPaise != null && magnitude < minPaise) return false
    if (maxPaise != null && magnitude > maxPaise) return false
    return true
}

/**
 * Whether [payee] is at either end of this entry.
 *
 * Either end, not only the payee side: a refund has the merchant as its payer, and it is part of what
 * that merchant's slice on Statistics nets out. A transfer has no counterparty, so it never matches;
 * nor does anything for [PayeeRef.Unmapped], which names nobody and is searched for by text instead.
 */
fun LedgerEntry.involves(payee: PayeeRef): Boolean {
    val tx = (this as? LedgerEntry.Tx)?.transaction ?: return false
    return when (payee) {
        is PayeeRef.Merchant -> tx.payerMerchantId == payee.merchantId || tx.payeeMerchantId == payee.merchantId
        is PayeeRef.Friend -> tx.payerFriendId == payee.friendId || tx.payeeFriendId == payee.friendId
        PayeeRef.Unmapped -> false
    }
}

/** How much this entry moved [accountId] alone. The primitive [balanceDelta] sums over. */
fun LedgerEntry.balanceDeltaFor(accountId: String): Long = when (this) {
    is LedgerEntry.Tx -> BalanceDeltaCalculator.transactionDelta(
        accountId,
        TransactionDeltaInput(
            myAccountId = transaction.myAccountId,
            payerActorType = transaction.payerActorType,
            payeeActorType = transaction.payeeActorType,
            amountPaise = transaction.amountPaise
        )
    )
    is LedgerEntry.Transfer -> BalanceDeltaCalculator.transferDelta(
        accountId,
        TransferDeltaInput(
            fromAccountId = transfer.fromAccountId,
            toAccountId = transfer.toAccountId,
            amountFromPaise = transfer.amountFromPaise,
            amountToPaise = transfer.amountToPaise
        )
    )
}

/**
 * How much this entry moved the combined balance of [accountIds]. A transfer between two accounts
 * that are both in scope nets to zero, which is what makes a combined CASH + SAVINGS balance behave.
 *
 * Additive over [accountIds] by construction rather than by comment, which is what lets a balance
 * timeline decompose a combined line into per-account running totals -- necessary because a
 * reconciliation snapshot re-anchors one account without touching the others -- and reassemble it
 * by summing.
 */
fun LedgerEntry.balanceDelta(accountIds: Set<String>): Long =
    accountIds.sumOf { balanceDeltaFor(it) }

/**
 * One movement per account this entry actually moved.
 *
 * A transfer between two in-scope accounts yields **two** movements rather than the netted zero
 * [balanceDelta] would give, because a balance timeline has to be able to re-anchor one of those
 * accounts on a reconciliation snapshot without disturbing the other. Zero deltas are dropped: they
 * are the common case, since most entries touch one account out of the scope.
 */
fun List<LedgerEntry>.toBalanceMovements(accountIds: Set<String>): List<BalanceMovement> =
    flatMap { entry ->
        accountIds.mapNotNull { id ->
            val delta = entry.balanceDeltaFor(id)
            if (delta == 0L) null else BalanceMovement(entry.dateEpoch, id, delta)
        }
    }

/**
 * The balance standing after each entry, keyed by [stableId].
 *
 * @param entriesNewestFirst display order; accumulation runs oldest-first over the reverse.
 * @param openingPaise the balance immediately before the oldest entry.
 *
 * Pass the **unfiltered** list. Accumulating over a filtered one would silently drop the movements
 * of hidden entries and make every figure wrong.
 */
fun runningBalances(
    entriesNewestFirst: List<LedgerEntry>,
    accountIds: Set<String>,
    openingPaise: Long
): Map<String, Long> {
    val balances = HashMap<String, Long>(entriesNewestFirst.size)
    var running = openingPaise
    for (entry in entriesNewestFirst.asReversed()) {
        running += entry.balanceDelta(accountIds)
        balances[entry.stableId()] = running
    }
    return balances
}

/** `"CASH_WITHDRAWAL"` -> `"Cash Withdrawal"`. */
fun enumDisplayName(rawName: String): String = rawName.lowercase()
    .split("_")
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

fun AccountTransferType.displayName(): String = enumDisplayName(name)

/** Card title for a transfer: its type, since there is no counterparty to name. */
fun AccountTransfer.resolvePrimaryDisplay(): String = type.displayName()

/** Secondary line: `"Savings -> Wallet"`, plus the fee or gain when the two legs differ. */
fun AccountTransfer.resolveRouteLabel(accountLabels: Map<String, String>): String {
    val from = fromAccountId?.let { accountLabels[it] } ?: "External"
    val to = toAccountId?.let { accountLabels[it] } ?: "External"
    val route = "$from -> $to"
    val delta = BalanceDeltaCalculator.expenseDelta(
        TransferDeltaInput(fromAccountId, toAccountId, amountFromPaise, amountToPaise)
    )
    return when {
        delta > 0L -> "$route - fee ${AmountFormat.rupees(delta)}"
        delta < 0L -> "$route + gain ${AmountFormat.rupees(-delta)}"
        else -> route
    }
}

/** Unsigned: money moving between your own accounts is neither spend nor income. */
fun AccountTransfer.formatTransferAmount(): String = AmountFormat.rupees(amountFromPaise)
