package com.varun.upitracker.domain.backup

/**
 * Everything one backup carries: what wrote it, the preferences, and a dump of every table.
 *
 * The table dump is deliberately **schema-agnostic** -- table names come from `sqlite_master` and
 * column names from the cursor, so nothing here knows what a transaction is. Adding a column or a
 * whole table needs no change to any of this. The alternative, a serializer per entity, has a
 * failure mode this design does not: someone adds a field, forgets the exporter, and backups quietly
 * stop carrying it.
 */
data class BackupDocument(
    val meta: BackupMeta,
    val prefs: List<BackupPref>,
    val tables: List<BackupTable>
) {
    fun table(name: String): BackupTable? = tables.firstOrNull { it.name == name }

    fun rowCount(tableName: String): Int = table(tableName)?.rows?.size ?: 0
}

data class BackupMeta(
    /** Room's `version` when this was written. Restoring a newer one into an older app is refused. */
    val schemaVersion: Int,
    val appVersionCode: Int,
    val createdAtEpoch: Long,
    /**
     * Which install wrote this. Random per install, so a backup can be told apart from one another
     * device made -- which is what stops an upload from silently overwriting someone else's work.
     */
    val deviceId: String,
    /** Bumped on every successful upload, so "newer" is decidable without trusting clocks. */
    val revision: Long
)

/**
 * One preference.
 *
 * Preferences are **not** optional extras here. Three of this app's keys are correctness state: the
 * per-recipient parcel origin tokens behind `Transaction.sharedRefId`, and the three backfill
 * watermarks. Restore the database without them and the next share re-imports as duplicates on a
 * friend's device, while the backfills re-run and flip reviewed transactions back to pending.
 */
data class BackupPref(
    val key: String,
    val type: PrefType,
    /** The raw textual form; [PrefType] says how to read it back. */
    val value: String
)

enum class PrefType(val tag: String) {
    BOOLEAN("B"),
    LONG("L"),
    INT("I"),
    FLOAT("F"),
    STRING("S"),
    /** A `Set<String>`; members are joined with a delimiter the field escaping already protects. */
    STRING_SET("X");

    companion object {
        fun fromTag(tag: String): PrefType? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * One table, exactly as SQLite handed it over.
 *
 * Every column in this schema has INTEGER or TEXT affinity -- no REAL, no BLOB -- so a textual
 * encoding is lossless and there is no float rounding to reason about. A null cell is a null
 * [String?] here and is encoded distinctly from the empty string; see [BackupFormat].
 */
data class BackupTable(
    val name: String,
    val columns: List<String>,
    /** Each row is parallel to [columns]. A null element is SQL NULL. */
    val rows: List<List<String?>>
)

sealed interface BackupParseResult {
    data class Ok(val document: BackupDocument) : BackupParseResult
    data class Failed(val reason: String) : BackupParseResult
}
