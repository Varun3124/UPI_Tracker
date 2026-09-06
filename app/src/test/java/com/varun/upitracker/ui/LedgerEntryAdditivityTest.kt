package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `balanceDelta` over a set must equal the sum of `balanceDeltaFor` over its members.
 *
 * Pinned explicitly rather than left to inspection because a balance timeline decomposes a combined
 * line into per-account running totals — necessary, since a reconciliation snapshot re-anchors one
 * account without touching the others — and reassembles it by summing. If this property ever broke,
 * the timeline would silently disagree with `AccountRepository.getBalance` instead of failing.
 */
class LedgerEntryAdditivityTest {

    private val savings = "acc-savings"
    private val cash = "acc-cash"
    private val outside = "acc-broker"

    private fun spend(accountId: String?) = LedgerEntry.Tx(
        Transaction(
            id = 1,
            amountPaise = 50_000,
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.MERCHANT,
            myAccountId = accountId,
            dateEpoch = 1_000,
            source = "MANUAL"
        )
    )

    private fun move(from: String?, to: String?, fromPaise: Long = 20_000, toPaise: Long = 20_000) =
        LedgerEntry.Transfer(
            AccountTransfer(
                id = "x1",
                fromAccountId = from,
                toAccountId = to,
                amountFromPaise = fromPaise,
                amountToPaise = toPaise,
                type = AccountTransferType.GENERIC_TRANSFER,
                dateEpoch = 1_000,
                source = EntrySource.MANUAL
            )
        )

    private fun assertAdditive(entry: LedgerEntry, scope: Set<String>) {
        assertEquals(
            entry.balanceDelta(scope),
            scope.sumOf { entry.balanceDeltaFor(it) }
        )
    }

    @Test
    fun additiveForEveryEntryShape() {
        val scope = setOf(savings, cash)
        listOf(
            spend(savings),
            spend(cash),
            spend(outside),
            spend(null),
            move(savings, cash),
            move(savings, outside),
            move(outside, cash),
            move(savings, cash, fromPaise = 20_000, toPaise = 19_900), // a fee
            move(null, cash),
            move(savings, null)
        ).forEach { assertAdditive(it, scope) }
    }

    @Test
    fun anInternalTransferNetsToZeroBothWays() {
        val scope = setOf(savings, cash)
        val entry = move(savings, cash)
        assertEquals(0L, entry.balanceDelta(scope))
        assertEquals(0L, scope.sumOf { entry.balanceDeltaFor(it) })
    }

    /** A fee is the difference between the legs, and must survive the decomposition. */
    @Test
    fun aTransferFeeShowsAsALossOnBothPaths() {
        val scope = setOf(savings, cash)
        val entry = move(savings, cash, fromPaise = 20_000, toPaise = 19_900)
        assertEquals(-100L, entry.balanceDelta(scope))
        assertEquals(-100L, scope.sumOf { entry.balanceDeltaFor(it) })
    }

    @Test
    fun leavingScopeIsNotNetted() {
        val entry = move(savings, outside)
        assertEquals(-20_000L, entry.balanceDelta(setOf(savings, cash)))
    }

    @Test
    fun anEmptyScopeMovesNothing() {
        assertEquals(0L, spend(savings).balanceDelta(emptySet()))
    }

    @Test
    fun perAccountAgreesWithASingletonSet() {
        listOf(savings, cash, outside).forEach { id ->
            assertEquals(spend(savings).balanceDelta(setOf(id)), spend(savings).balanceDeltaFor(id))
        }
    }
}
