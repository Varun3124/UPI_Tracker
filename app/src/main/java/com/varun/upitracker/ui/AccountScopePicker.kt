package com.varun.upitracker.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.domain.statistics.AccountScope

/**
 * Liquid / All accounts / each account / Choose accounts, as a dialog.
 *
 * An AlertDialog list with the multi-select chained off the last row, matching the period menu it
 * sits beside on the statistics screen. Shaped like [com.varun.upitracker.ui.share.RecipientPicker]:
 * the screen hands over the accounts and the current choice and gets a new scope back, so the two
 * screens that offer this cannot drift apart in what they offer or in what they call it.
 *
 * @param title what the scope is being chosen *for*, which differs per screen -- a balance line on
 *   one, the list and its balance row on the other.
 */
class AccountScopePicker(
    private val activity: AppCompatActivity,
    private val title: String
) {

    fun show(accounts: List<Account>, current: AccountScope, onPicked: (AccountScope) -> Unit) {
        val labels = listOf("Liquid (cash and savings)", "All accounts") +
            accounts.map { accountLabel(it) } +
            listOf("Choose accounts…")

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onPicked(AccountScope.Liquid)
                    1 -> onPicked(AccountScope.Total)
                    labels.lastIndex -> showCustomPicker(accounts, current, onPicked)
                    else -> onPicked(AccountScope.Single(accounts[which - 2].id))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomPicker(
        accounts: List<Account>,
        current: AccountScope,
        onPicked: (AccountScope) -> Unit
    ) {
        if (accounts.isEmpty()) return
        val alreadyIn = when (current) {
            is AccountScope.Custom -> current.ids
            is AccountScope.Single -> setOf(current.id)
            else -> emptySet()
        }
        val checked = accounts.map { it.id in alreadyIn }.toBooleanArray()

        AlertDialog.Builder(activity)
            .setTitle("Choose accounts")
            .setMultiChoiceItems(
                accounts.map { accountLabel(it) }.toTypedArray(),
                checked
            ) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                val picked = accounts.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                // An empty pick would scope to nothing at all; treat it as "never mind".
                if (picked.isNotEmpty()) onPicked(AccountScope.Custom(picked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
