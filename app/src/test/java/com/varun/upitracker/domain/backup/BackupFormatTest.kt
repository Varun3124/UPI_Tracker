package com.varun.upitracker.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is somebody's whole financial history, so most of these are about the ways a text
 * encoding can quietly change a value rather than fail loudly.
 */
class BackupFormatTest {

    private fun meta(
        schemaVersion: Int = 15,
        deviceId: String = "device-a",
        revision: Long = 3L
    ) = BackupMeta(
        schemaVersion = schemaVersion,
        appVersionCode = 4,
        createdAtEpoch = 1_700_000_000_000L,
        deviceId = deviceId,
        revision = revision
    )

    private fun docOf(
        prefs: List<BackupPref> = emptyList(),
        tables: List<BackupTable> = emptyList()
    ) = BackupDocument(meta(), prefs, tables)

    private fun roundTrip(document: BackupDocument): BackupDocument {
        val result = BackupFormat.parse(BackupFormat.format(document))
        assertTrue("expected Ok, got $result", result is BackupParseResult.Ok)
        return (result as BackupParseResult.Ok).document
    }

    private fun failureOf(text: String): String {
        val result = BackupFormat.parse(text)
        assertTrue("expected Failed, got $result", result is BackupParseResult.Failed)
        return (result as BackupParseResult.Failed).reason
    }

    // --- the value that matters most ------------------------------------------------------------

    @Test
    fun `null and the empty string are not the same value`() {
        // The whole reason NULL gets its own marker. A null reason and an empty one are different
        // rows, and a format that conflated them would corrupt data without ever failing.
        val table = BackupTable("transactions", listOf("id", "reason"), listOf(listOf("1", null), listOf("2", "")))
        val decoded = roundTrip(docOf(tables = listOf(table)))
        assertNull(decoded.tables[0].rows[0][1])
        assertEquals("", decoded.tables[0].rows[1][1])
    }

    @Test
    fun `a literal backslash-zero is not mistaken for null`() {
        // escape() turns it into \\0, which is a different field from the \0 marker.
        val table = BackupTable("t", listOf("v"), listOf(listOf("\\0"), listOf(null)))
        val decoded = roundTrip(docOf(tables = listOf(table)))
        assertEquals("\\0", decoded.tables[0].rows[0][0])
        assertNull(decoded.tables[0].rows[1][0])
    }

    @Test
    fun `delimiters and newlines inside a value survive verbatim`() {
        val nasty = "a|b\\c\nd\re|| \\p \\0 \\n end"
        val table = BackupTable("t", listOf("note"), listOf(listOf(nasty)))
        assertEquals(nasty, roundTrip(docOf(tables = listOf(table))).tables[0].rows[0][0])
    }

    @Test
    fun `a trailing backslash does not swallow the delimiter`() {
        val table = BackupTable("t", listOf("a", "b"), listOf(listOf("ends with\\", "second")))
        val row = roundTrip(docOf(tables = listOf(table))).tables[0].rows[0]
        assertEquals("ends with\\", row[0])
        assertEquals("second", row[1])
    }

    @Test
    fun `emoji and long text survive`() {
        val value = "Chai 🍵 " + "x".repeat(2000)
        val table = BackupTable("t", listOf("v"), listOf(listOf(value)))
        assertEquals(value, roundTrip(docOf(tables = listOf(table))).tables[0].rows[0][0])
    }

    @Test
    fun `extreme integers survive as text`() {
        val values = listOf(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "0", "-1")
        val table = BackupTable("t", listOf("v"), values.map { listOf(it) })
        val decoded = roundTrip(docOf(tables = listOf(table)))
        assertEquals(values, decoded.tables[0].rows.map { it[0] })
    }

    @Test
    fun `a table name or column name holding a delimiter survives`() {
        val table = BackupTable("odd|name", listOf("a|b", "c\\d"), listOf(listOf("1", "2")))
        val decoded = roundTrip(docOf(tables = listOf(table))).tables[0]
        assertEquals("odd|name", decoded.name)
        assertEquals(listOf("a|b", "c\\d"), decoded.columns)
    }

    // --- structure ------------------------------------------------------------------------------

    @Test
    fun `meta survives a round trip`() {
        assertEquals(meta(), roundTrip(docOf()).meta)
    }

    @Test
    fun `an empty document round trips`() {
        val decoded = roundTrip(docOf())
        assertTrue(decoded.tables.isEmpty())
        assertTrue(decoded.prefs.isEmpty())
    }

    @Test
    fun `a table with no rows round trips as a table, not as nothing`() {
        // An empty table and a missing table mean different things on restore.
        val decoded = roundTrip(docOf(tables = listOf(BackupTable("budget_settings", listOf("id"), emptyList()))))
        assertEquals(1, decoded.tables.size)
        assertEquals("budget_settings", decoded.tables[0].name)
        assertTrue(decoded.tables[0].rows.isEmpty())
    }

    @Test
    fun `rows stay attached to the table above them`() {
        val decoded = roundTrip(
            docOf(
                tables = listOf(
                    BackupTable("a", listOf("x"), listOf(listOf("1"), listOf("2"))),
                    BackupTable("b", listOf("y"), emptyList()),
                    BackupTable("c", listOf("z"), listOf(listOf("3")))
                )
            )
        )
        assertEquals(listOf("a", "b", "c"), decoded.tables.map { it.name })
        assertEquals(listOf(2, 0, 1), decoded.tables.map { it.rows.size })
    }

    @Test
    fun `every preference type round trips`() {
        val prefs = PrefType.entries.map { BackupPref("key_${it.tag}", it, "value|${it.tag}") }
        assertEquals(prefs, roundTrip(docOf(prefs = prefs)).prefs)
    }

    @Test
    fun `the correctness-critical preferences survive`() {
        // These are the ones a database-only backup would lose, with real consequences.
        val prefs = listOf(
            BackupPref("parcel_origin_7", PrefType.STRING, "a1b2c3d4e5f6a7b8"),
            BackupPref("category_split_backfill_v1_done", PrefType.BOOLEAN, "true"),
            BackupPref("onboarding_complete", PrefType.BOOLEAN, "true")
        )
        assertEquals(prefs, roundTrip(docOf(prefs = prefs)).prefs)
    }

    @Test
    fun `a document with many tables and rows round trips intact`() {
        val tables = (1..12).map { t ->
            BackupTable(
                name = "table_$t",
                columns = listOf("id", "amountPaise", "reason"),
                rows = (1..40).map { r -> listOf("$r", "${r * 100}", if (r % 5 == 0) null else "row $r of $t") }
            )
        }
        val decoded = roundTrip(docOf(tables = tables))
        assertEquals(tables, decoded.tables)
    }

    @Test
    fun `a trailing newline is tolerated`() {
        val text = BackupFormat.format(docOf(tables = listOf(BackupTable("t", listOf("v"), listOf(listOf("1"))))))
        val result = BackupFormat.parse(text + "\n")
        assertTrue("$result", result is BackupParseResult.Ok)
    }

    // --- rejection ------------------------------------------------------------------------------

    @Test
    fun `text that is not a backup is rejected`() {
        assertTrue(failureOf("").isNotEmpty())
        assertTrue(failureOf("hello there").isNotEmpty())
        assertTrue(failureOf("V|1|15").isNotEmpty())
    }

    @Test
    fun `a newer format version says to update rather than blaming the file`() {
        assertTrue(failureOf("V|2|15|4|1700000000000|device-a|3").contains("newer version"))
    }

    @Test
    fun `a row whose width disagrees with its header is rejected`() {
        val text = "V|1|15|4|1700000000000|device-a|3\nT|t|2|a|b\nR|1"
        assertTrue(failureOf(text).contains("Line 3"))
    }

    @Test
    fun `a header whose column count disagrees with its columns is rejected`() {
        val text = "V|1|15|4|1700000000000|device-a|3\nT|t|3|a|b"
        assertTrue(failureOf(text).contains("Line 2"))
    }

    @Test
    fun `a row before any table is rejected`() {
        val text = "V|1|15|4|1700000000000|device-a|3\nR|1|2\nT|t|2|a|b"
        assertTrue(failureOf(text).contains("Line 2"))
    }

    @Test
    fun `unknown lines and nonsense fields are rejected`() {
        val header = "V|1|15|4|1700000000000|device-a|3\n"
        listOf(
            "Z|something",                    // unknown line kind
            "P|key|Q|value",                  // unknown pref type
            "P|key|S",                        // too few fields
            "T|t|notanumber|a",               // unreadable column count
            "T||1|a",                         // nameless table
            ""                                // a blank line in the middle
        ).forEach { line ->
            assertTrue(line, BackupFormat.parse(header + line + "\nT|x|1|c") is BackupParseResult.Failed)
        }
    }

    @Test
    fun `a malformed header is rejected field by field`() {
        listOf(
            "V|1|notanumber|4|1700000000000|device-a|3",
            "V|1|15|notanumber|1700000000000|device-a|3",
            "V|1|15|4|notanumber|device-a|3",
            "V|1|15|4|1700000000000||3",
            "V|1|15|4|1700000000000|device-a|-1",
            "V|1|15|4|1700000000000|device-a|notanumber"
        ).forEach { header ->
            assertTrue(header, BackupFormat.parse(header) is BackupParseResult.Failed)
        }
    }

    @Test
    fun `the same table twice is rejected rather than silently merged`() {
        val text = "V|1|15|4|1700000000000|device-a|3\nT|t|1|a\nR|1\nT|t|1|a\nR|2"
        assertTrue(failureOf(text).contains("twice"))
    }
}
