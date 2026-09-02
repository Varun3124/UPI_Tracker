package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.BalanceDeltaCalculator
import com.varun.upitracker.domain.TransactionDeltaInput
import com.varun.upitracker.domain.TransferDeltaInput

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
 * How much this entry moved the combined balance of [accountIds]. A transfer between two accounts
 * that are both in scope nets to zero, which is what makes a combined CASH + SAVINGS balance behave.
 */
fun LedgerEntry.balanceDelta(accountIds: Set<String>): Long = when (this) {
    is LedgerEntry.Tx -> {
        val input = TransactionDeltaInput(
            myAccountId = transaction.myAccountId,
            payerActorType = transaction.payerActorType,
            payeeActorType = transaction.payeeActorType,
            amountPaise = transaction.amountPaise
        )
        accountIds.sumOf { BalanceDeltaCalculator.transactionDelta(it, input) }
    }
    is LedgerEntry.Transfer -> {
        val input = TransferDeltaInput(
            fromAccountId = transfer.fromAccountId,
            toAccountId = transfer.toAccountId,
            amountFromPaise = transfer.amountFromPaise,
            amountToPaise = transfer.amountToPaise
        )
        accountIds.sumOf { BalanceDeltaCalculator.transferDelta(it, input) }
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
        delta > 0L -> "$route - fee Rs${"%.0f".format(delta / 100.0)}"
        delta < 0L -> "$route + gain Rs${"%.0f".format(-delta / 100.0)}"
        else -> route
    }
}

/** Unsigned: money moving between your own accounts is neither spend nor income. */
fun AccountTransfer.formatTransferAmount(): String = "Rs${"%.0f".format(amountFromPaise / 100.0)}"
