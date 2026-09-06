package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.domain.AccountTypes

/**
 * Which accounts a trend line is drawn for.
 *
 * A named choice rather than a bare id set, so the screen can say what it is showing and reload the
 * same meaning after a restart -- "Liquid" survives adding a new savings account, a stored id set
 * would not.
 */
sealed interface AccountScope {

    /** Spendable money: cash and savings, the balance the ledger row on the transactions list shows. */
    data object Liquid : AccountScope

    /** Net worth: everything, deposits and holdings included. */
    data object Total : AccountScope

    data class Single(val id: String) : AccountScope

    data class Custom(val ids: Set<String>) : AccountScope
}

/**
 * The account ids [this] scope covers, given the full account list.
 *
 * **Archived accounts are excluded from the two policy scopes and kept in the two explicit ones.**
 * A policy scope is a standing description of the accounts in use, and archiving is how an account
 * leaves that set -- Liquid has to match the transactions screen's balance row, which is built from
 * active accounts, or the line would end somewhere the rest of the app disagrees with. Total follows
 * the same rule so the two cannot mean different things by "account"; the deleted net-worth use case
 * counted archived accounts and was the only place in the app that did. Naming an account outright
 * is a different act, and is honoured whatever its state.
 *
 * Unknown ids are dropped rather than rejected: a stored custom scope outlives the account it names.
 */
fun AccountScope.resolve(accounts: List<Account>): Set<String> = when (this) {
    AccountScope.Liquid ->
        accounts.filter { !it.isArchived && AccountTypes.isLiquid(it.type) }.map { it.id }.toSet()
    AccountScope.Total ->
        accounts.filter { !it.isArchived }.map { it.id }.toSet()
    is AccountScope.Single ->
        accounts.filter { it.id == id }.map { it.id }.toSet()
    is AccountScope.Custom ->
        accounts.filter { it.id in ids }.map { it.id }.toSet()
}
