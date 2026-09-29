package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelDecodeResult
import com.varun.upitracker.domain.parcel.ParcelFormat
import com.varun.upitracker.domain.parcel.Parcel
import com.varun.upitracker.domain.parcel.ParcelTransaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** One payment in a shared chapter's plan, from the reader's seat: [debtor] pays [creditor]. */
data class SnapshotPayment(val debtor: ParcelActor, val creditor: ParcelActor, val amountPaise: Long)

/** Someone's net in a shared chapter, from the reader's seat. Positive means they are owed. */
data class SnapshotNet(val party: ParcelActor, val amountPaise: Long)

/** What a claim can match one snapshot row by, beside the references the row itself carries (S5). */
data class SnapshotRowExtra(
    /** Awaiting review on the owner's phone: it counts for nothing in the plan yet, so it is not claimed. */
    val pending: Boolean,
    /** The owner's copy's `sharedRefId`, when the owner received the row from someone: whose it originally was. */
    val sourceRef: String?
)

/**
 * The owner's part for this chapter in the owner's checkpoint with the reader, from the reader's seat
 * (S8). Keeps what the two agreed about this chapter the same on both phones.
 */
data class SnapshotHint(val declarationId: String, val amountPaise: Long)

/**
 * A shared chapter as its owner sends it to one member: written from that member's seat, the way a
 * parcel is, with the owner as [ParcelActor.Sender] and the member as [ParcelActor.Me].
 *
 * The member never works the plan out again. [plan] is the owner's, and it is what the member's
 * balances come from -- `ChapterMath` breaks ties in favour of whoever runs it, so two phones computing
 * the same chapter would not always agree. See docs/declarations-design.md S3.
 */
data class ChapterSnapshot(
    val shareId: String,
    val version: Long,
    val state: ChapterState,
    val name: String,
    val notes: String?,
    val sentEpoch: Long,
    /** Everyone in it, the owner and the reader included. */
    val members: List<ParcelActor>,
    val nets: List<SnapshotNet>,
    val plan: List<SnapshotPayment>,
    val hint: SnapshotHint?,
    /** Its rows, most relevant first: the reader's own before anyone else's. */
    val rows: List<ParcelTransaction>,
    /** One per row, in the same order. */
    val extras: List<SnapshotRowExtra>,
    /** Rows left out to keep the message small. The plan still counts them. */
    val omittedRows: Int
)

/** Why a copy stopped being a live one. */
enum class ChapterEnd {
    /** The owner stopped sharing it. Copies freeze, and keep counting. */
    UNSHARED,

    /** The owner deleted it. Copies go too, and their claimed rows return to the direct ledger. */
    DELETED
}

/**
 * The bodies of `CHAPTER_SNAPSHOT` and `CHAPTER_ENDED`.
 *
 * `java.io.Data*Stream` and base64url like the other message bodies, with the rows as an embedded
 * [ParcelFormat] version 2 body -- the same grammar a mailbox parcel uses, so a row reads the same way
 * however it arrived. Strict: a snapshot moves balances, so anything malformed is refused whole.
 */
object ChapterSnapshotFormat {

    const val MAX_NAME_CHARS = 120
    const val MAX_NOTES_CHARS = 1_000

    private const val SNAPSHOT_MAGIC = "DHC1"
    private const val ENDED_MAGIC = "DHE1"

    /** Plenty for any group that shares a trip; only bounds what a hostile sender can make us allocate. */
    private const val MAX_PEOPLE = 200
    private const val MAX_ROWS_BYTES = 180 * 1024

    /** `mbx:<uid>:<ref>` or `<token>.<id>`: what a `sharedRefId` looks like. */
    private val SOURCE_REF = Regex("[A-Za-z0-9:._-]{1,200}")

    fun encode(snapshot: ChapterSnapshot): String {
        require(snapshot.extras.size == snapshot.rows.size) { "One extra per row." }
        val rowsBody = ParcelFormat.format(Parcel(ParcelFormat.MAILBOX_VERSION, null, snapshot.rows))
            .toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(rowsBody.size + 1024)
        DataOutputStream(out).use { data ->
            data.writeUTF(SNAPSHOT_MAGIC)
            data.writeUTF(snapshot.shareId)
            data.writeLong(snapshot.version)
            data.writeUTF(snapshot.state.name)
            data.writeUTF(snapshot.name)
            data.writeUTF(snapshot.notes.orEmpty())
            data.writeLong(snapshot.sentEpoch)
            data.writeInt(snapshot.members.size)
            snapshot.members.forEach { data.writeUTF(actor(it)) }
            data.writeInt(snapshot.nets.size)
            snapshot.nets.forEach {
                data.writeUTF(actor(it.party))
                data.writeLong(it.amountPaise)
            }
            data.writeInt(snapshot.plan.size)
            snapshot.plan.forEach {
                data.writeUTF(actor(it.debtor))
                data.writeUTF(actor(it.creditor))
                data.writeLong(it.amountPaise)
            }
            data.writeBoolean(snapshot.hint != null)
            snapshot.hint?.let {
                data.writeUTF(it.declarationId)
                data.writeLong(it.amountPaise)
            }
            data.writeInt(rowsBody.size)
            data.write(rowsBody)
            snapshot.extras.forEach {
                data.writeBoolean(it.pending)
                data.writeUTF(it.sourceRef.orEmpty())
            }
            data.writeInt(snapshot.omittedRows)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /**
     * Null for anything that is not exactly what [encode] writes, or that names the sender or the
     * reader by account as well as by role -- a second copy of either would count their share twice.
     */
    fun decode(body: String, senderUid: String? = null, readerUid: String? = null): ChapterSnapshot? = try {
        DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(body))).use { data ->
            if (data.readUTF() != SNAPSHOT_MAGIC) return null
            val shareId = data.readUTF()
            val version = data.readLong()
            val stateName = data.readUTF()
            val name = data.readUTF()
            val notes = data.readUTF().ifEmpty { null }
            val sentEpoch = data.readLong()
            val members = List(count(data) ?: return null) { parseActor(data.readUTF()) ?: return null }
            val nets = List(count(data) ?: return null) {
                SnapshotNet(parseActor(data.readUTF()) ?: return null, data.readLong())
            }
            val plan = List(count(data) ?: return null) {
                SnapshotPayment(parseActor(data.readUTF()) ?: return null, parseActor(data.readUTF()) ?: return null, data.readLong())
            }
            val hint = if (data.readBoolean()) SnapshotHint(data.readUTF(), data.readLong()) else null
            val rowsSize = data.readInt()
            if (rowsSize < 0 || rowsSize > MAX_ROWS_BYTES) return null
            val rowsBody = ByteArray(rowsSize).also(data::readFully)
            val rows = (ParcelFormat.parse(String(rowsBody, Charsets.UTF_8), ParcelFormat.MAILBOX_VERSION) as? ParcelDecodeResult.Ok)
                ?.parcel ?: return null
            val extras = List(rows.transactions.size) {
                SnapshotRowExtra(data.readBoolean(), data.readUTF().ifEmpty { null })
            }
            val omitted = data.readInt()
            if (data.read() != -1) return null

            val state = ChapterState.entries.firstOrNull { it.name == stateName } ?: return null
            val snapshot = ChapterSnapshot(
                shareId, version, state, name, notes, sentEpoch, members, nets, plan, hint,
                rows.transactions, extras, omitted
            )
            snapshot.takeIf { isWellFormed(it, rows, senderUid, readerUid) }
        }
    } catch (error: IOException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    fun encodeEnded(shareId: String, end: ChapterEnd): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(ENDED_MAGIC)
            data.writeUTF(shareId)
            data.writeUTF(end.name)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decodeEnded(body: String): Pair<String, ChapterEnd>? = try {
        DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(body))).use { data ->
            if (data.readUTF() != ENDED_MAGIC) return null
            val shareId = data.readUTF()
            val endName = data.readUTF()
            if (data.read() != -1) return null
            val end = ChapterEnd.entries.firstOrNull { it.name == endName } ?: return null
            if (!MailboxIds.isRandomId(shareId)) return null
            shareId to end
        }
    } catch (error: IOException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    private fun isWellFormed(s: ChapterSnapshot, rows: Parcel, senderUid: String?, readerUid: String?): Boolean {
        if (!MailboxIds.isRandomId(s.shareId) || s.version < 0L) return false
        if (s.name.isBlank() || s.name.length > MAX_NAME_CHARS) return false
        if (s.notes != null && s.notes.length > MAX_NOTES_CHARS) return false
        if (s.omittedRows < 0) return false
        if (ParcelActor.Sender !in s.members || ParcelActor.Me !in s.members) return false
        val people = s.members + s.nets.map { it.party } + s.plan.flatMap { listOf(it.debtor, it.creditor) }
        // Only people hold a share of a group's debts; a shop or an unclassified label cannot.
        if (people.any { it !is ParcelActor.Me && it !is ParcelActor.Sender && it !is ParcelActor.Person && it !is ParcelActor.Linked }) {
            return false
        }
        // Everyone the plan and nets name has to be a member, or a payment could land on a stranger.
        val members = s.members.toSet()
        if (people.any { it !in members }) return false
        if (s.plan.any { it.amountPaise <= 0L || it.debtor == it.creditor }) return false
        if (s.nets.any { it.amountPaise == 0L }) return false
        if (s.nets.sumOf { it.amountPaise } != 0L) return false
        if (s.hint != null && !MailboxIds.isRandomId(s.hint.declarationId)) return false
        if (s.extras.any { extra -> extra.sourceRef != null && !SOURCE_REF.matches(extra.sourceRef) }) return false
        if (senderUid != null && readerUid != null) {
            val linkedUids = people.filterIsInstance<ParcelActor.Linked>().map { it.uid }
            if (senderUid in linkedUids || readerUid in linkedUids) return false
            if (!com.varun.upitracker.domain.parcel.ParcelPerspective.linkedUidsAreThirdParties(rows, senderUid, readerUid)) {
                return false
            }
        }
        return true
    }

    private fun count(data: DataInputStream): Int? = data.readInt().takeIf { it in 0..MAX_PEOPLE * 4 }

    private fun actor(actor: ParcelActor): String = ParcelFormat.formatActor(actor, mailbox = true)

    private fun parseActor(field: String): ParcelActor? = ParcelFormat.parseActor(field, mailbox = true)
}
