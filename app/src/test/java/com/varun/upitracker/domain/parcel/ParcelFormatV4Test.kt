package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pasted parcel that says who wrote it, and the older ones that do not. */
class ParcelFormatV4Test {

    private val row = ParcelTransaction(
        sourceId = 41L,
        dateEpoch = 1_700_000_000_000L,
        amountPaise = 30000L,
        payer = ParcelActor.Sender,
        payee = ParcelActor.Me,
        ledgerEffect = LedgerEffect.DEBT,
        upiRefId = null,
        reason = "Dinner",
        shares = emptyList(),
        iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
    )

    private fun parsed(text: String): Parcel {
        val result = ParcelFormat.parse(text)
        assertTrue("expected Ok, got $result", result is ParcelDecodeResult.Ok)
        return (result as ParcelDecodeResult.Ok).parcel
    }

    @Test
    fun `the writer's alias and UPI ID survive a round trip`() {
        val parcel = Parcel(ParcelFormat.VERSION, "tok3n", listOf(row), senderAlias = "Asha | K", senderUpiId = "asha.k-2@okhdfc")
        val decoded = ParcelCodec.decode(ParcelCodec.encode(parcel))
        assertEquals(ParcelDecodeResult.Ok(parcel), decoded)
    }

    @Test
    fun `a UPI ID is optional`() {
        val parcel = Parcel(ParcelFormat.VERSION, "tok3n", listOf(row), senderAlias = "Asha")
        assertTrue(ParcelFormat.format(parcel).startsWith("V|4|tok3n|Asha||1"))
        assertNull(parsed(ParcelFormat.format(parcel)).senderUpiId)
    }

    @Test
    fun `a UPI ID is kept the way it will be compared`() {
        val parcel = parsed("V|4|tok|Asha|  Asha@OKHDFC |1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS")
        assertEquals("asha@okhdfc", parcel.senderUpiId)
    }

    @Test
    fun `a UPI ID that does not read as one is dropped rather than refusing the parcel`() {
        val parcel = parsed("V|4|tok|Asha|not a upi id|1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS")
        assertEquals("Asha", parcel.senderAlias)
        assertNull(parcel.senderUpiId)
    }

    @Test
    fun `a version 4 parcel that does not say who wrote it is refused`() {
        val result = ParcelFormat.parse("V|4|tok|  ||1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS")
        assertTrue(result is ParcelDecodeResult.Failed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a parcel without the writer's alias cannot be pasted`() {
        ParcelCodec.encode(Parcel(ParcelFormat.VERSION, "tok3n", listOf(row)))
    }

    @Test
    fun `a version 3 parcel from an older app still reads, naming nobody`() {
        val parcel = parsed("V|3|tok|1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS")
        assertEquals(3, parcel.version)
        assertNull(parcel.senderAlias)
        assertNull(parcel.senderUpiId)
        assertEquals(IouRecovery.FROM_SECONDARY_PAYERS, parcel.transactions.single().iouRecovery)
    }

    @Test
    fun `a version 1 parcel still reads, naming nobody`() {
        val parcel = parsed("V|1|tok|1\nT|41|100|200|S|M|DEBT||Dinner")
        assertNull(parcel.senderAlias)
    }

    @Test
    fun `a version 3 header with sender fields, or a version 4 one without, is refused`() {
        assertTrue(ParcelFormat.parse("V|3|tok|Asha||1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS") is ParcelDecodeResult.Failed)
        assertTrue(ParcelFormat.parse("V|4|tok|1\nT|41|100|200|S|M|DEBT||Dinner|PAYERS") is ParcelDecodeResult.Failed)
    }

    @Test
    fun `the mailbox never reads a version 4 body`() {
        val body = ParcelFormat.format(Parcel(ParcelFormat.VERSION, "tok3n", listOf(row), senderAlias = "Asha"))
        assertTrue(ParcelFormat.parse(body, ParcelFormat.MAILBOX_VERSION) is ParcelDecodeResult.Failed)
    }

    @Test
    fun `an alias is trimmed, squeezed and held to a length`() {
        assertEquals("Asha K", SenderIdentity.normaliseAlias("  Asha   K "))
        assertNull(SenderIdentity.normaliseAlias("   "))
        assertNull(SenderIdentity.normaliseAlias("x".repeat(SenderIdentity.MAX_ALIAS + 1)))
    }

    @Test
    fun `UPI IDs are recognised the way banks print them`() {
        assertEquals("9023631695-2@ybl", SenderIdentity.normaliseUpiId("9023631695-2@YBL"))
        assertEquals("paytm-69905863@ptybl", SenderIdentity.normaliseUpiId("PAYTM-69905863@PTYBL"))
        assertNull(SenderIdentity.normaliseUpiId("asha"))
        assertNull(SenderIdentity.normaliseUpiId("asha@"))
        assertNull(SenderIdentity.normaliseUpiId("@okhdfc"))
    }
}
