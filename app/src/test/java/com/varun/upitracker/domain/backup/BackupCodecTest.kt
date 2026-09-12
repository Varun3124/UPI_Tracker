package com.varun.upitracker.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun document(tableCount: Int = 3, rowsPer: Int = 50) = BackupDocument(
        meta = BackupMeta(15, 4, 1_700_000_000_000L, "device-a", 7L),
        prefs = listOf(
            BackupPref("onboarding_complete", PrefType.BOOLEAN, "true"),
            BackupPref("parcel_origin_7", PrefType.STRING, "a1b2c3d4e5f6a7b8")
        ),
        tables = (1..tableCount).map { t ->
            BackupTable(
                "table_$t",
                listOf("id", "amountPaise", "reason"),
                (1..rowsPer).map { r -> listOf("$r", "${r * 137}", if (r % 7 == 0) null else "row $r") }
            )
        }
    )

    private fun encoded(document: BackupDocument) = BackupCodec.encode(BackupFormat.format(document))

    private fun failureOf(bytes: ByteArray): String {
        val result = BackupCodec.decode(bytes)
        assertTrue("expected Failed, got $result", result is BackupParseResult.Failed)
        return (result as BackupParseResult.Failed).reason
    }

    @Test
    fun `a document survives the wire`() {
        val original = document()
        val result = BackupCodec.decode(encoded(original))
        assertTrue("$result", result is BackupParseResult.Ok)
        assertEquals(original, (result as BackupParseResult.Ok).document)
    }

    @Test
    fun `the envelope starts with the magic and a version`() {
        val bytes = encoded(document())
        assertEquals("DHMB", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(1, bytes[4].toInt())
    }

    @Test
    fun `a repetitive dump compresses hard`() {
        // A table dump is the most repetitive data there is; if this regresses, the format has
        // gained per-row overhead that will eventually collide with Drive's upload ceiling.
        val original = document(tableCount = 10, rowsPer = 400)
        val plaintext = BackupFormat.format(original)
        val bytes = encoded(original)
        assertTrue(
            "plaintext=${plaintext.length} encoded=${bytes.size}",
            bytes.size < plaintext.length / 4
        )
    }

    @Test
    fun `a file that is not ours is rejected before any work`() {
        assertTrue(failureOf(ByteArray(0)).isNotEmpty())
        assertTrue(failureOf(ByteArray(4)).isNotEmpty())
        assertTrue(failureOf("not a backup at all, just text".toByteArray()).contains("not a DhanMoney backup"))
    }

    @Test
    fun `a newer envelope version says to update`() {
        val bytes = encoded(document())
        bytes[4] = 2
        assertTrue(failureOf(bytes).contains("newer version"))
    }

    @Test
    fun `a single flipped payload byte is caught by the checksum`() {
        val bytes = encoded(document())
        val index = bytes.size / 2
        bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        assertTrue(failureOf(bytes).contains("damaged"))
    }

    @Test
    fun `a flipped checksum byte is caught too`() {
        val bytes = encoded(document())
        bytes[5] = (bytes[5].toInt() xor 0xFF).toByte()
        assertTrue(failureOf(bytes).contains("damaged"))
    }

    @Test
    fun `a truncated download is rejected rather than half read`() {
        val bytes = encoded(document())
        assertTrue(failureOf(bytes.copyOfRange(0, bytes.size - 40)).isNotEmpty())
    }

    @Test
    fun `valid deflate that is not a backup document is rejected`() {
        // Right envelope, right checksum, contents that are not the grammar.
        val bytes = BackupCodec.encode("this is not a backup document")
        assertTrue(failureOf(bytes).isNotEmpty())
    }
}
