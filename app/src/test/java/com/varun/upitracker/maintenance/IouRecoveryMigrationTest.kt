package com.varun.upitracker.maintenance

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What upgrading changes, pinned: every row keeps the balance it already posted, except where the old
 * inference left out a leg for ME as a secondary, or counted ME's share twice.
 */
class IouRecoveryMigrationTest {

    private val payers = IouRecovery.FROM_SECONDARY_PAYERS
    private val payees = IouRecovery.FROM_SECONDARY_PAYEES

    private val x = 1L
    private val y = 2L
    private val other = 3L

    private fun tx(
        payerActorType: String,
        payerFriendId: Long? = null,
        payeeActorType: String,
        payeeFriendId: Long? = null
    ) = Transaction(
        id = 41L,
        amountPaise = 30000L,
        payerActorType = payerActorType,
        payerFriendId = payerFriendId,
        payerMerchantId = if (payerActorType == ActorType.MERCHANT) 50L else null,
        payeeActorType = payeeActorType,
        payeeFriendId = payeeFriendId,
        payeeMerchantId = if (payeeActorType == ActorType.MERCHANT) 50L else null,
        dateEpoch = 0L,
        source = "MANUAL",
        iouRecovery = null
    )

    private fun friendShare(side: String, id: Long, amountPaise: Long) = TransactionShare(
        transactionId = 41L, side = side, participantType = ActorType.FRIEND, friendId = id, amountPaise = amountPaise
    )

    private fun meShare(side: String, amountPaise: Long) = TransactionShare(
        transactionId = 41L, side = side, participantType = ActorType.ME, amountPaise = amountPaise
    )

    private fun upgrade(tx: Transaction, vararg shares: TransactionShare) = IouRecoveryUpgrade.of(tx, shares.toList())

    @Test
    fun `ME paying keeps recovering from co-payers, with nothing to correct`() {
        val bill = upgrade(
            tx(ActorType.ME, payeeActorType = ActorType.MERCHANT),
            meShare("PAYER", 20000L), friendShare("PAYER", other, 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payers, emptyMap()), bill)

        // Even with the payee side split, which the old inference ignored when ME paid.
        val loan = upgrade(
            tx(ActorType.ME, payeeActorType = ActorType.FRIEND, payeeFriendId = y),
            meShare("PAYER", 30000L), friendShare("PAYEE", y, 20000L), friendShare("PAYEE", other, 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payers, emptyMap()), loan)
    }

    @Test
    fun `ME paid keeps recovering from co-payees, with nothing to correct`() {
        val refund = upgrade(
            tx(ActorType.MERCHANT, payeeActorType = ActorType.ME),
            meShare("PAYEE", 20000L), friendShare("PAYEE", other, 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payees, emptyMap()), refund)

        // Even with the payer side split, which the old inference ignored when ME was paid.
        val split = upgrade(
            tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.ME),
            friendShare("PAYER", x, 20000L), friendShare("PAYER", other, 10000L), meShare("PAYEE", 30000L)
        )
        assertEquals(IouRecoveryUpgrade(payees, emptyMap()), split)
    }

    @Test
    fun `ME as a co-payer gains the leg the payee owes them`() {
        val upgraded = upgrade(
            tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.FRIEND, payeeFriendId = y),
            friendShare("PAYER", x, 20000L), meShare("PAYER", 10000L), friendShare("PAYEE", y, 30000L)
        )
        assertEquals(IouRecoveryUpgrade(payers, mapOf(y to 10000L)), upgraded)
    }

    @Test
    fun `ME as a co-payee gains the leg they owe the payer`() {
        val upgraded = upgrade(
            tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.FRIEND, payeeFriendId = y),
            friendShare("PAYER", x, 30000L), friendShare("PAYEE", y, 20000L), meShare("PAYEE", 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payees, mapOf(x to -10000L)), upgraded)
    }

    @Test
    fun `ME on both sides loses the share that was counted twice`() {
        val upgraded = upgrade(
            tx(ActorType.ME, payeeActorType = ActorType.FRIEND, payeeFriendId = y),
            meShare("PAYER", 30000L), friendShare("PAYEE", y, 20000L), meShare("PAYEE", 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payers, mapOf(y to -10000L)), upgraded)
    }

    @Test
    fun `a co-payer on a shop bill already had every leg`() {
        val upgraded = upgrade(
            tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.MERCHANT),
            friendShare("PAYER", x, 20000L), meShare("PAYER", 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payers, emptyMap()), upgraded)
    }

    @Test
    fun `rows that posted nothing sided are given a side and left alone`() {
        val shares = arrayOf(friendShare("PAYER", x, 20000L), meShare("PAYER", 10000L), friendShare("PAYEE", y, 30000L))
        val betweenFriends = tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.FRIEND, payeeFriendId = y)

        assertEquals(IouRecoveryUpgrade(payers, emptyMap()), upgrade(betweenFriends.copy(isPending = true), *shares))
        assertEquals(
            IouRecoveryUpgrade(payers, emptyMap()),
            upgrade(betweenFriends.copy(ledgerEffect = LedgerEffect.NONE), *shares)
        )
        // Settling up: money straight to a friend with no split at all.
        assertEquals(
            IouRecoveryUpgrade(payers, emptyMap()),
            upgrade(tx(ActorType.ME, payeeActorType = ActorType.FRIEND, payeeFriendId = y))
        )
    }

    @Test
    fun `a row ME is not part of gets the side its split suggests, and nothing to correct`() {
        val upgraded = upgrade(
            tx(ActorType.FRIEND, payerFriendId = x, payeeActorType = ActorType.FRIEND, payeeFriendId = y),
            friendShare("PAYER", x, 30000L), friendShare("PAYEE", y, 20000L), friendShare("PAYEE", other, 10000L)
        )
        assertEquals(IouRecoveryUpgrade(payees, emptyMap()), upgraded)
    }
}
