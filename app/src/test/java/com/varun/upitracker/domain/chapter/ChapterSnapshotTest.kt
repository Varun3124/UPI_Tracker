package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterShareMode
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/declarations-design.md S3: a shared chapter written from one member's seat, and the body it
 * travels in. The worked example is section 4.4 -- Alice's Goa trip, shared with Bob and Dan.
 */
class ChapterSnapshotTest {

    // Alice's book: Bob is friend 20, Dan is friend 30.
    private val bob = 20L
    private val dan = 30L
    private val names = mapOf(bob to "Bob", dan to "Dan")
    private val shareId = MailboxIds.newRandomId()

    private fun tx(id: Long, date: Long, amount: Long, payerType: String, payerFriend: Long? = null, payeeType: String, payeeFriend: Long? = null, shares: List<TransactionShare>, pending: Boolean = false, sharedRefId: String? = null) =
        TaggedTx(
            Transaction(
                id = id, amountPaise = amount,
                payerActorType = payerType, payerFriendId = payerFriend, payerRawLabel = payerFriend?.let(names::get),
                payeeActorType = payeeType, payeeFriendId = payeeFriend, payeeRawLabel = payeeFriend?.let(names::get) ?: "Shop",
                dateEpoch = date, source = "MANUAL", isPending = pending, sharedRefId = sharedRefId,
                ledgerEffect = LedgerEffect.DEBT, iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS, chapterId = 1L
            ),
            shares.map { it.copy(transactionId = id) }
        )

    private fun share(side: String, type: String, friend: Long? = null, amount: Long) =
        TransactionShare(transactionId = 0L, side = side, participantType = type, friendId = friend, amountPaise = amount, rawLabel = friend?.let(names::get))

    /** Hotel: Alice paid 9,000 for three. Dinner: Bob paid 3,000 for three. Cab: Dan paid 600 for Bob and himself. */
    private val goaRows = listOf(
        tx(1L, 100L, 900_000L, ActorType.ME, payeeType = ActorType.MERCHANT, shares = listOf(
            share("PAYER", ActorType.ME, amount = 300_000L),
            share("PAYER", ActorType.FRIEND, bob, 300_000L),
            share("PAYER", ActorType.FRIEND, dan, 300_000L)
        )),
        tx(2L, 200L, 300_000L, ActorType.FRIEND, bob, ActorType.MERCHANT, shares = listOf(
            share("PAYER", ActorType.FRIEND, bob, 100_000L),
            share("PAYER", ActorType.ME, amount = 100_000L),
            share("PAYER", ActorType.FRIEND, dan, 100_000L)
        ), sharedRefId = "mbx:uidBob:bobsDinnerRef"),
        tx(3L, 300L, 60_000L, ActorType.FRIEND, dan, ActorType.MERCHANT, shares = listOf(
            share("PAYER", ActorType.FRIEND, dan, 30_000L),
            share("PAYER", ActorType.FRIEND, bob, 30_000L)
        )),
        // Alice alone at a shop: nobody else's business, so it never leaves her phone.
        tx(4L, 400L, 5_000L, ActorType.ME, payeeType = ActorType.MERCHANT, shares = emptyList())
    )

    private val chapter = Chapter(
        id = 1L, name = "Goa trip", createdEpoch = 1L, shareId = shareId, shareVersion = 7L,
        shareMode = ChapterShareMode.SHARED
    )

    private fun source(receiving: Map<Long, String> = mapOf(bob to "uidBob", dan to "uidDan")) = SnapshotSource(
        chapter = chapter,
        result = ChapterMath.compute(goaRows),
        memberIds = setOf(bob, dan),
        rows = goaRows,
        shareRefOf = { "ref$it" },
        friendName = names::get,
        merchantName = { null },
        receivingUids = receiving,
        sentEpoch = 999L
    )

    @Test
    fun `the plan is written from the member's seat`() {
        val forBob = SnapshotBuilder.build(source(), bob, hint = null, pasteTokenForRecipient = null)
        // Nets: Alice +5,000, Bob -1,300, Dan -3,700. Alice's plan: Dan pays her 3,700 and Bob 1,300.
        // From Bob's seat, Alice is the Sender and Bob is Me.
        assertEquals(
            listOf(
                SnapshotPayment(ParcelActor.Linked("uidDan", "Dan"), ParcelActor.Sender, 370_000L),
                SnapshotPayment(ParcelActor.Me, ParcelActor.Sender, 130_000L)
            ),
            forBob.plan
        )
        assertEquals(0L, forBob.nets.sumOf { it.amountPaise })
        assertEquals(listOf(ParcelActor.Sender, ParcelActor.Me, ParcelActor.Linked("uidDan", "Dan")), forBob.members)
    }

    @Test
    fun `someone the chapter does not go to is named, not identified`() {
        val forBob = SnapshotBuilder.build(source(receiving = mapOf(bob to "uidBob")), bob, hint = null, pasteTokenForRecipient = null)
        assertTrue(ParcelActor.Person("Dan") in forBob.members)
        assertTrue(forBob.plan.none { it.debtor is ParcelActor.Linked || it.creditor is ParcelActor.Linked })
    }

    @Test
    fun `the member's own rows come first, and solo spending never leaves`() {
        val forDan = SnapshotBuilder.build(source(), dan, hint = null, pasteTokenForRecipient = null)
        assertEquals(3, forDan.rows.size)
        // Every row names Dan here, so newest first among them.
        assertEquals(listOf("ref3", "ref2", "ref1"), forDan.rows.map { it.shareRef })
        assertEquals(0, forDan.omittedRows)
        assertEquals("mbx:uidBob:bobsDinnerRef", forDan.extras[1].sourceRef)
    }

    @Test
    fun `a paste token puts the pasted reference on every row`() {
        val forBob = SnapshotBuilder.build(source(), bob, hint = null, pasteTokenForRecipient = "tok")
        assertTrue(forBob.rows.all { it.legacyRef == "tok.${it.shareRef!!.removePrefix("ref")}" })
    }

    @Test
    fun `a snapshot survives the round trip`() {
        val hint = SnapshotHint(MailboxIds.newRandomId(), -12_345L)
        val forBob = SnapshotBuilder.build(source(), bob, hint, pasteTokenForRecipient = "tok")
        val decoded = ChapterSnapshotFormat.decode(ChapterSnapshotFormat.encode(forBob), senderUid = "uidAlice", readerUid = "uidBob")
        assertEquals(forBob, decoded)
    }

    @Test
    fun `a snapshot naming its sender or reader by account is refused`() {
        val forBob = SnapshotBuilder.build(source(), bob, hint = null, pasteTokenForRecipient = null)
        val body = ChapterSnapshotFormat.encode(forBob)
        assertNotNull(ChapterSnapshotFormat.decode(body, senderUid = "uidAlice", readerUid = "uidBob"))
        // Dan is named by account; were Dan the reader, their share would count twice.
        assertNull(ChapterSnapshotFormat.decode(body, senderUid = "uidAlice", readerUid = "uidDan"))
        assertNull(ChapterSnapshotFormat.decode(body, senderUid = "uidDan", readerUid = "uidBob"))
    }

    @Test
    fun `anything malformed is refused whole`() {
        val good = SnapshotBuilder.build(source(), bob, hint = null, pasteTokenForRecipient = null)
        val refused = listOf(
            good.copy(shareId = "short"),
            good.copy(name = " "),
            good.copy(members = good.members - ParcelActor.Me),
            good.copy(members = good.members - ParcelActor.Linked("uidDan", "Dan")),
            good.copy(plan = good.plan + SnapshotPayment(ParcelActor.Me, ParcelActor.Sender, 0L)),
            good.copy(plan = good.plan + SnapshotPayment(ParcelActor.Shop("Cafe"), ParcelActor.Sender, 5L)),
            good.copy(nets = good.nets.drop(1)),
            good.copy(omittedRows = -1),
            good.copy(hint = SnapshotHint("nope", 1L))
        )
        refused.forEachIndexed { index, snapshot ->
            assertNull("case $index", ChapterSnapshotFormat.decode(ChapterSnapshotFormat.encode(snapshot)))
        }
        val body = ChapterSnapshotFormat.encode(good)
        assertNull(ChapterSnapshotFormat.decode(body.dropLast(3)))
        assertNull(ChapterSnapshotFormat.decode(ChapterSnapshotFormat.encodeEnded(shareId, ChapterEnd.DELETED)))
    }

    @Test
    fun `an ending survives the round trip`() {
        ChapterEnd.entries.forEach { end ->
            assertEquals(shareId to end, ChapterSnapshotFormat.decodeEnded(ChapterSnapshotFormat.encodeEnded(shareId, end)))
        }
        assertNull(ChapterSnapshotFormat.decodeEnded("garbage"))
    }

    @Test
    fun `a chapter too big for one message leaves its oldest rows out, the member's own last`() {
        val many = (1L..(SnapshotBuilder.MAX_ROWS + 20).toLong()).map { id ->
            tx(id, id, 1_000L, ActorType.ME, payeeType = ActorType.MERCHANT, shares = listOf(
                share("PAYER", ActorType.ME, amount = 500L),
                share("PAYER", ActorType.FRIEND, if (id % 2 == 0L) bob else dan, 500L)
            ))
        }
        val big = SnapshotSource(chapter, ChapterMath.compute(many), setOf(bob, dan), many, { "ref$it" }, names::get, { null }, emptyMap(), 1L)
        val forBob = SnapshotBuilder.build(big, bob, hint = null, pasteTokenForRecipient = null)
        assertEquals(SnapshotBuilder.MAX_ROWS, forBob.rows.size)
        assertEquals(20, forBob.omittedRows)
        // All 160 of Bob's rows made it; only Dan's oldest were left out.
        assertEquals(160, forBob.rows.count { row -> row.shares.any { it.participant == ParcelActor.Me } })
        assertNotNull(ChapterSnapshotFormat.decode(ChapterSnapshotFormat.encode(forBob)))
    }

    @Test
    fun `a closed chapter says so`() {
        val closed = SnapshotSource(chapter.copy(state = ChapterState.CLOSED), ChapterMath.compute(goaRows), setOf(bob, dan), goaRows, { "ref$it" }, names::get, { null }, emptyMap(), 1L)
        assertEquals(ChapterState.CLOSED, SnapshotBuilder.build(closed, bob, null, null).state)
    }
}
