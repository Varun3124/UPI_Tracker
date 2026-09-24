package com.varun.upitracker.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cross-version rules: what happens when a backup and the schema disagree about columns. */
class ColumnMappingTest {

    @Test
    fun `a matching schema keeps everything in the backup's own order`() {
        val backup = listOf("id", "amountPaise", "reason")
        val mapping = mapColumns(backup, setOf("reason", "id", "amountPaise"))
        // Backup order, not live order: the caller reads values out of backup rows by position.
        assertEquals(backup, mapping.kept)
        assertTrue(mapping.dropped.isEmpty())
    }

    @Test
    fun `columns the schema gained since the backup are simply absent`() {
        // A v14 backup into a v15 schema. sharedRefId and rawLabel are not named in the INSERT, so
        // they take their declared defaults -- which for those two means null, correctly.
        val v14 = listOf("id", "amountPaise", "reason", "upiRefId")
        val v15 = setOf("id", "amountPaise", "reason", "upiRefId", "sharedRefId", "rawLabel")
        val mapping = mapColumns(v14, v15)
        assertEquals(v14, mapping.kept)
        assertTrue(mapping.dropped.isEmpty())
    }

    @Test
    fun `a v15 backup restores into v16 with no share references`() {
        // The friends mailbox added transactions.shareRef and four tables. The column is not named in
        // the INSERT, so every restored row starts with no reference -- the next send mints one. The
        // tables are absent from the backup and so come back empty, which is right: a v15 install
        // never linked anyone.
        val v15 = listOf("id", "amountPaise", "sharedRefId")
        val v16 = setOf("id", "amountPaise", "sharedRefId", "shareRef")
        val mapping = mapColumns(v15, v16)
        assertEquals(v15, mapping.kept)
        assertTrue(mapping.dropped.isEmpty())
    }

    @Test
    fun `a column the schema has dropped is discarded and named`() {
        val mapping = mapColumns(listOf("id", "legacyField", "reason"), setOf("id", "reason"))
        assertEquals(listOf("id", "reason"), mapping.kept)
        assertEquals(listOf("legacyField"), mapping.dropped)
    }

    @Test
    fun `a table with nothing in common is reported as empty rather than half inserted`() {
        val mapping = mapColumns(listOf("a", "b"), setOf("x", "y"))
        assertTrue(mapping.isEmpty)
        assertEquals(listOf("a", "b"), mapping.dropped)
    }

    @Test
    fun `an empty backup table maps to nothing`() {
        val mapping = mapColumns(emptyList(), setOf("id"))
        assertTrue(mapping.isEmpty)
        assertTrue(mapping.dropped.isEmpty())
    }

    @Test
    fun `matching is exact, not case-insensitive or trimmed`() {
        // SQLite column names are case-insensitive in SQL but we compare the strings we were given;
        // a mismatch must show up as dropped rather than quietly resolving to the wrong column.
        val mapping = mapColumns(listOf("Id", " id", "id"), setOf("id"))
        assertEquals(listOf("id"), mapping.kept)
        assertEquals(listOf("Id", " id"), mapping.dropped)
    }
}
