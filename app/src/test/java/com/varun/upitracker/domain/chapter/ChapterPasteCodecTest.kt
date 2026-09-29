package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelCodec
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.ParcelShare
import com.varun.upitracker.domain.parcel.ParcelTransaction
import com.varun.upitracker.domain.parcel.PasteCompression
import com.varun.upitracker.domain.parcel.PasteFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** docs/declarations-design.md S2: a static copy of a chapter, pasted to someone who is not linked. */
class ChapterPasteCodecTest {

    private val dan = ParcelActor.Person("Dan")

    /** Alice's Goa trip, written for Bob: section 4.4 of the design doc. */
    private val snapshot = ChapterSnapshot(
        shareId = MailboxIds.newRandomId(),
        version = 3L,
        state = ChapterState.OPEN,
        name = "Goa trip",
        notes = "Three nights",
        sentEpoch = 1_000L,
        members = listOf(ParcelActor.Sender, ParcelActor.Me, dan),
        nets = listOf(
            SnapshotNet(ParcelActor.Sender, 500_000L),
            SnapshotNet(ParcelActor.Me, -130_000L),
            SnapshotNet(dan, -370_000L)
        ),
        plan = listOf(
            SnapshotPayment(dan, ParcelActor.Sender, 370_000L),
            SnapshotPayment(ParcelActor.Me, ParcelActor.Sender, 130_000L)
        ),
        hint = null,
        rows = listOf(
            ParcelTransaction(
                sourceId = 0L,
                dateEpoch = 100L,
                amountPaise = 900_000L,
                payer = ParcelActor.Sender,
                payee = ParcelActor.Shop("Hotel"),
                ledgerEffect = LedgerEffect.DEBT,
                upiRefId = null,
                reason = "Hotel",
                shares = listOf(
                    ParcelShare("PAYER", ParcelActor.Sender, 300_000L),
                    ParcelShare("PAYER", ParcelActor.Me, 300_000L),
                    ParcelShare("PAYER", dan, 300_000L)
                ),
                shareRef = MailboxIds.newRandomId(),
                legacyRef = null,
                iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
            )
        ),
        extras = listOf(SnapshotRowExtra(pending = false, sourceRef = null)),
        omittedRows = 0
    )

    @Test
    fun `a copy survives the paste`() {
        val pasted = ChapterPasteCodec.encode(snapshot)
        assertTrue(pasted.startsWith("${ChapterPasteCodec.PREFIX}."))
        val decoded = ChapterPasteCodec.decode(pasted) as ChapterPasteResult.Ok
        assertEquals(snapshot, decoded.snapshot)
        // What the copy keeps reads back as the same snapshot.
        assertEquals(snapshot, ChapterSnapshotFormat.decode(decoded.body))
    }

    @Test
    fun `chat apps' line breaks and spaces do no harm`() {
        val pasted = ChapterPasteCodec.encode(snapshot)
        val mangled = " " + pasted.chunked(40).joinToString("\n") + "\n"
        assertTrue(ChapterPasteCodec.decode(mangled) is ChapterPasteResult.Ok)
    }

    @Test
    fun `a damaged copy is refused`() {
        val pasted = ChapterPasteCodec.encode(snapshot)
        assertTrue(ChapterPasteCodec.decode(pasted.dropLast(5)) is ChapterPasteResult.Failed)
        val flipped = pasted.dropLast(1) + (if (pasted.last() == 'A') 'B' else 'A')
        assertTrue(ChapterPasteCodec.decode(flipped) is ChapterPasteResult.Failed)
    }

    @Test
    fun `a paste knows copies apart from parcels`() {
        val parcel = ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "tok", emptyList()))
        assertFalse(ChapterPasteCodec.looksLikeCopy(parcel))
        assertTrue(ChapterPasteCodec.decode(parcel) is ChapterPasteResult.Failed)
        assertTrue(ChapterPasteCodec.looksLikeCopy(ChapterPasteCodec.encode(snapshot)))
        // A newer app's copy is still recognised as one, so the reader is told to update.
        val newer = ChapterPasteCodec.decode("UPIC2.abc.def") as ChapterPasteResult.Failed
        assertTrue(newer.reason.contains("newer version"))
    }

    @Test
    fun `nobody is named by account, and no agreement is spoken for`() {
        val linked = snapshot.copy(
            members = listOf(ParcelActor.Sender, ParcelActor.Me, ParcelActor.Linked(MailboxIds.newRandomId(), "Dan")),
            nets = emptyList(),
            plan = emptyList()
        )
        val hinted = snapshot.copy(hint = SnapshotHint(MailboxIds.newRandomId(), 5_000L))
        assertFalse(ChapterPasteCodec.isPastable(linked))
        assertFalse(ChapterPasteCodec.isPastable(hinted))
        assertTrue(ChapterPasteCodec.isPastable(snapshot))

        // Written by hand, bypassing encode: still refused on the way in.
        listOf(linked, hinted).forEach { refused ->
            val raw = Base64.getUrlDecoder().decode(ChapterSnapshotFormat.encode(refused))
            val pasted = PasteFraming.frame(ChapterPasteCodec.PREFIX, PasteCompression.deflate(raw))
            assertTrue(ChapterPasteCodec.decode(pasted) is ChapterPasteResult.Failed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a copy naming someone by account is never written`() {
        ChapterPasteCodec.encode(snapshot.copy(hint = SnapshotHint(MailboxIds.newRandomId(), 1L)))
    }
}
