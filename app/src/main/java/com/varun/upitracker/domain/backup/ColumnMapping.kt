package com.varun.upitracker.domain.backup

/** Which of a backed-up table's columns this schema can still take. */
data class ColumnMapping(
    val kept: List<String>,
    /** Columns the backup carries that the table no longer has. Reported, not silently swallowed. */
    val dropped: List<String>
) {
    val isEmpty: Boolean get() = kept.isEmpty()
}

/**
 * How a backup written against one schema is read into another.
 *
 * This is the whole of the version-compatibility story, and it is here rather than beside the SQL so
 * it can be tested:
 *  - a column the schema has **gained** since the backup is simply not named in the INSERT, so it
 *    takes its declared default. That is what lets a v14 backup restore into v15 with `sharedRefId`
 *    and `rawLabel` correctly null.
 *  - a column the schema has **dropped** is discarded and named in [ColumnMapping.dropped], so the
 *    restore can say what it ignored instead of failing or pretending.
 *
 * Order follows the backup, not the live table, because the caller reads values out of backup rows by
 * position.
 */
fun mapColumns(backupColumns: List<String>, liveColumns: Set<String>): ColumnMapping {
    val kept = mutableListOf<String>()
    val dropped = mutableListOf<String>()
    backupColumns.forEach { column ->
        if (column in liveColumns) kept.add(column) else dropped.add(column)
    }
    return ColumnMapping(kept, dropped)
}
