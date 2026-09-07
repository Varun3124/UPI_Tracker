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
 * A form that survives a restart.
 *
 * The two policy scopes store no ids at all, which is the point of naming them: "Liquid" still
 * means every cash and savings account after a new one is opened, where a stored id set would
 * quietly go on describing the old ones.
 *
 * Account ids are UUIDs, so neither delimiter can appear inside one.
 */
fun AccountScope.serialise(): String = when (this) {
    AccountScope.Liquid -> "LIQUID"
    AccountScope.Total -> "TOTAL"
    is AccountScope.Single -> "SINGLE:$id"
    is AccountScope.Custom -> "CUSTOM:" + ids.sorted().joinToString(",")
}

/**
 * Total: anything unrecognised reads as [AccountScope.Liquid].
 *
 * A stored scope outlives the accounts it names and the release that wrote it, and neither is worth
 * a crash on a statistics screen -- resolving drops unknown ids anyway.
 */
fun parseAccountScope(stored: String?): AccountScope {
    val text = stored?.trim().orEmpty()
    val ids = text.substringAfter(':', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        text == "TOTAL" -> AccountScope.Total
        text.startsWith("SINGLE:") && ids.size == 1 -> AccountScope.Single(ids.first())
        text.startsWith("CUSTOM:") && ids.isNotEmpty() -> AccountScope.Custom(ids.toSet())
        else -> AccountScope.Liquid
    }
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
