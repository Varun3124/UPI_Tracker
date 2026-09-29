package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.mailbox.MailboxIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** A shared chapter's share that a proposal counted in its amount, named by the chapter's share id. */
data class ProposalPart(val shareId: String, val amountPaise: Long)

/**
 * A proposal as it travels between the two phones.
 *
 * Written from the **reader's** seat, the way a parcel is: the sender has already negated every
 * amount, so the reader stores what arrives as it is. See docs/declarations-design.md section 7.
 */
data class DeclarationProposal(
    val id: String,
    /** One of [DeclarationKind]. */
    val kind: String,
    val targetId: String?,
    val asOfEpoch: Long?,
    val amountPaise: Long?,
    val deltaPaise: Long?,
    val proposedEpoch: Long,
    val auto: Boolean,
    val note: String?,
    /** DECLARE only: the shared chapters its amount counted (D6). Private ones are never named. */
    val parts: List<ProposalPart>
)

enum class DeclarationVerdict { ACCEPT, DENY, WITHDRAW }

/** An answer to a proposal. ACCEPT and DENY come from its recipient, WITHDRAW from its proposer. */
data class DeclarationAnswer(
    val proposalId: String,
    val verdict: DeclarationVerdict,
    val note: String? = null
)

/**
 * The bodies of `DECLARATION_PROPOSAL` and `DECLARATION_ANSWER`.
 *
 * `java.io.Data*Stream` and base64url, the same as [com.varun.upitracker.domain.mailbox.LinkMessages]:
 * every field is length-prefixed, and decoding is strict -- a proposal is money, so anything that is
 * not exactly what [encodeProposal] writes is refused whole.
 */
object DeclarationMessages {

    const val MAX_NOTE_CHARS = 280

    private const val PROPOSAL_MAGIC = "DHD1"
    private const val ANSWER_MAGIC = "DHA1"

    /** A proposal names one part per shared chapter; far more than any real friendship holds. */
    private const val MAX_PARTS = 200

    /**
     * [row], one of this phone's own proposals, as the other side should read it: every amount
     * negated. [sharedParts] are the parts of it that name a shared chapter, from this phone's seat.
     */
    fun outgoing(row: IouDeclaration, sharedParts: List<ProposalPart>): DeclarationProposal {
        require(row.proposedByMe) { "Only this phone's own proposals are sent." }
        val kind = requireNotNull(row.kind) { "A stub is never sent." }
        return DeclarationProposal(
            id = row.id,
            kind = kind,
            targetId = row.targetId,
            asOfEpoch = row.asOfEpoch,
            amountPaise = row.amountPaise?.let { -it },
            deltaPaise = row.deltaPaise?.let { -it },
            proposedEpoch = row.proposedEpoch,
            auto = row.auto,
            note = row.note,
            parts = if (kind == DeclarationKind.DECLARE) sharedParts.map { ProposalPart(it.shareId, -it.amountPaise) } else emptyList()
        )
    }

    /** The trimmed note, or null when there is nothing worth sending. */
    fun cleanNote(note: String?): String? = note?.trim()?.take(MAX_NOTE_CHARS)?.ifEmpty { null }

    fun encodeProposal(proposal: DeclarationProposal): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(PROPOSAL_MAGIC)
            data.writeUTF(proposal.id)
            data.writeUTF(proposal.kind)
            data.writeUTF(proposal.targetId.orEmpty())
            writeOptionalLong(data, proposal.asOfEpoch)
            writeOptionalLong(data, proposal.amountPaise)
            writeOptionalLong(data, proposal.deltaPaise)
            data.writeLong(proposal.proposedEpoch)
            data.writeBoolean(proposal.auto)
            data.writeUTF(proposal.note.orEmpty())
            data.writeInt(proposal.parts.size)
            proposal.parts.forEach { part ->
                data.writeUTF(part.shareId)
                data.writeLong(part.amountPaise)
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decodeProposal(body: String): DeclarationProposal? = try {
        DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(body))).use { data ->
            if (data.readUTF() != PROPOSAL_MAGIC) return null
            val id = data.readUTF()
            val kind = data.readUTF()
            val targetId = data.readUTF().ifEmpty { null }
            val asOfEpoch = readOptionalLong(data)
            val amountPaise = readOptionalLong(data)
            val deltaPaise = readOptionalLong(data)
            val proposedEpoch = data.readLong()
            val auto = data.readBoolean()
            val note = data.readUTF().ifEmpty { null }
            val partCount = data.readInt()
            if (partCount < 0 || partCount > MAX_PARTS) return null
            val parts = List(partCount) { ProposalPart(data.readUTF(), data.readLong()) }
            if (data.read() != -1) return null
            DeclarationProposal(id, kind, targetId, asOfEpoch, amountPaise, deltaPaise, proposedEpoch, auto, note, parts)
                .takeIf(::isWellFormed)
        }
    } catch (error: IOException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    fun encodeAnswer(answer: DeclarationAnswer): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(ANSWER_MAGIC)
            data.writeUTF(answer.proposalId)
            data.writeUTF(answer.verdict.name)
            data.writeUTF(answer.note.orEmpty())
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decodeAnswer(body: String): DeclarationAnswer? = try {
        DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(body))).use { data ->
            if (data.readUTF() != ANSWER_MAGIC) return null
            val proposalId = data.readUTF()
            val verdictName = data.readUTF()
            val note = data.readUTF().ifEmpty { null }
            if (data.read() != -1) return null
            val verdict = DeclarationVerdict.entries.firstOrNull { it.name == verdictName } ?: return null
            if (!MailboxIds.isRandomId(proposalId)) return null
            if (note != null && (note.isBlank() || note.length > MAX_NOTE_CHARS)) return null
            DeclarationAnswer(proposalId, verdict, note)
        }
    } catch (error: IOException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    /**
     * The shape each kind has to have. A DECLARE names an instant and an amount and nothing to
     * replace; an AMEND names what it replaces as well, and what it adds; a REVOKE names only what
     * it removes. Parts only ever ride on a DECLARE, and never the same chapter twice.
     */
    private fun isWellFormed(p: DeclarationProposal): Boolean {
        if (!MailboxIds.isRandomId(p.id)) return false
        if (p.note != null && (p.note.isBlank() || p.note.length > MAX_NOTE_CHARS)) return false
        if (p.parts.any { !MailboxIds.isRandomId(it.shareId) }) return false
        if (p.parts.map { it.shareId }.toSet().size != p.parts.size) return false
        return when (p.kind) {
            DeclarationKind.DECLARE ->
                p.targetId == null && p.asOfEpoch != null && p.amountPaise != null && p.deltaPaise == null
            DeclarationKind.AMEND ->
                p.targetId != null && MailboxIds.isRandomId(p.targetId) && p.targetId != p.id &&
                    p.asOfEpoch != null && p.amountPaise != null && p.deltaPaise != null && p.parts.isEmpty()
            DeclarationKind.REVOKE ->
                p.targetId != null && MailboxIds.isRandomId(p.targetId) && p.targetId != p.id &&
                    p.asOfEpoch == null && p.amountPaise == null && p.deltaPaise == null && p.parts.isEmpty()
            else -> false
        }
    }

    private fun writeOptionalLong(data: DataOutputStream, value: Long?) {
        data.writeBoolean(value != null)
        if (value != null) data.writeLong(value)
    }

    private fun readOptionalLong(data: DataInputStream): Long? =
        if (data.readBoolean()) data.readLong() else null
}
