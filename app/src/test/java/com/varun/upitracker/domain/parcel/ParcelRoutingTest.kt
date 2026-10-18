package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A debt routed through its writer reads, on the reader's phone, as running through the sender. */
class ParcelRoutingTest {

    private val asha = 10L
    private val bob = 11L
    private val writerOnBobsPhone = 99L
    private val ashaOnBobsPhone = 20L

    /** The writer's row: Asha paid a shop 1000 for the writer and Bob, Bob's half routed through the writer. */
    private val writersRow = Transaction(
        id = 1L,
        amountPaise = 1000L,
        payerActorType = ActorType.FRIEND,
        payerFriendId = asha,
        payeeActorType = ActorType.MERCHANT,
        payeeMerchantId = 50L,
        dateEpoch = 1_000L,
        source = "MANUAL",
        iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
    )
    private val writersShares = listOf(
        TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.ME, amountPaise = 500L),
        IouLegs.withVia(
            TransactionShare(transactionId = 1L, side = "PAYER", participantType = ActorType.FRIEND, friendId = bob, amountPaise = 500L),
            owesPayer = true,
            via = IouParty.Me
        )
    )

    private fun flippedForBob(): ParcelTransaction = ParcelPerspective.flipForRecipient(
        writersRow,
        writersShares,
        recipientFriendId = bob,
        friendName = { mapOf(asha to "Asha", bob to "Bob")[it] },
        merchantName = { "Cafe" }
    )

    @Test
    fun flipping_aDebtRoutedThroughTheWriterRunsThroughTheSender() {
        val bobsShare = flippedForBob().shares.single { it.participant == ParcelActor.Me }
        assertEquals(ParcelActor.Sender, bobsShare.payerLegVia)
        assertEquals(null, bobsShare.payeeLegVia)
    }

    @Test
    fun landing_bobOwesTheWriterNotAsha() {
        val landed = ParcelPerspective.toLocal(
            flippedForBob(),
            senderFriendId = writerOnBobsPhone,
            originToken = "tok",
            mapPerson = { if (it == "Asha") ashaOnBobsPhone else null },
            resolveShop = { null },
            carryUpiRefId = false
        )
        val tx = landed.transaction
        val legs = IouLegs.legs(
            tx.payerActorRef(), tx.payeeActorRef(), landed.shares, tx.amountPaise, tx.ledgerEffect,
            IouLegs.resolve(tx, landed.shares)
        )
        assertEquals(mapOf(writerOnBobsPhone to -500L), IouLegs.netByFriend(legs))
    }

    @Test
    fun format_writesTheRoutedVersionOnlyWhenARowNeedsIt() {
        val routed = Parcel(ParcelFormat.VERSION, "tok", listOf(flippedForBob()), senderAlias = "Asha")
        val plain = routed.copy(transactions = routed.transactions.map { tx -> tx.copy(shares = tx.shares.map { it.copy(payerLegVia = null) }) })

        assertTrue(ParcelFormat.format(routed).startsWith("V|${ParcelFormat.ROUTED_VERSION}|"))
        assertTrue(ParcelFormat.format(plain).startsWith("V|${ParcelFormat.VERSION}|"))
    }

    @Test
    fun format_aRoutedParcelReadsBackAsItWasWritten() {
        val parcel = Parcel(ParcelFormat.VERSION, "tok", listOf(flippedForBob()), senderAlias = "Asha")
        val read = ParcelFormat.parse(ParcelFormat.format(parcel)) as ParcelDecodeResult.Ok
        assertEquals(parcel, read.parcel)
    }

    @Test
    fun format_aRoutedMailboxParcelReadsBackToo() {
        val row = flippedForBob().copy(sourceId = 0L, shareRef = "ref-1")
        val parcel = Parcel(ParcelFormat.MAILBOX_VERSION, null, listOf(row))
        val text = ParcelFormat.format(parcel)

        assertTrue(text.startsWith("V|${ParcelFormat.MAILBOX_ROUTED_VERSION}|"))
        val read = ParcelFormat.parse(text, ParcelFormat.MAILBOX_VERSION) as ParcelDecodeResult.Ok
        assertEquals(parcel, read.parcel)
    }

    @Test
    fun format_anAppThatPredatesRoutingIsToldToUpdate() {
        val parcel = Parcel(ParcelFormat.VERSION, "tok", listOf(flippedForBob()), senderAlias = "Asha")
        val result = ParcelFormat.parse(ParcelFormat.format(parcel), expectedVersion = ParcelFormat.PRE_ALIAS_VERSION)
        assertTrue((result as ParcelDecodeResult.Failed).reason.contains("newer version"))
    }

    @Test
    fun sanity_theWritersOwnRowPostsWhatTheyMeant() {
        val legs = IouLegs.legs(
            writersRow.payerActorRef(), writersRow.payeeActorRef(), writersShares, 1000L, LedgerEffect.DEBT,
            IouRecovery.FROM_SECONDARY_PAYERS
        )
        assertEquals(mapOf(asha to -1000L, bob to 500L), IouLegs.netByFriend(legs))
    }
}
