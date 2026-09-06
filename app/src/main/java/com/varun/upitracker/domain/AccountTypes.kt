package com.varun.upitracker.domain

import com.varun.upitracker.database.entity.AccountType

/**
 * What counts as spendable money.
 *
 * A policy rather than a schema fact, which is why it lives in `domain` and not beside the entity.
 * FD, invested and uninvested holdings are wealth, not a balance you can pay from — the reasoning
 * `AllTransactionsViewModel.balanceScopeIds` already gives for excluding them from its balance row.
 *
 * Type only, deliberately. The call sites this replaces **disagree about archived accounts**: some
 * go through `getActiveByTypes`, one filters archived by hand, another is handed an already-active
 * list. Folding that decision in here would silently change behaviour on screens that are not part
 * of this feature, so archived-ness stays with the caller and only the type test is shared.
 */
object AccountTypes {

    val LIQUID: List<AccountType> = listOf(AccountType.CASH, AccountType.SAVINGS)

    fun isLiquid(type: AccountType): Boolean = type in LIQUID
}
