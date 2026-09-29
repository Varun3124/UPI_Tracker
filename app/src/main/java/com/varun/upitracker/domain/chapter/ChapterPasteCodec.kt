package com.varun.upitracker.domain.chapter

import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.PasteCompression
import com.varun.upitracker.domain.parcel.PasteFraming
import java.util.Base64

/** What reading a pasted copy of a chapter found. */
sealed interface ChapterPasteResult {

    /** [body] is the snapshot as a copy keeps it, in `Chapter.snapshot`. */
    data class Ok(val snapshot: ChapterSnapshot, val body: String) : ChapterPasteResult

    data class Failed(val reason: String) : ChapterPasteResult
}

/**
 * A static copy of a chapter, pasted to a member who is not linked (docs/declarations-design.md S2):
 * `UPIC1.<crc32>.<payload>`, framed by [PasteFraming] like a parcel, the payload the body
 * [ChapterSnapshotFormat] writes, compressed by [PasteCompression].
 *
 * A paste has nothing that verifies who wrote it -- the reader says who sent it -- so a copy may
 * neither name anyone by account nor carry a hint about an agreement. Either would let whoever wrote
 * it speak for somebody else, so both are refused on the way in as well as on the way out.
 */
object ChapterPasteCodec {

    const val PREFIX = "UPIC1"

    private const val FAMILY = "UPIC"

    /** A snapshot body is capped far below this; see [ChapterSnapshotFormat]. */
    private const val MAX_INFLATED_BYTES = 256 * 1024

    private const val DAMAGED =
        "This copy is damaged, most likely cut short when it was copied. Ask for it to be sent again."

    fun encode(snapshot: ChapterSnapshot): String {
        require(isPastable(snapshot)) { "A pasted copy names nobody by account and carries no hint." }
        val body = Base64.getUrlDecoder().decode(ChapterSnapshotFormat.encode(snapshot))
        return PasteFraming.frame(PREFIX, PasteCompression.deflate(body))
    }

    /** Whether [text] is meant to be one of these, readable or not, so the paste box knows where to send it. */
    fun looksLikeCopy(text: String): Boolean = PasteFraming.prefixOf(text)?.startsWith(FAMILY) == true

    fun decode(text: String): ChapterPasteResult {
        val compressed = when (val framed = PasteFraming.unframe(text, PREFIX, FAMILY)) {
            is PasteFraming.Result.Ok -> framed.payload
            PasteFraming.Result.Empty -> return ChapterPasteResult.Failed("There is nothing here to import.")
            PasteFraming.Result.Foreign -> return ChapterPasteResult.Failed("That does not look like a copy of a chapter.")
            PasteFraming.Result.OtherVersion -> return ChapterPasteResult.Failed(
                "This copy was made by a newer version of the app. Update, then try again."
            )
            PasteFraming.Result.BadChecksum, PasteFraming.Result.Corrupted -> return ChapterPasteResult.Failed(DAMAGED)
        }
        val raw = PasteCompression.inflate(compressed, MAX_INFLATED_BYTES) ?: return ChapterPasteResult.Failed(DAMAGED)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val snapshot = ChapterSnapshotFormat.decode(body) ?: return ChapterPasteResult.Failed(DAMAGED)
        if (!isPastable(snapshot)) {
            return ChapterPasteResult.Failed(
                "This copy names people by their DhanMoney accounts, which only the mailbox can vouch for. " +
                    "Ask for it to be sent again."
            )
        }
        return ChapterPasteResult.Ok(snapshot, body)
    }

    /** Names nobody by account and says nothing about an agreement: what a paste can safely carry. */
    fun isPastable(snapshot: ChapterSnapshot): Boolean {
        if (snapshot.hint != null) return false
        val actors = snapshot.members.asSequence() +
            snapshot.nets.asSequence().map { it.party } +
            snapshot.plan.asSequence().flatMap { sequenceOf(it.debtor, it.creditor) } +
            snapshot.rows.asSequence().flatMap { row ->
                sequenceOf(row.payer, row.payee) + row.shares.asSequence().map { it.participant }
            }
        return actors.none { it is ParcelActor.Linked }
    }
}
