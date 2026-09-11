package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parcel is money travelling through a chat app, so most of these are about the ways a chat app
 * damages text: wrapping it, truncating it, and capitalising the first character of it.
 */
class ParcelCodecTest {

    private fun tx(
        sourceId: Long = 41L,
        payer: ParcelActor = ParcelActor.Sender,
        payee: ParcelActor = ParcelActor.Me,
        upiRefId: String? = null,
        reason: String? = "Dinner",
        shares: List<ParcelShare> = emptyList()
    ) = ParcelTransaction(
        sourceId = sourceId,
        dateEpoch = 1_700_000_000_000L,
        amountPaise = 30000L,
        payer = payer,
        payee = payee,
        ledgerEffect = LedgerEffect.DEBT,
        upiRefId = upiRefId,
        reason = reason,
        shares = shares
    )

    private fun parcelOf(vararg transactions: ParcelTransaction) =
        Parcel(ParcelFormat.VERSION, "tok3n", transactions.toList())

    private fun roundTrip(parcel: Parcel): Parcel {
        val result = ParcelCodec.decode(ParcelCodec.encode(parcel))
        assertTrue("expected Ok, got $result", result is ParcelDecodeResult.Ok)
        return (result as ParcelDecodeResult.Ok).parcel
    }

    private fun failureOf(text: String): String {
        val result = ParcelCodec.decode(text)
        assertTrue("expected Failed, got $result", result is ParcelDecodeResult.Failed)
        return (result as ParcelDecodeResult.Failed).reason
    }

    @Test
    fun `every kind of actor survives a round trip`() {
        val parcel = parcelOf(
            tx(sourceId = 1L, payer = ParcelActor.Sender, payee = ParcelActor.Me),
            tx(sourceId = 2L, payer = ParcelActor.Me, payee = ParcelActor.Shop("Swiggy")),
            tx(sourceId = 3L, payer = ParcelActor.Person("Charlie"), payee = ParcelActor.Me),
            tx(sourceId = 4L, payer = ParcelActor.Unnamed("SOME VPA"), payee = ParcelActor.Me)
        )
        assertEquals(parcel, roundTrip(parcel))
    }

    @Test
    fun `shares survive a round trip on both sides`() {
        val parcel = parcelOf(
            tx(
                payer = ParcelActor.Sender,
                payee = ParcelActor.Shop("Swiggy"),
                shares = listOf(
                    ParcelShare("PAYER", ParcelActor.Sender, 10000L),
                    ParcelShare("PAYER", ParcelActor.Me, 10000L),
                    ParcelShare("PAYER", ParcelActor.Person("Charlie"), 10000L)
                )
            )
        )
        assertEquals(parcel, roundTrip(parcel))
    }

    @Test
    fun `shares stay attached to the transaction above them`() {
        val parcel = parcelOf(
            tx(sourceId = 1L, shares = listOf(ParcelShare("PAYER", ParcelActor.Me, 100L))),
            tx(sourceId = 2L, shares = emptyList()),
            tx(sourceId = 3L, shares = listOf(ParcelShare("PAYEE", ParcelActor.Sender, 200L)))
        )
        val decoded = roundTrip(parcel)
        assertEquals(1, decoded.transactions[0].shares.size)
        assertEquals(0, decoded.transactions[1].shares.size)
        assertEquals(1, decoded.transactions[2].shares.size)
    }

    @Test
    fun `delimiters and newlines inside free text survive verbatim`() {
        val nasty = "a|b\\c\nd\re|| \\p end"
        val parcel = parcelOf(
            tx(reason = nasty, payer = ParcelActor.Person(nasty), payee = ParcelActor.Me)
        )
        val decoded = roundTrip(parcel)
        assertEquals(nasty, decoded.transactions[0].reason)
        assertEquals(ParcelActor.Person(nasty), decoded.transactions[0].payer)
    }

    @Test
    fun `a trailing backslash in a name does not swallow the delimiter`() {
        val parcel = parcelOf(tx(payer = ParcelActor.Person("Raj\\"), payee = ParcelActor.Me))
        assertEquals(parcel, roundTrip(parcel))
    }

    @Test
    fun `emoji and a long reason survive`() {
        val parcel = parcelOf(tx(reason = "Chai 🍵 " + "x".repeat(500)))
        assertEquals(parcel, roundTrip(parcel))
    }

    @Test
    fun `a null reason stays null rather than becoming empty`() {
        val decoded = roundTrip(parcelOf(tx(reason = null, upiRefId = null)))
        assertEquals(null, decoded.transactions[0].reason)
        assertEquals(null, decoded.transactions[0].upiRefId)
    }

    @Test
    fun `an empty parcel round trips`() {
        val parcel = Parcel(ParcelFormat.VERSION, "tok3n", emptyList())
        assertEquals(parcel, roundTrip(parcel))
    }

    @Test
    fun `the encoded form carries nothing a chat app would mangle`() {
        val encoded = ParcelCodec.encode(parcelOf(tx(reason = "a|b\nc")))
        assertTrue(encoded, Regex("^UPIX1\\.[0-9a-z]+\\.[A-Za-z0-9_-]+$").matches(encoded))
    }

    @Test
    fun `line breaks and spaces inserted anywhere are ignored`() {
        val encoded = ParcelCodec.encode(parcelOf(tx()))
        val wrapped = "  " + encoded.chunked(20).joinToString("\n") + "\n"
        assertEquals(roundTrip(parcelOf(tx())), (ParcelCodec.decode(wrapped) as ParcelDecodeResult.Ok).parcel)
    }

    @Test
    fun `a single flipped character is caught by the checksum`() {
        val encoded = ParcelCodec.encode(parcelOf(tx(reason = "Dinner at the usual place")))
        val body = encoded.substringAfterLast('.')
        val flippedChar = if (body[4] == 'A') 'B' else 'A'
        val corrupted = encoded.substringBeforeLast('.') + "." +
            body.substring(0, 4) + flippedChar + body.substring(5)
        assertTrue(failureOf(corrupted).contains("damaged"))
    }

    @Test
    fun `a truncated parcel is rejected rather than half read`() {
        val encoded = ParcelCodec.encode(parcelOf(tx(), tx(sourceId = 42L), tx(sourceId = 43L)))
        assertTrue(failureOf(encoded.dropLast(12)).isNotEmpty())
    }

    @Test
    fun `text that is not a parcel at all is rejected`() {
        assertTrue(failureOf("").isNotEmpty())
        assertTrue(failureOf("   \n  ").isNotEmpty())
        assertTrue(failureOf("hello there").isNotEmpty())
        assertTrue(failureOf("UPIX1.abc").isNotEmpty())
    }

    @Test
    fun `a newer format version says to update rather than blaming the parcel`() {
        val encoded = ParcelCodec.encode(parcelOf(tx()))
        val future = "UPIX2" + encoded.removePrefix("UPIX1")
        assertTrue(failureOf(future).contains("newer version"))
    }

    @Test
    fun `valid base64 that is not a compressed parcel is rejected`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(64) { it.toByte() })
        val checksum = java.util.zip.CRC32()
            .apply { update(java.util.Base64.getUrlDecoder().decode(payload)) }.value
        assertTrue(failureOf("UPIX1.${checksum.toString(36)}.$payload").isNotEmpty())
    }

    @Test
    fun `a malformed line names the line it was on`() {
        assertTrue(ParcelFormat.parse("V|1|tok|1\nT|1|2|3").let { it as ParcelDecodeResult.Failed }
            .reason.contains("Line 2"))
    }

    @Test
    fun `a transaction count that disagrees with the body is rejected`() {
        val body = "V|1|tok|3\nT|1|100|200|S|M|DEBT||Dinner"
        assertTrue((ParcelFormat.parse(body) as ParcelDecodeResult.Failed).reason.contains("cut short"))
    }

    @Test
    fun `nonsense field values are rejected`() {
        val header = "V|1|tok|1\n"
        val rejected = listOf(
            "T|1|100|-5|S|M|DEBT||x",      // negative amount
            "T|1|100|abc|S|M|DEBT||x",     // unreadable amount
            "T|0|100|200|S|M|DEBT||x",     // no source id
            "T|1|100|200|S|M|MAYBE||x",    // unknown ledger effect
            "T|1|100|200|Z|M|DEBT||x",     // unknown actor tag
            "T|1|100|200|S|S|DEBT||x",     // both sides the same party
            "T|1|100|200|M|M|DEBT||x"
        )
        rejected.forEach { line ->
            assertTrue(line, ParcelFormat.parse(header + line) is ParcelDecodeResult.Failed)
        }
    }

    @Test
    fun `a split that is not on a side or is charged to a shop is rejected`() {
        val header = "V|1|tok|1\nT|1|100|200|S|M|DEBT||x\n"
        listOf(
            "S|MIDDLE|M|100",
            "S|PAYER|C:Swiggy|100",
            "S|PAYER|U:whoever|100",
            "S|PAYER|M|-1",
            "S|PAYER|M"
        ).forEach { line ->
            assertTrue(line, ParcelFormat.parse(header + line) is ParcelDecodeResult.Failed)
        }
    }

    @Test
    fun `a split before any transaction is rejected`() {
        val text = "V|1|tok|1\nS|PAYER|M|100\nT|1|100|200|S|M|DEBT||x"
        assertTrue((ParcelFormat.parse(text) as ParcelDecodeResult.Failed).reason.contains("Line 2"))
    }

    @Test
    fun `a parcel with no origin token is rejected`() {
        assertTrue(ParcelFormat.parse("V|1||0") is ParcelDecodeResult.Failed)
    }

    @Test
    fun `two hundred transactions still fit in a chat message`() {
        val parcel = Parcel(
            ParcelFormat.VERSION,
            "tok3n",
            (1L..200L).map { id ->
                tx(
                    sourceId = id,
                    reason = "Dinner $id",
                    shares = listOf(
                        ParcelShare("PAYER", ParcelActor.Sender, 15000L),
                        ParcelShare("PAYER", ParcelActor.Me, 15000L)
                    )
                )
            }
        )
        val encoded = ParcelCodec.encode(parcel)
        assertEquals(parcel, roundTrip(parcel))
        assertTrue("encoded length was ${encoded.length}", encoded.length < 12_000)
    }
}
