package com.varun.upitracker.ui

import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.statistics.PayeeRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two predicates the transactions list is filtered by.
 *
 * The account rule is the one worth pinning: Liquid is the scope the screen opens on, and plenty of
 * rows carry no account at all -- an SMS or notification parse has no way to know which was used --
 * so if a policy scope ever started excluding them, the default view would silently lose a good part
 * of the list.
 */
class LedgerEntryFilterTest {

    private val savings = "acc-savings"
    private val cash = "acc-cash"
    private val broker = "acc-broker"
    private val liquid = setOf(savings, cash)

    private fun spend(accountId: String?, paise: Long = 50_000) = LedgerEntry.Tx(
        Transaction(
            id = 1,
            amountPaise = paise,
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.MERCHANT,
            myAccountId = accountId,
            dateEpoch = 1_000,
            source = "MANUAL"
        )
    )

    private fun move(from: String?, to: String?, paise: Long = 20_000) = LedgerEntry.Transfer(
        AccountTransfer(
            id = "x1",
            fromAccountId = from,
            toAccountId = to,
            amountFromPaise = paise,
            amountToPaise = paise,
            type = AccountTransferType.GENERIC_TRANSFER,
            dateEpoch = 1_000,
            source = EntrySource.MANUAL
        )
    )

    // --- scope ------------------------------------------------------------

    @Test
    fun aPolicyScopeKeepsRowsWithNoAccountRecorded() {
        assertTrue(spend(null).isInScope(AccountScope.Liquid, liquid))
        assertTrue(spend(null).isInScope(AccountScope.Total, liquid + broker))
    }

    /** Naming an account is a different act: an unattributed row is not on the account you asked for. */
    @Test
    fun aNamedScopeExcludesRowsWithNoAccountRecorded() {
        assertFalse(spend(null).isInScope(AccountScope.Single(savings), setOf(savings)))
        assertFalse(spend(null).isInScope(AccountScope.Custom(liquid), liquid))
    }

    @Test
    fun aRowOutsideTheScopeIsExcludedWhicheverKindItIs() {
        assertFalse(spend(broker).isInScope(AccountScope.Liquid, liquid))
        assertFalse(spend(broker).isInScope(AccountScope.Single(savings), setOf(savings)))
    }

    @Test
    fun aRowInsideTheScopeIsKept() {
        assertTrue(spend(savings).isInScope(AccountScope.Liquid, liquid))
        assertTrue(spend(savings).isInScope(AccountScope.Single(savings), setOf(savings)))
    }

    /** Either endpoint counts as involvement; an external leg is null and matches nothing. */
    @Test
    fun eitherEndOfATransferPutsItInScope() {
        assertTrue(move(savings, broker).isInScope(AccountScope.Liquid, liquid))
        assertTrue(move(broker, cash).isInScope(AccountScope.Liquid, liquid))
        assertFalse(move(broker, null).isInScope(AccountScope.Liquid, liquid))
    }

    // --- amount -----------------------------------------------------------

    @Test
    fun noBoundsMatchEverything() {
        assertTrue(spend(savings).matchesAmount(null, null))
        assertTrue(move(savings, cash).matchesAmount(null, null))
    }

    @Test
    fun boundsAreInclusiveAtBothEnds() {
        val entry = spend(savings, paise = 50_000)
        assertTrue(entry.matchesAmount(50_000, 50_000))
        assertFalse(entry.matchesAmount(50_001, null))
        assertFalse(entry.matchesAmount(null, 49_999))
    }

    @Test
    fun eitherBoundStandsAlone() {
        val entry = spend(savings, paise = 50_000)
        assertTrue(entry.matchesAmount(10_000, null))
        assertTrue(entry.matchesAmount(null, 100_000))
        assertFalse(entry.matchesAmount(60_000, null))
    }

    /** A transfer is matched on the leg the row actually shows. */
    @Test
    fun aTransferIsMatchedOnTheAmountItDisplays() {
        assertTrue(move(savings, cash, paise = 20_000).matchesAmount(20_000, 20_000))
    }

    // --- payee ------------------------------------------------------------

    private fun between(
        payerMerchant: Long? = null,
        payeeMerchant: Long? = null,
        payerFriend: Long? = null,
        payeeFriend: Long? = null
    ) = LedgerEntry.Tx(
        Transaction(
            id = 2,
            amountPaise = 10_000,
            payerMerchantId = payerMerchant,
            payeeMerchantId = payeeMerchant,
            payerFriendId = payerFriend,
            payeeFriendId = payeeFriend,
            dateEpoch = 1_000,
            source = "MANUAL"
        )
    )

    /** A refund has the merchant paying, and it nets against the same slice the purchase is in. */
    @Test
    fun aMerchantMatchesAtEitherEnd() {
        val swiggy = PayeeRef.Merchant(5)
        assertTrue(between(payeeMerchant = 5).involves(swiggy))
        assertTrue(between(payerMerchant = 5).involves(swiggy))
        assertFalse(between(payeeMerchant = 6).involves(swiggy))
    }

    /** Ids are only unique within their own table, so friend 5 is not merchant 5. */
    @Test
    fun aFriendIsNotTakenForAMerchantWithTheSameId() {
        assertTrue(between(payeeFriend = 5).involves(PayeeRef.Friend(5)))
        assertFalse(between(payeeFriend = 5).involves(PayeeRef.Merchant(5)))
        assertFalse(between(payeeMerchant = 5).involves(PayeeRef.Friend(5)))
    }

    @Test
    fun aTransferHasNoPayeeToMatch() {
        assertFalse(move(savings, cash).involves(PayeeRef.Merchant(5)))
    }
}
