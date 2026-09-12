package com.varun.upitracker.data.backup

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.backup.BackupTable
import com.varun.upitracker.domain.backup.LocalBackupState
import com.varun.upitracker.domain.backup.mapColumns

/** What a restore actually did, so the screen can report it rather than just claiming success. */
data class RestoreReport(
    val rowsByTable: Map<String, Int>,
    /** Tables the backup carried that this schema no longer has. */
    val unknownTables: List<String>,
    /** Columns the backup carried that their table no longer has, as `table.column`. */
    val droppedColumns: List<String>
) {
    val totalRows: Int get() = rowsByTable.values.sum()
}

class DumpException(message: String) : IllegalStateException(message)

/**
 * Reads and writes the whole database as a table dump.
 *
 * Schema-agnostic on purpose: tables come from `sqlite_master` and columns from the cursor, so
 * nothing here names a single entity and nothing here needs changing when the schema grows. The
 * alternative -- a serializer per entity -- fails silently the first time someone adds a column and
 * forgets to update it, which is the one failure mode a backup must not have.
 *
 * A restore is one database transaction. No file is swapped, nothing is closed and the process does
 * not restart, which matters in this app: `AppDatabase` keeps a singleton it never closes, and
 * `SmsReceiver` and the notification listener can open the database at any moment. Write-ahead
 * logging also means readers keep seeing the pre-transaction snapshot until it commits, so no screen
 * ever catches the database half-empty.
 */
class DatabaseDumpRepository(private val db: AppDatabase) {

    companion object {
        private const val TAG = "DatabaseDump"

        /**
         * Room's own bookkeeping and SQLite's. `sqlite_sequence` matters most: ten tables use
         * `INTEGER PRIMARY KEY AUTOINCREMENT`, and inserting explicit ids raises its high-water mark
         * on its own, so new rows carry on above the restored ones without it being touched.
         */
        private const val TABLE_QUERY = """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT LIKE 'room_%'
              AND name != 'android_metadata'
            ORDER BY name ASC
        """
    }

    private val database: SupportSQLiteDatabase
        get() = db.openHelper.writableDatabase

    fun tableNames(): List<String> = database.query(TABLE_QUERY).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun dump(): List<BackupTable> = tableNames().map { name ->
        database.query("SELECT * FROM `$name`").use { cursor ->
            val columns = (0 until cursor.columnCount).map { cursor.getColumnName(it) }
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { readCell(cursor, it, name, columns[it]) })
                }
            }
            BackupTable(name, columns, rows)
        }
    }

    /**
     * Every column in this schema has INTEGER or TEXT affinity, so text is a lossless encoding.
     *
     * The `else` branch is a tripwire rather than dead code: if a REAL or BLOB column is ever added,
     * this fails loudly at backup time instead of quietly writing a lossy or empty value into
     * somebody's only copy of their financial history.
     */
    private fun readCell(cursor: Cursor, index: Int, table: String, column: String): String? =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            else -> throw DumpException(
                "Cannot back up `$table`.`$column`: this app can only store whole numbers and text, " +
                    "and that column holds something else."
            )
        }

    /**
     * Room's schema version, read from the database rather than from a constant.
     *
     * Room keeps it in SQLite's own `user_version`, so this cannot drift out of step with the
     * `@Database(version = ...)` annotation the way a copied number would.
     */
    fun schemaVersion(): Int = database.query("PRAGMA user_version").use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun localState(): LocalBackupState = LocalBackupState(
        transactionCount = count("transactions"),
        accountCount = count("account"),
        friendCount = count("friends"),
        transferCount = count("account_transfer"),
        snapshotCount = count("balance_snapshot")
    )

    private fun count(table: String): Int =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /**
     * Replaces the contents of every table with [tables].
     *
     * `PRAGMA defer_foreign_keys = ON` is what makes table order irrelevant. It holds foreign-key
     * enforcement until COMMIT, which covers the awkward cases this schema has: the self-referencing
     * `transactions.refundsTransactionId`, and the `RESTRICT` keys pointing at `account`. SQLite
     * clears the pragma at the end of the transaction, so it has to be set inside one.
     *
     * A table present in the schema but absent from the backup is emptied, not left alone -- a
     * backup describes the whole database, not a patch to it.
     */
    fun restore(tables: List<BackupTable>): RestoreReport {
        val live = tableNames()
        val byName = tables.associateBy { it.name }
        val unknown = tables.map { it.name }.filterNot { it in live }
        val rowsByTable = mutableMapOf<String, Int>()
        val droppedColumns = mutableListOf<String>()

        db.runInTransaction {
            val sdb = database
            sdb.execSQL("PRAGMA defer_foreign_keys = ON")

            live.forEach { sdb.execSQL("DELETE FROM `$it`") }

            live.forEach { name ->
                val table = byName[name] ?: return@forEach
                val mapping = mapColumns(table.columns, columnsOf(sdb, name))
                mapping.dropped.forEach { droppedColumns.add("$name.$it") }
                if (mapping.isEmpty) return@forEach
                rowsByTable[name] = insertRows(sdb, name, table, mapping.kept)
            }
        }

        if (unknown.isNotEmpty()) Log.w(TAG, "Backup held unknown tables: $unknown")
        if (droppedColumns.isNotEmpty()) Log.w(TAG, "Backup held unknown columns: $droppedColumns")
        return RestoreReport(rowsByTable.toMap(), unknown, droppedColumns.toList())
    }

    /**
     * Inserts through one compiled statement rather than a string per row: a real database is
     * thousands of rows and recompiling the SQL for each is the difference between instant and slow.
     *
     * Values bind as text and SQLite applies the column's affinity, so a numeric string lands in an
     * INTEGER column as an integer. Which columns are named at all is decided by
     * [com.varun.upitracker.domain.backup.mapColumns], where the cross-version rules are written down
     * and tested.
     */
    private fun insertRows(
        sdb: SupportSQLiteDatabase,
        name: String,
        table: BackupTable,
        keep: List<String>
    ): Int {
        val indices = keep.map { table.columns.indexOf(it) }
        val sql = "INSERT INTO `$name` (" + keep.joinToString(", ") { "`$it`" } + ") " +
            "VALUES (" + keep.joinToString(", ") { "?" } + ")"

        sdb.compileStatement(sql).use { statement ->
            var inserted = 0
            table.rows.forEach { row ->
                statement.clearBindings()
                indices.forEachIndexed { position, sourceIndex ->
                    val value = row.getOrNull(sourceIndex)
                    if (value == null) statement.bindNull(position + 1) else statement.bindString(position + 1, value)
                }
                statement.executeInsert()
                inserted++
            }
            return inserted
        }
    }

    private fun columnsOf(sdb: SupportSQLiteDatabase, table: String): Set<String> =
        sdb.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex == -1) return emptySet()
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }
}
