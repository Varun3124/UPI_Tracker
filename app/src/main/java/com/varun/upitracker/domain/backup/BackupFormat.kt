package com.varun.upitracker.domain.backup

/**
 * The plaintext a backup compresses down from, and the only place its grammar is written.
 *
 * Line-based and pipe-delimited, for the same reason
 * [com.varun.upitracker.domain.parcel.ParcelFormat] is: there is no serialization library in this
 * project, so the alternative is a hand-rolled JSON writer -- more code, for a format that would
 * then compress worse. A table dump is the most repetitive data imaginable, which is exactly what
 * DEFLATE is good at.
 *
 *     V|1|<schemaVersion>|<appVersionCode>|<createdAtEpoch>|<deviceId>|<revision>
 *     P|<key>|<B|L|I|F|S|X>|<value>
 *     T|<tableName>|<colCount>|<col1>|<col2>|...
 *     R|<v1>|<v2>|...
 *
 * `R` lines belong to the `T` line above them, and carry exactly as many values as that header
 * declared columns. Strict throughout: any malformed line rejects the whole document and names the
 * line. That is the opposite of
 * [com.varun.upitracker.domain.statistics.parseAccountScope], which falls back to a default because
 * a stale screen preference is not worth a crash. A backup is somebody's entire financial history.
 */
object BackupFormat {

    const val VERSION = 1

    private const val FIELD = '|'
    private const val ESCAPE = '\\'

    /**
     * SQL NULL, as it appears in a value field.
     *
     * NULL has to be distinguishable from the empty string -- a null `reason` and an empty one are
     * different rows, and conflating them is the one way a text encoding can quietly corrupt data.
     * [escape] can only ever emit `\\`, `\p`, `\n` or `\r`, so a bare `\0` is unambiguous: a literal
     * backslash-zero in the data escapes to `\\0`, which is a different field. Tested both ways.
     *
     * Checked against the **raw** field, before unescaping.
     */
    private const val NULL_MARKER = "\\0"

    /** Members of a `Set<String>` preference. Escaped like any other text, so a member may contain it. */
    const val SET_DELIMITER = ""

    fun format(document: BackupDocument): String {
        val out = StringBuilder()
        with(document.meta) {
            out.append("V").append(FIELD).append(VERSION)
                .append(FIELD).append(schemaVersion)
                .append(FIELD).append(appVersionCode)
                .append(FIELD).append(createdAtEpoch)
                .append(FIELD).append(escape(deviceId))
                .append(FIELD).append(revision)
        }

        document.prefs.forEach { pref ->
            out.append('\n')
            out.append("P").append(FIELD).append(escape(pref.key))
                .append(FIELD).append(pref.type.tag)
                .append(FIELD).append(escape(pref.value))
        }

        document.tables.forEach { table ->
            out.append('\n')
            out.append("T").append(FIELD).append(escape(table.name))
                .append(FIELD).append(table.columns.size)
            table.columns.forEach { out.append(FIELD).append(escape(it)) }
            table.rows.forEach { row ->
                out.append('\n').append("R")
                row.forEach { value ->
                    out.append(FIELD).append(if (value == null) NULL_MARKER else escape(value))
                }
            }
        }
        return out.toString()
    }

    fun parse(plaintext: String): BackupParseResult {
        // Kept, not filtered: a blank line is malformed and should say so, and dropping lines would
        // shift every line number in the error messages below.
        val lines = plaintext.split('\n')
        if (lines.isEmpty() || lines[0].isEmpty()) {
            return BackupParseResult.Failed("This does not look like a backup.")
        }

        val header = splitFields(lines[0])
        if (header.size != 7 || header[0] != "V") {
            return BackupParseResult.Failed("Line 1 is not a backup header.")
        }
        val version = header[1].toIntOrNull()
            ?: return BackupParseResult.Failed("Line 1 has an unreadable format version.")
        if (version != VERSION) {
            return BackupParseResult.Failed(
                "This backup was written by a newer version of the app. Update, then try again."
            )
        }
        val meta = BackupMeta(
            schemaVersion = header[2].toIntOrNull()
                ?: return BackupParseResult.Failed("Line 1 has an unreadable schema version."),
            appVersionCode = header[3].toIntOrNull()
                ?: return BackupParseResult.Failed("Line 1 has an unreadable app version."),
            createdAtEpoch = header[4].toLongOrNull()
                ?: return BackupParseResult.Failed("Line 1 has an unreadable timestamp."),
            deviceId = unescape(header[5]).ifBlank {
                return BackupParseResult.Failed("Line 1 names no device.")
            },
            revision = header[6].toLongOrNull()?.takeIf { it >= 0L }
                ?: return BackupParseResult.Failed("Line 1 has an unreadable revision.")
        )

        val prefs = mutableListOf<BackupPref>()
        val tables = mutableListOf<BackupTable>()
        var currentName: String? = null
        var currentColumns: List<String> = emptyList()
        var currentRows = mutableListOf<List<String?>>()

        fun closeTable() {
            val name = currentName ?: return
            tables.add(BackupTable(name, currentColumns, currentRows.toList()))
            currentName = null
            currentColumns = emptyList()
            currentRows = mutableListOf()
        }

        for (index in 1 until lines.size) {
            val lineNumber = index + 1
            val line = lines[index]
            // The document is built with '\n' separators and no trailing newline, but a file that
            // made a round trip through something line-oriented may have gained one.
            if (line.isEmpty() && index == lines.lastIndex) continue
            val fields = splitFields(line)
            when (fields.firstOrNull()) {
                "P" -> {
                    if (fields.size != 4) {
                        return BackupParseResult.Failed("Line $lineNumber: a setting with the wrong number of fields.")
                    }
                    val type = PrefType.fromTag(fields[2])
                        ?: return BackupParseResult.Failed("Line $lineNumber: an unknown setting type.")
                    prefs.add(BackupPref(unescape(fields[1]), type, unescape(fields[3])))
                }
                "T" -> {
                    closeTable()
                    if (fields.size < 3) {
                        return BackupParseResult.Failed("Line $lineNumber: a table header with too few fields.")
                    }
                    val declared = fields[2].toIntOrNull()?.takeIf { it >= 0 }
                        ?: return BackupParseResult.Failed("Line $lineNumber: an unreadable column count.")
                    val columns = fields.drop(3).map(::unescape)
                    if (columns.size != declared) {
                        return BackupParseResult.Failed(
                            "Line $lineNumber: a table header claiming $declared columns but listing ${columns.size}."
                        )
                    }
                    currentName = unescape(fields[1]).ifBlank {
                        return BackupParseResult.Failed("Line $lineNumber: a table with no name.")
                    }
                    currentColumns = columns
                }
                "R" -> {
                    if (currentName == null) {
                        return BackupParseResult.Failed("Line $lineNumber: a row with no table above it.")
                    }
                    val values = fields.drop(1)
                    if (values.size != currentColumns.size) {
                        return BackupParseResult.Failed(
                            "Line $lineNumber: a row of ${values.size} values in a table of " +
                                "${currentColumns.size} columns."
                        )
                    }
                    currentRows.add(values.map { if (it == NULL_MARKER) null else unescape(it) })
                }
                else -> return BackupParseResult.Failed(
                    "Line $lineNumber is not a kind of line this app knows."
                )
            }
        }
        closeTable()

        val duplicate = tables.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return BackupParseResult.Failed("The backup carries the table '${duplicate.key}' twice.")
        }

        return BackupParseResult.Ok(BackupDocument(meta, prefs.toList(), tables.toList()))
    }

    /**
     * Splits on unescaped delimiters only, so a value holding a `|` survives. Written out rather
     * than done with [String.split] because that has no notion of an escape.
     *
     * Identical in behaviour to [com.varun.upitracker.domain.parcel.ParcelFormat]'s, kept separate
     * so the two grammars can diverge without one silently changing the other.
     */
    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == ESCAPE && index + 1 < line.length -> {
                    current.append(character).append(line[index + 1])
                    index += 2
                }
                character == FIELD -> {
                    fields.add(current.toString())
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        fields.add(current.toString())
        return fields
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        value.forEach { character ->
            when (character) {
                ESCAPE -> out.append("\\\\")
                FIELD -> out.append("\\p")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /** One left-to-right pass, so an escaped backslash cannot be re-read as an escape. */
    private fun unescape(value: String): String {
        if (!value.contains(ESCAPE)) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == ESCAPE && index + 1 < value.length) {
                when (value[index + 1]) {
                    ESCAPE -> out.append(ESCAPE)
                    'p' -> out.append(FIELD)
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(value[index + 1])
                }
                index += 2
            } else {
                out.append(character)
                index++
            }
        }
        return out.toString()
    }
}
