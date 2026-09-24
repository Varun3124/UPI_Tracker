package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version 2 is the mailbox's parcel. It names accounts, so what matters most is that it is only ever
 * read where it was meant to be read, and that an account id cannot be smuggled in or bent.
 */
class ParcelFormatV2Test {

    private val alice = "aliceUid00000000000000000000"
    private val charlie = "charlieUid000000000000000000"

    private fun row(
        shareRef: String? = "Q2hhaW5zQXJlR29vZA",
        legacyRef: String? = null,
        payer: ParcelActor = ParcelActor.Linked(charlie, "Charlie"),
        payee: ParcelActor = ParcelActor.Shop("Swiggy"),
        shares: List<ParcelShare> = listOf(
            ParcelShare("PAYER", ParcelActor.Sender, 10000L),
            ParcelShare("PAYER", ParcelActor.Me, 10000L),
            ParcelShare("PAYER", ParcelActor.Linked(charlie, "Charlie"), 10000L)
        ),
        iouRecovery: IouRecovery? = IouRecovery.FROM_SECONDARY_PAYERS
    ) = ParcelTransaction(
        sourceId = 0L,
        dateEpoch = 1_700_000_000_000L,
        amountPaise = 30000L,
        payer = payer,
        payee = payee,
        ledgerEffect = LedgerEffect.DEBT,
        upiRefId = null,
        reason = "Dinner",
        shares = shares,
        shareRef = shareRef,
        legacyRef = legacyRef,
        iouRecovery = iouRecovery
    )

    private fun mailboxParcel(vararg rows: ParcelTransaction) =
        Parcel(ParcelFormat.MAILBOX_VERSION, null, rows.toList())

    private fun readBack(parcel: Parcel): Parcel {
        val result = ParcelFormat.parse(ParcelFormat.format(parcel), ParcelFormat.MAILBOX_VERSION)
        assertTrue("expected Ok, got $result", result is ParcelDecodeResult.Ok)
        return (result as ParcelDecodeResult.Ok).parcel
    }

    @Test
    fun `a mailbox parcel survives a round trip, linked people and all`() {
        val parcel = mailboxParcel(
            row(),
            row(
                shareRef = "second-ref_01",
                legacyRef = "a1b2c3d4e5f6a7b8.41",
                iouRecovery = IouRecovery.FROM_SECONDARY_PAYEES
            )
        )
        assertEquals(parcel, readBack(parcel))
    }

    @Test
    fun `a linked name keeps its colons, pipes and backslashes`() {
        val nasty = "Ch:ar|lie\\ \n: end"
        val parcel = mailboxParcel(row(payer = ParcelActor.Linked(charlie, nasty), shares = emptyList()))
        assertEquals(ParcelActor.Linked(charlie, nasty), readBack(parcel).transactions.single().payer)
    }

    @Test
    fun `a pasted parcel never reads a mailbox body`() {
        val result = ParcelFormat.parse(ParcelFormat.format(mailboxParcel(row())))
        assertTrue(result is ParcelDecodeResult.Failed)
    }

    @Test
    fun `the mailbox never reads a pasted body`() {
        val pasted = Parcel(
            ParcelFormat.VERSION,
            "tok3n",
            listOf(
                row(payer = ParcelActor.Sender, payee = ParcelActor.Me, shares = emptyList())
                    .copy(sourceId = 41L, shareRef = null)
            )
        )
        val result = ParcelFormat.parse(ParcelFormat.format(pasted), ParcelFormat.MAILBOX_VERSION)
        assertTrue(result is ParcelDecodeResult.Failed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pasted parcel cannot name an account`() {
        ParcelFormat.format(Parcel(ParcelFormat.VERSION, "tok3n", listOf(row().copy(sourceId = 41L))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mailbox parcel is never encoded for pasting`() {
        ParcelCodec.encode(mailboxParcel(row()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mailbox row with no share reference cannot be written`() {
        ParcelFormat.format(mailboxParcel(row(shareRef = null)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mailbox row that does not say which side pays back cannot be written`() {
        ParcelFormat.format(mailboxParcel(row(iouRecovery = null)))
    }

    @Test
    fun `an account tag in a pasted body is not an actor`() {
        listOf(
            "V|1|tok|1\nT|1|100|200|L:$alice:Alice|M|DEBT||x",
            "V|3|tok|1\nT|1|100|200|L:$alice:Alice|M|DEBT||x|PAYERS"
        ).forEach { text ->
            assertTrue(text, ParcelFormat.parse(text) is ParcelDecodeResult.Failed)
        }
    }

    @Test
    fun `the smallest valid mailbox row reads`() {
        assertTrue(
            ParcelFormat.parse("V|2|1\nT|ref|100|200|S|M|DEBT||x||PAYERS", ParcelFormat.MAILBOX_VERSION)
                is ParcelDecodeResult.Ok
        )
    }

    @Test
    fun `malformed references, account ids and pay-back settings are rejected`() {
        val header = "V|2|1\n"
        listOf(
            "T||100|200|S|M|DEBT||x||PAYERS",                 // no share reference
            "T|has space|100|200|S|M|DEBT||x||PAYERS",        // not a reference at all
            "T|ref|100|200|S|M|DEBT||x|PAYERS",               // the earlier-reference field is missing
            "T|ref|100|200|L:bad uid:Al|M|DEBT||x||PAYERS",   // not an account id
            "T|ref|100|200|L::Al|M|DEBT||x||PAYERS",          // no account id
            "T|ref|100|200|L:$alice:|M|DEBT||x||PAYERS",      // no name
            "T|ref|100|200|S|M|DEBT||x|not/a/ref|PAYERS",     // a malformed earlier reference
            "T|ref|100|200|S|M|DEBT||x|",                     // the pay-back field is missing
            "T|ref|100|200|S|M|DEBT||x||",                    // an empty pay-back setting
            "T|ref|100|200|S|M|DEBT||x||SOMETIMES"            // an unknown pay-back setting
        ).forEach { line ->
            assertTrue(
                line,
                ParcelFormat.parse(header + line, ParcelFormat.MAILBOX_VERSION) is ParcelDecodeResult.Failed
            )
        }
    }

    @Test
    fun `which debts a split keeps survives the round trip`() {
        val parcel = mailboxParcel(
            row(
                shares = listOf(
                    ParcelShare("PAYER", ParcelActor.Sender, 20000L),
                    ParcelShare("PAYER", ParcelActor.Me, 10000L, keepPayeeLeg = false, keepPayerLeg = true),
                    ParcelShare("PAYEE", ParcelActor.Linked(charlie, "Charlie"), 30000L, keepPayeeLeg = true, keepPayerLeg = false)
                )
            )
        )
        assertEquals(parcel, readBack(parcel))
    }

    @Test
    fun `a mailbox split that does not say which debts it keeps is rejected`() {
        val header = "V|2|1\nT|ref|100|200|S|M|DEBT||x||PAYERS\n"
        listOf("S|PAYER|M|200", "S|PAYER|M|200|", "S|PAYER|M|200|1", "S|PAYER|M|200|12").forEach { line ->
            assertTrue(line, ParcelFormat.parse(header + line, ParcelFormat.MAILBOX_VERSION) is ParcelDecodeResult.Failed)
        }
        assertTrue(ParcelFormat.parse(header + "S|PAYER|M|200|10", ParcelFormat.MAILBOX_VERSION) is ParcelDecodeResult.Ok)
    }
}
