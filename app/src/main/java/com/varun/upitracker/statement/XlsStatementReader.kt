package com.varun.upitracker.statement

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class StatementParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads a legacy `.xls` (BIFF8) bank statement into [StatementRow]s.
 *
 * The only file in the project that touches Apache POI. Column positions are resolved from the
 * header text rather than hardcoded, so a bank reordering its export does not silently produce
 * garbage.
 */
object XlsStatementReader {

    private const val MAX_HEADER_SCAN_ROWS = 60
    private val DATE_FORMATS = listOf("dd/MM/yy", "dd/MM/yyyy", "dd-MM-yy", "dd-MM-yyyy")

    private data class Columns(
        val date: Int,
        val narration: Int,
        val refNo: Int,
        val withdrawal: Int,
        val deposit: Int
    )

    @Throws(StatementParseException::class)
    fun read(input: InputStream): List<StatementRow> {
        val workbook = try {
            HSSFWorkbook(input)
        } catch (error: Exception) {
            throw StatementParseException(
                "Could not open this file. Expected a legacy Excel (.xls) bank statement.",
                error
            )
        }

        return workbook.use { book ->
            val sheet = book.getSheetAt(0)
                ?: throw StatementParseException("The workbook has no sheets.")
            val headerRowIndex = findHeaderRow(sheet)
            val columns = readColumns(sheet.getRow(headerRowIndex))
            readRows(sheet, headerRowIndex, columns)
        }
    }

    private fun findHeaderRow(sheet: Sheet): Int {
        val limit = minOf(sheet.lastRowNum, MAX_HEADER_SCAN_ROWS)
        for (index in 0..limit) {
            val row = sheet.getRow(index) ?: continue
            val texts = row.map { cellText(it).lowercase(Locale.ROOT) }
            val hasNarration = texts.any { it.contains("narration") }
            val hasRef = texts.any { it.contains("chq") || it.contains("ref.no") }
            if (hasNarration && hasRef) return index
        }
        throw StatementParseException(
            "Could not find the statement header row (expected a Narration and a Chq./Ref.No. column)."
        )
    }

    private fun readColumns(header: Row): Columns {
        var date = -1
        var narration = -1
        var refNo = -1
        var withdrawal = -1
        var deposit = -1

        for (cell in header) {
            val text = cellText(cell).lowercase(Locale.ROOT).trim()
            when {
                text.startsWith("date") && date == -1 -> date = cell.columnIndex
                text.contains("narration") -> narration = cell.columnIndex
                text.contains("chq") || text.contains("ref.no") -> refNo = cell.columnIndex
                text.contains("withdrawal") -> withdrawal = cell.columnIndex
                text.contains("deposit") -> deposit = cell.columnIndex
            }
        }

        val missing = buildList {
            if (date == -1) add("Date")
            if (narration == -1) add("Narration")
            if (withdrawal == -1) add("Withdrawal Amt.")
            if (deposit == -1) add("Deposit Amt.")
        }
        if (missing.isNotEmpty()) {
            throw StatementParseException(
                "Statement is missing the " + missing.joinToString(", ") + " column(s)."
            )
        }
        return Columns(date, narration, refNo, withdrawal, deposit)
    }

    private fun readRows(sheet: Sheet, headerRowIndex: Int, columns: Columns): List<StatementRow> {
        val rows = mutableListOf<StatementRow>()
        // HDFC omits the date on continuation lines of a multi-line narration.
        var lastDateEpoch: Long? = null

        for (index in (headerRowIndex + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(index) ?: continue
            val narration = cellText(row.getCell(columns.narration)).trim()
            val dateText = cellText(row.getCell(columns.date)).trim()

            if (isEndOfStatement(row)) break
            // HDFC prints a row of asterisks immediately under the header and again before the
            // summary block, so these are skipped rather than treated as terminal.
            if (isSeparator(row, narration, dateText)) continue
            if (narration.isEmpty()) continue

            val dateEpoch = parseDate(row.getCell(columns.date), dateText)
                ?: lastDateEpoch
                ?: continue
            lastDateEpoch = dateEpoch

            val withdrawal = cellAmountPaise(row.getCell(columns.withdrawal))
            val deposit = cellAmountPaise(row.getCell(columns.deposit))
            // A row that moved no money is a repeated header or a note, not a transaction.
            if (withdrawal == 0L && deposit == 0L) continue

            val refNoCell = if (columns.refNo >= 0) {
                cellText(row.getCell(columns.refNo)).trim()
            } else {
                null
            }
            val parsed = NarrationParser.parse(narration, refNoCell)

            rows += StatementRow(
                dateEpoch = dateEpoch,
                narration = narration,
                statementRefNo = NarrationParser.normaliseRefNo(refNoCell),
                amountPaise = if (withdrawal > 0L) withdrawal else deposit,
                direction = if (withdrawal > 0L) "DEBIT" else "CREDIT",
                upiRefId = parsed.upiRefId,
                rawName = parsed.rawName,
                upiId = parsed.upiId,
                notes = parsed.notes
            )
        }

        if (rows.isEmpty()) {
            throw StatementParseException("No transaction rows found in this statement.")
        }
        return rows
    }

    private fun isEndOfStatement(row: Row): Boolean = row.any {
        val text = cellText(it)
        text.contains("STATEMENT SUMMARY", ignoreCase = true) ||
            text.contains("End Of Statement", ignoreCase = true)
    }

    private fun isSeparator(row: Row, narration: String, dateText: String): Boolean {
        if (narration.startsWith("***") || dateText.startsWith("***")) return true
        val firstText = row.firstOrNull { cellText(it).isNotBlank() }
            ?.let { cellText(it).trim() }
            ?: return false
        return firstText.startsWith("***")
    }

    /** Start of day, local zone — statement dates carry no time. */
    private fun parseDate(cell: Cell?, text: String): Long? {
        if (cell != null && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return startOfDay(cell.dateCellValue.time)
        }
        if (text.isEmpty()) return null
        // Cells sometimes carry a stray tab; keep only the date-shaped prefix.
        val candidate = text.substringBefore('\t').trim()
        for (pattern in DATE_FORMATS) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.ENGLISH)
                    .apply { isLenient = false }
                    .parse(candidate) ?: continue
                return startOfDay(parsed.time)
            } catch (_: java.text.ParseException) {
                // Try the next pattern.
            }
        }
        return null
    }

    private fun startOfDay(epochMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun cellAmountPaise(cell: Cell?): Long {
        val rupees = when (cell?.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.trim().replace(",", "").toDoubleOrNull() ?: 0.0
            CellType.FORMULA -> runCatching { cell.numericCellValue }.getOrDefault(0.0)
            else -> 0.0
        }
        return rupeesToPaise(rupees)
    }

    private fun cellText(cell: Cell?): String = when (cell?.cellType) {
        CellType.STRING -> cell.stringCellValue
        CellType.NUMERIC ->
            if (DateUtil.isCellDateFormatted(cell)) {
                SimpleDateFormat("dd/MM/yy", Locale.ENGLISH).format(cell.dateCellValue)
            } else {
                val value = cell.numericCellValue
                if (value == value.toLong().toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        CellType.FORMULA -> runCatching { cell.stringCellValue }.getOrDefault("")
        else -> ""
    }
}
