package com.varun.upitracker.domain

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.EntrySource
import kotlin.math.abs

/**
 * Finds the transfer a bank message already became, when the transfer cannot say so itself.
 *
 * A pending SMS or statement transaction the user reclassifies as a transfer is deleted, and until
 * [AccountTransfer.upiRefId] existed its reference went with it. The SMS backlog is rescanned on
 * every dashboard open, so each of those payments came back as a fresh transaction, over and over.
 * Those transfers can only be recognised by shape: the same amount leaving (or reaching) one of the
 * right accounts, close to the same time.
 *
 * Deliberately narrow, because a wrong match silently drops a real payment:
 *  - only transfers **converted from an import** (SMS, notification, statement). A transfer typed in
 *    by hand never came from a bank message, and a round-amount ATM withdrawal logged that way would
 *    otherwise swallow an unrelated UPI payment of the same amount the next day.
 *  - only transfers **not yet claimed**. The caller stamps the reference on the one it picks, so each
 *    transfer answers for one message and a second payment of the same amount still comes in.
 *  - the nearest in time wins when several fit.
 *
 * Pure, so the rule is under test; the callers own the database reads and the stamping.
 */
object ImportedTransferMatcher {

    private val IMPORTED_SOURCES = setOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.BANK_STATEMENT)

    /**
     * @param isDebit true when money left [accountIds]: the transfer's source leg must match. False
     *   matches its destination leg instead.
     * @param fromEpoch inclusive start of the tolerance window.
     * @param toEpoch inclusive end of the tolerance window.
     * @param atEpoch when the message says the payment happened; nearest wins.
     * @param allowStatementRef whether a transfer already holding a statement reference may still be
     *   claimed. True for an SMS (a statement row converted to a transfer is still missing its UPI
     *   reference); false for another statement row, which would be a different row of the statement.
     * @param claimedIds transfers already taken by earlier rows of the same import.
     */
    fun pick(
        transfers: List<AccountTransfer>,
        amountPaise: Long,
        isDebit: Boolean,
        accountIds: Collection<String>,
        fromEpoch: Long,
        toEpoch: Long,
        atEpoch: Long,
        allowStatementRef: Boolean,
        claimedIds: Set<String> = emptySet()
    ): AccountTransfer? = transfers
        .asSequence()
        .filter { it.id !in claimedIds }
        .filter { it.source in IMPORTED_SOURCES }
        .filter { it.upiRefId == null }
        .filter { allowStatementRef || it.statementRefNo == null }
        .filter { it.dateEpoch in fromEpoch..toEpoch }
        .filter { transfer ->
            val (accountId, amount) = if (isDebit) {
                transfer.fromAccountId to transfer.amountFromPaise
            } else {
                transfer.toAccountId to transfer.amountToPaise
            }
            accountId != null && accountId in accountIds && amount == amountPaise
        }
        .minWithOrNull(compareBy<AccountTransfer> { abs(it.dateEpoch - atEpoch) }.thenBy { it.id })
}
