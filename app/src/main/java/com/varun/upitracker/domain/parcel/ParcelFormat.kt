package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect

/**
 * The plaintext a parcel compresses down from, and the only place its grammar is written.
 *
 * Line-based and pipe-delimited rather than JSON, for the same reason
 * [com.varun.upitracker.domain.statistics.serialise] is: there is no serialization library in this
 * project, so the alternative is a hand-rolled JSON writer -- more code, for a format that would
 * then compress worse. Every line repeats its neighbours' shape, which is what DEFLATE is good at.
 *
 *     V|1|<originToken>|<txCount>
 *     T|<sourceId>|<dateEpoch>|<amountPaise>|<payer>|<payee>|<DEBT|NONE>|<upiRefId>|<reason>
 *     S|<PAYER|PAYEE>|<participant>|<amountPaise>
 *
 * `S` lines belong to the `T` line above them. Actors are `M` (the reader), `S` (the writer),
 * `F:<name>`, `C:<name>` for a shop, and `U:<label>` for an unclassified counterparty.
 */
object ParcelFormat {

    const val VERSION = 1

    private const val FIELD = '|'
    private const val ESCAPE = '\\'

    fun format(parcel: Parcel): String {
        val out = StringBuilder()
        out.append("V").append(FIELD).append(parcel.version)
            .append(FIELD).append(escape(parcel.originToken))
            .append(FIELD).append(parcel.transactions.size)
        parcel.transactions.forEach { tx ->
            out.append('\n')
            out.append("T").append(FIELD).append(tx.sourceId)
                .append(FIELD).append(tx.dateEpoch)
                .append(FIELD).append(tx.amountPaise)
                .append(FIELD).append(formatActor(tx.payer))
                .append(FIELD).append(formatActor(tx.payee))
                .append(FIELD).append(tx.ledgerEffect.name)
                .append(FIELD).append(escape(tx.upiRefId.orEmpty()))
                .append(FIELD).append(escape(tx.reason.orEmpty()))
            tx.shares.forEach { share ->
                out.append('\n')
                out.append("S").append(FIELD).append(share.side)
                    .append(FIELD).append(formatActor(share.participant))
                    .append(FIELD).append(share.amountPaise)
            }
        }
        return out.toString()
    }

    fun parse(plaintext: String): ParcelDecodeResult {
        val lines = plaintext.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParcelDecodeResult.Failed("The parcel is empty.")

        val header = splitFields(lines[0])
        if (header.size != 4 || header[0] != "V") {
            return ParcelDecodeResult.Failed("Line 1 is not a parcel header.")
        }
        val version = header[1].toIntOrNull()
            ?: return ParcelDecodeResult.Failed("Line 1 has an unreadable version.")
        if (version != VERSION) {
            return ParcelDecodeResult.Failed(
                "This parcel was made by a newer version of the app. Update, then try again."
            )
        }
        val originToken = unescape(header[2])
        if (originToken.isBlank()) return ParcelDecodeResult.Failed("Line 1 has no origin token.")
        val declaredCount = header[3].toIntOrNull()
            ?: return ParcelDecodeResult.Failed("Line 1 has an unreadable transaction count.")

        val transactions = mutableListOf<ParcelTransaction>()
        val shares = mutableListOf<ParcelShare>()

        fun attachSharesToLastTransaction() {
            if (transactions.isEmpty()) return
            val last = transactions.removeAt(transactions.size - 1)
            transactions.add(last.copy(shares = shares.toList()))
            shares.clear()
        }

        for (index in 1 until lines.size) {
            val lineNumber = index + 1
            val fields = splitFields(lines[index])
            when (fields.firstOrNull()) {
                "T" -> {
                    attachSharesToLastTransaction()
                    when (val row = parseTransaction(fields, lineNumber)) {
                        is Parsed.Failure -> return ParcelDecodeResult.Failed(row.reason)
                        is Parsed.Success -> transactions.add(row.value)
                    }
                }
                "S" -> {
                    if (transactions.isEmpty()) {
                        return ParcelDecodeResult.Failed(
                            "Line $lineNumber: a split with no transaction above it."
                        )
                    }
                    when (val row = parseShare(fields, lineNumber)) {
                        is Parsed.Failure -> return ParcelDecodeResult.Failed(row.reason)
                        is Parsed.Success -> shares.add(row.value)
                    }
                }
                else -> return ParcelDecodeResult.Failed(
                    "Line $lineNumber is not a kind of line this app knows."
                )
            }
        }
        attachSharesToLastTransaction()

        if (transactions.size != declaredCount) {
            return ParcelDecodeResult.Failed(
                "The parcel says it holds $declaredCount transactions but carries " +
                    "${transactions.size}. It was probably cut short somewhere."
            )
        }
        return ParcelDecodeResult.Ok(Parcel(version, originToken, transactions))
    }

    private fun parseTransaction(fields: List<String>, lineNumber: Int): Parsed<ParcelTransaction> {
        if (fields.size != 9) {
            return Parsed.Failure("Line $lineNumber: a transaction with the wrong number of fields.")
        }
        val sourceId = fields[1].toLongOrNull()?.takeIf { it > 0 }
            ?: return Parsed.Failure("Line $lineNumber: an unreadable transaction id.")
        val dateEpoch = fields[2].toLongOrNull()
            ?: return Parsed.Failure("Line $lineNumber: an unreadable date.")
        val amountPaise = fields[3].toLongOrNull()?.takeIf { it >= 0 }
            ?: return Parsed.Failure("Line $lineNumber: an unreadable amount.")
        val payer = parseActor(fields[4])
            ?: return Parsed.Failure("Line $lineNumber: an unreadable payer.")
        val payee = parseActor(fields[5])
            ?: return Parsed.Failure("Line $lineNumber: an unreadable payee.")
        // Both endpoints naming the same party describes nothing the ledger can post -- most
        // likely an account transfer, which is one person's private business either way.
        if (payer == payee) {
            return Parsed.Failure("Line $lineNumber: both sides of the transaction are the same person.")
        }
        val ledgerEffect = when (fields[6]) {
            LedgerEffect.DEBT.name -> LedgerEffect.DEBT
            LedgerEffect.NONE.name -> LedgerEffect.NONE
            else -> return Parsed.Failure("Line $lineNumber: an unreadable debt setting.")
        }
        return Parsed.Success(
            ParcelTransaction(
                sourceId = sourceId,
                dateEpoch = dateEpoch,
                amountPaise = amountPaise,
                payer = payer,
                payee = payee,
                ledgerEffect = ledgerEffect,
                upiRefId = unescape(fields[7]).ifBlank { null },
                reason = unescape(fields[8]).ifBlank { null },
                shares = emptyList()
            )
        )
    }

    private fun parseShare(fields: List<String>, lineNumber: Int): Parsed<ParcelShare> {
        if (fields.size != 4) {
            return Parsed.Failure("Line $lineNumber: a split with the wrong number of fields.")
        }
        val side = fields[1]
        if (side != "PAYER" && side != "PAYEE") {
            return Parsed.Failure("Line $lineNumber: a split on neither side of the transaction.")
        }
        val participant = parseActor(fields[2])
            ?: return Parsed.Failure("Line $lineNumber: an unreadable person in a split.")
        // A shop does not hold a share of a bill, and an unclassified counterparty cannot be
        // charged one. Allowing either would make the payer and payee sums meaningless.
        if (participant is ParcelActor.Shop || participant is ParcelActor.Unnamed) {
            return Parsed.Failure("Line $lineNumber: a split cannot be charged to a shop.")
        }
        val amountPaise = fields[3].toLongOrNull()?.takeIf { it >= 0 }
            ?: return Parsed.Failure("Line $lineNumber: an unreadable split amount.")
        return Parsed.Success(ParcelShare(side, participant, amountPaise))
    }

    private fun formatActor(actor: ParcelActor): String = when (actor) {
        ParcelActor.Me -> "M"
        ParcelActor.Sender -> "S"
        is ParcelActor.Person -> "F:" + escape(actor.name)
        is ParcelActor.Shop -> "C:" + escape(actor.name)
        is ParcelActor.Unnamed -> "U:" + escape(actor.label)
    }

    private fun parseActor(field: String): ParcelActor? = when {
        field == "M" -> ParcelActor.Me
        field == "S" -> ParcelActor.Sender
        field.startsWith("F:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Person)
        field.startsWith("C:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Shop)
        field.startsWith("U:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Unnamed)
        else -> null
    }

    /**
     * Splits on unescaped delimiters only, so a name holding a `|` survives. Written out rather
     * than done with [String.split] because that has no notion of an escape.
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

    private sealed interface Parsed<out T> {
        data class Success<T>(val value: T) : Parsed<T>
        data class Failure(val reason: String) : Parsed<Nothing>
    }
}
