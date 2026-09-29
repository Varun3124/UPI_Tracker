package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.domain.mailbox.MailboxIds

/**
 * The plaintext a parcel compresses down from, and the only place its grammar is written.
 *
 * Line-based and pipe-delimited rather than JSON, for the same reason
 * [com.varun.upitracker.domain.statistics.serialise] is: there is no serialization library in this
 * project, so the alternative is a hand-rolled JSON writer -- more code, for a format that would
 * then compress worse. Every line repeats its neighbours' shape, which is what DEFLATE is good at.
 *
 * Version 3 is pasted through a chat app:
 *
 *     V|3|<originToken>|<txCount>
 *     T|<sourceId>|<dateEpoch>|<amountPaise>|<payer>|<payee>|<DEBT|NONE>|<upiRefId>|<reason>|<PAYERS|PAYEES>
 *     S|<PAYER|PAYEE>|<participant>|<amountPaise>|<keptLegs>
 *
 * Version 1 is version 3 without the last field of either line, as apps from before [IouRecovery]
 * wrote it. It is still read, so a friend who has not updated can still paste to one who has, but
 * never written.
 *
 * Version 2 only ever travels inside a mailbox envelope:
 *
 *     V|2|<txCount>
 *     T|<shareRef>|<dateEpoch>|<amountPaise>|<payer>|<payee>|<DEBT|NONE>|<upiRefId>|<reason>|<legacyRef>|<PAYERS|PAYEES>
 *     S|<PAYER|PAYEE>|<participant>|<amountPaise>|<keptLegs>
 *
 * `S` lines belong to the `T` line above them. Actors are `M` (the reader), `S` (the writer),
 * `F:<name>`, `C:<name>` for a shop, and `U:<label>` for an unclassified counterparty. Version 2 adds
 * `L:<uid>:<name>`, a person named by the account they linked with. `PAYERS` and `PAYEES` are
 * [IouRecovery.FROM_SECONDARY_PAYERS] and [IouRecovery.FROM_SECONDARY_PAYEES]. `<keptLegs>` is two
 * digits, `1` kept and `0` left out: the IOU where the payee owes this person, then the one where
 * this person owes the payer.
 */
object ParcelFormat {

    /** Pasted through a chat app. */
    const val VERSION = 3

    /** Pasted by an app from before [IouRecovery]. Read, never written. */
    const val LEGACY_VERSION = 1

    /** Inside a mailbox envelope only, whose verified sender is what says who wrote it. */
    const val MAILBOX_VERSION = 2

    private const val FIELD = '|'
    private const val ESCAPE = '\\'

    private const val RECOVER_FROM_PAYERS = "PAYERS"
    private const val RECOVER_FROM_PAYEES = "PAYEES"

    /**
     * A share reference, or the version 1 reference a row would have had. Held to characters
     * nothing downstream has to escape, since both end up in a unique database index.
     */
    private val REF = Regex("[A-Za-z0-9._-]{1,80}")

    private val KEPT_LEGS = Regex("[01]{2}")

    fun format(parcel: Parcel): String {
        val mailbox = when (parcel.version) {
            VERSION -> false
            MAILBOX_VERSION -> true
            else -> throw IllegalArgumentException("There is no parcel version ${parcel.version} to write.")
        }
        val out = StringBuilder()
        out.append("V").append(FIELD).append(parcel.version)
        if (!mailbox) {
            val token = requireNotNull(parcel.originToken) { "A pasted parcel needs an origin token." }
            out.append(FIELD).append(escape(token))
        }
        out.append(FIELD).append(parcel.transactions.size)
        parcel.transactions.forEach { tx ->
            out.append('\n')
            out.append("T").append(FIELD)
            if (mailbox) out.append(requireRef(tx.shareRef, "share reference")) else out.append(tx.sourceId)
            out.append(FIELD).append(tx.dateEpoch)
                .append(FIELD).append(tx.amountPaise)
                .append(FIELD).append(formatActor(tx.payer, mailbox))
                .append(FIELD).append(formatActor(tx.payee, mailbox))
                .append(FIELD).append(tx.ledgerEffect.name)
                .append(FIELD).append(escape(tx.upiRefId.orEmpty()))
                .append(FIELD).append(escape(tx.reason.orEmpty()))
            if (mailbox) {
                out.append(FIELD).append(tx.legacyRef?.let { requireRef(it, "earlier reference") }.orEmpty())
            }
            out.append(FIELD).append(formatRecovery(tx.iouRecovery))
            tx.shares.forEach { share ->
                out.append('\n')
                out.append("S").append(FIELD).append(share.side)
                    .append(FIELD).append(formatActor(share.participant, mailbox))
                    .append(FIELD).append(share.amountPaise)
                    .append(FIELD).append(if (share.keepPayeeLeg) '1' else '0')
                    .append(if (share.keepPayerLeg) '1' else '0')
            }
        }
        return out.toString()
    }

    /**
     * Reads a parcel of exactly [expectedVersion] -- the version of the route it arrived by. A pasted
     * parcel is version 3, or version 1 from an older app, and a mailbox envelope carries version 2: a
     * version 2 body smuggled into a pasted parcel would name accounts that nothing has verified.
     */
    fun parse(plaintext: String, expectedVersion: Int = VERSION): ParcelDecodeResult {
        val lines = plaintext.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParcelDecodeResult.Failed("The parcel is empty.")

        val header = splitFields(lines[0])
        if (header.size < 3 || header[0] != "V") {
            return ParcelDecodeResult.Failed("Line 1 is not a parcel header.")
        }
        val version = header[1].toIntOrNull()
            ?: return ParcelDecodeResult.Failed("Line 1 has an unreadable version.")
        if (version > expectedVersion) {
            return ParcelDecodeResult.Failed(
                "This parcel was made by a newer version of the app. Update, then try again."
            )
        }
        val legacy = expectedVersion == VERSION && version == LEGACY_VERSION
        if (version != expectedVersion && !legacy) {
            return ParcelDecodeResult.Failed("This parcel is not in a form this screen can read.")
        }
        val mailbox = version == MAILBOX_VERSION
        if (header.size != (if (mailbox) 3 else 4)) {
            return ParcelDecodeResult.Failed("Line 1 is not a parcel header.")
        }
        val originToken = if (mailbox) null else unescape(header[2])
        if (originToken != null && originToken.isBlank()) {
            return ParcelDecodeResult.Failed("Line 1 has no origin token.")
        }
        val declaredCount = header.last().toIntOrNull()
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
                    when (val row = parseTransaction(fields, lineNumber, mailbox, legacy)) {
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
                    when (val row = parseShare(fields, lineNumber, mailbox, legacy)) {
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

    private fun parseTransaction(
        fields: List<String>,
        lineNumber: Int,
        mailbox: Boolean,
        legacy: Boolean
    ): Parsed<ParcelTransaction> {
        val expectedFields = when {
            mailbox -> 11
            legacy -> 9
            else -> 10
        }
        if (fields.size != expectedFields) {
            return Parsed.Failure("Line $lineNumber: a transaction with the wrong number of fields.")
        }
        val sourceId: Long
        val shareRef: String?
        if (mailbox) {
            sourceId = 0L
            shareRef = fields[1].takeIf { REF.matches(it) }
                ?: return Parsed.Failure("Line $lineNumber: an unreadable reference.")
        } else {
            sourceId = fields[1].toLongOrNull()?.takeIf { it > 0 }
                ?: return Parsed.Failure("Line $lineNumber: an unreadable transaction id.")
            shareRef = null
        }
        val dateEpoch = fields[2].toLongOrNull()
            ?: return Parsed.Failure("Line $lineNumber: an unreadable date.")
        val amountPaise = fields[3].toLongOrNull()?.takeIf { it >= 0 }
            ?: return Parsed.Failure("Line $lineNumber: an unreadable amount.")
        val payer = parseActor(fields[4], mailbox)
            ?: return Parsed.Failure("Line $lineNumber: an unreadable payer.")
        val payee = parseActor(fields[5], mailbox)
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
        val legacyRef = if (mailbox && fields[9].isNotEmpty()) {
            fields[9].takeIf { REF.matches(it) }
                ?: return Parsed.Failure("Line $lineNumber: an unreadable earlier reference.")
        } else {
            null
        }
        val iouRecovery = if (legacy) {
            null
        } else {
            parseRecovery(fields.last())
                ?: return Parsed.Failure("Line $lineNumber: an unreadable pay-back setting.")
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
                shares = emptyList(),
                shareRef = shareRef,
                legacyRef = legacyRef,
                iouRecovery = iouRecovery
            )
        )
    }

    private fun parseShare(
        fields: List<String>,
        lineNumber: Int,
        mailbox: Boolean,
        legacy: Boolean
    ): Parsed<ParcelShare> {
        if (fields.size != (if (legacy) 4 else 5)) {
            return Parsed.Failure("Line $lineNumber: a split with the wrong number of fields.")
        }
        val side = fields[1]
        if (side != "PAYER" && side != "PAYEE") {
            return Parsed.Failure("Line $lineNumber: a split on neither side of the transaction.")
        }
        val participant = parseActor(fields[2], mailbox)
            ?: return Parsed.Failure("Line $lineNumber: an unreadable person in a split.")
        // A shop does not hold a share of a bill, and an unclassified counterparty cannot be
        // charged one. Allowing either would make the payer and payee sums meaningless.
        if (participant is ParcelActor.Shop || participant is ParcelActor.Unnamed) {
            return Parsed.Failure("Line $lineNumber: a split cannot be charged to a shop.")
        }
        val amountPaise = fields[3].toLongOrNull()?.takeIf { it >= 0 }
            ?: return Parsed.Failure("Line $lineNumber: an unreadable split amount.")
        if (legacy) return Parsed.Success(ParcelShare(side, participant, amountPaise))

        val keptLegs = fields[4]
        if (!KEPT_LEGS.matches(keptLegs)) {
            return Parsed.Failure("Line $lineNumber: a split that does not say which debts it keeps.")
        }
        return Parsed.Success(
            ParcelShare(
                side = side,
                participant = participant,
                amountPaise = amountPaise,
                keepPayeeLeg = keptLegs[0] == '1',
                keepPayerLeg = keptLegs[1] == '1'
            )
        )
    }

    private fun requireRef(ref: String?, what: String): String {
        require(ref != null && REF.matches(ref)) { "A version 2 row needs a well-formed $what, not '$ref'." }
        return ref
    }

    private fun formatRecovery(recovery: IouRecovery?): String =
        when (requireNotNull(recovery) { "A row has to say which side of its split pays back." }) {
            IouRecovery.FROM_SECONDARY_PAYERS -> RECOVER_FROM_PAYERS
            IouRecovery.FROM_SECONDARY_PAYEES -> RECOVER_FROM_PAYEES
        }

    private fun parseRecovery(field: String): IouRecovery? = when (field) {
        RECOVER_FROM_PAYERS -> IouRecovery.FROM_SECONDARY_PAYERS
        RECOVER_FROM_PAYEES -> IouRecovery.FROM_SECONDARY_PAYEES
        else -> null
    }

    /** One actor in the grammar above. Shared with the chapter snapshot, which names people the same way. */
    internal fun formatActor(actor: ParcelActor, mailbox: Boolean): String = when (actor) {
        ParcelActor.Me -> "M"
        ParcelActor.Sender -> "S"
        is ParcelActor.Person -> "F:" + escape(actor.name)
        is ParcelActor.Shop -> "C:" + escape(actor.name)
        is ParcelActor.Unnamed -> "U:" + escape(actor.label)
        is ParcelActor.Linked -> {
            require(mailbox) { "Only a mailbox parcel can name an account." }
            require(MailboxIds.isUid(actor.uid)) { "'${actor.uid}' is not an account id." }
            "L:" + actor.uid + ":" + escape(actor.name)
        }
    }

    /** The inverse of [formatActor], or null for anything that is not exactly one actor. */
    internal fun parseActor(field: String, mailbox: Boolean): ParcelActor? = when {
        field == "M" -> ParcelActor.Me
        field == "S" -> ParcelActor.Sender
        field.startsWith("F:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Person)
        field.startsWith("C:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Shop)
        field.startsWith("U:") -> unescape(field.substring(2)).ifBlank { null }?.let(ParcelActor::Unnamed)
        // Only a mailbox parcel may name an account: a pasted one has nothing verifying who wrote it.
        mailbox && field.startsWith("L:") -> parseLinked(field.substring(2))
        else -> null
    }

    /** `<uid>:<name>`. A uid never holds a colon, so the first one ends it and the name may hold more. */
    private fun parseLinked(body: String): ParcelActor.Linked? {
        val colon = body.indexOf(':')
        if (colon <= 0) return null
        val uid = body.substring(0, colon).takeIf(MailboxIds::isUid) ?: return null
        val name = unescape(body.substring(colon + 1)).ifBlank { null } ?: return null
        return ParcelActor.Linked(uid, name)
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
