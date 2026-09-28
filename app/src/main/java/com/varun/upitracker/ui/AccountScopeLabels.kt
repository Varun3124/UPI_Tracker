package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.domain.statistics.AccountScope

/**
 * What an [AccountScope] is called on screen.
 *
 * Shared by the statistics screen and the transactions list, which have to agree: the balance figure
 * on one and the balance row on the other are the same money under the same scope, and two copies of
 * this would eventually call it two different things.
 */

/** An archived account is still nameable, so it is labelled rather than hidden. */
fun accountLabel(account: Account): String =
    if (account.isArchived) "${account.label} (archived)" else account.label

/** Named scopes keep their name; a hand-picked set is counted rather than listed. */
fun scopeLabel(scope: AccountScope, accounts: List<Account>): String = when (scope) {
    AccountScope.Liquid -> "Liquid"
    AccountScope.Total -> "All accounts"
    is AccountScope.Single ->
        accounts.firstOrNull { it.id == scope.id }?.label ?: "1 account"
    is AccountScope.Custom -> {
        val known = accounts.count { it.id in scope.ids }
        if (known == 1) accounts.first { it.id in scope.ids }.label else "$known accounts"
    }
}
