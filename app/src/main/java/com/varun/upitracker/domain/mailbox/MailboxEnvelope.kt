package com.varun.upitracker.domain.mailbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

enum class MailboxKind {
    PARCEL,
    LINK_ACCEPT,
    LINK_CONFIRMED,
    UNLINK,

    /** A proposal about the agreed balance between two linked friends. See docs/declarations-design.md. */
    DECLARATION_PROPOSAL,

    /** An answer to one: accepted, denied, or withdrawn by its proposer. */
    DECLARATION_ANSWER,

    /** A shared chapter, whole, as its owner sends it to one member. See docs/declarations-design.md S3. */
    CHAPTER_SNAPSHOT,

    /** The owner stopped sharing a chapter, or deleted it. */
    CHAPTER_ENDED,

    /**
     * Never sent. What a kind from a newer version of the app reads as here, so the inbox can say
     * "update the app to read this" rather than calling a perfectly good message damaged.
     */
    UNSUPPORTED
}

/**
 * One message in someone's mailbox, as its sender wrote it and before anything protects it.
 * `MailboxCrypto` signs these fields and seals them to the reader; this file only lays them out.
 */
data class MailboxEnvelope(
    val kind: MailboxKind,
    /** The Firestore document id it is stored under. Bound into the seal, so a copy under another id will not open. */
    val messageId: String,
    val senderUid: String,
    val recipientUid: String,
    val createdEpoch: Long,
    val body: String
)

/**
 * The byte layout of a [MailboxEnvelope]. Pure, so the parts that decide what gets signed and what a
 * ciphertext is bound to stay under plain JUnit.
 *
 * `java.io.Data*Stream` rather than a text grammar: every field is length-prefixed, so no field can
 * be stretched into its neighbour -- `("ab", "c")` and `("a", "bc")` never sign the same.
 */
object MailboxEnvelopeFormat {

    const val MAGIC = "DHMX1"

    /** A hundred-transaction parcel is tens of kilobytes. This only bounds what a hostile sender can make us allocate. */
    const val MAX_BODY_BYTES = 200 * 1024

    /** What a kind's name can look like, including one this version has never heard of. */
    private val KIND_NAME = Regex("[A-Z][A-Z_]{0,39}")

    private const val MAX_SIGNATURE_BYTES = 1024

    /** The exact bytes the sender signs. */
    fun fields(envelope: MailboxEnvelope): ByteArray {
        require(envelope.kind != MailboxKind.UNSUPPORTED) { "UNSUPPORTED only ever describes a message received." }
        val body = envelope.body.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_BODY_BYTES) { "A mailbox message is capped at $MAX_BODY_BYTES bytes." }
        val out = ByteArrayOutputStream(body.size + 256)
        DataOutputStream(out).use { data ->
            data.writeUTF(MAGIC)
            data.writeUTF(envelope.kind.name)
            data.writeUTF(envelope.messageId)
            data.writeUTF(envelope.senderUid)
            data.writeUTF(envelope.recipientUid)
            data.writeLong(envelope.createdEpoch)
            data.writeInt(body.size)
            data.write(body)
        }
        return out.toByteArray()
    }

    /** Null for anything that is not exactly what [fields] writes. */
    fun parseFields(bytes: ByteArray): MailboxEnvelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readUTF() != MAGIC) return null
            val kindName = data.readUTF()
            if (!KIND_NAME.matches(kindName)) return null
            // A newer app may send a kind this one does not know. It is still a signed, sealed message
            // from its sender, so it is read as UNSUPPORTED rather than refused as malformed.
            val kind = MailboxKind.entries.firstOrNull { it.name == kindName && it != MailboxKind.UNSUPPORTED }
                ?: MailboxKind.UNSUPPORTED
            val messageId = data.readUTF()
            val senderUid = data.readUTF()
            val recipientUid = data.readUTF()
            val createdEpoch = data.readLong()
            val bodySize = data.readInt()
            if (bodySize < 0 || bodySize > MAX_BODY_BYTES) return null
            val body = ByteArray(bodySize)
            data.readFully(body)
            if (data.read() != -1) return null
            if (!MailboxIds.isRandomId(messageId) || !MailboxIds.isUid(senderUid) || !MailboxIds.isUid(recipientUid)) {
                return null
            }
            MailboxEnvelope(kind, messageId, senderUid, recipientUid, createdEpoch, String(body, Charsets.UTF_8))
        }
    } catch (error: IOException) {
        null
    }

    /**
     * What HPKE binds the ciphertext to without encrypting it. The reader rebuilds it from the
     * document -- its `from`, their own uid and its id -- so a message copied into another inbox, or
     * stored again under another id, fails to open rather than opening as something it is not.
     */
    fun contextInfo(senderUid: String, recipientUid: String, messageId: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(MAGIC)
            data.writeUTF(senderUid)
            data.writeUTF(recipientUid)
            data.writeUTF(messageId)
        }
        return out.toByteArray()
    }

    /** The fields and the sender's signature over them, as the one plaintext that gets sealed. */
    fun pack(fields: ByteArray, signature: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(fields.size + signature.size + 8)
        DataOutputStream(out).use { data ->
            data.writeInt(fields.size)
            data.write(fields)
            data.writeInt(signature.size)
            data.write(signature)
        }
        return out.toByteArray()
    }

    /** Fields to signature, or null when [plaintext] is not exactly what [pack] writes. */
    fun unpack(plaintext: ByteArray): Pair<ByteArray, ByteArray>? = try {
        DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            val fieldsSize = data.readInt()
            if (fieldsSize < 0 || fieldsSize > MAX_BODY_BYTES + 4096) return null
            val fields = ByteArray(fieldsSize).also(data::readFully)
            val signatureSize = data.readInt()
            if (signatureSize <= 0 || signatureSize > MAX_SIGNATURE_BYTES) return null
            val signature = ByteArray(signatureSize).also(data::readFully)
            if (data.read() != -1) return null
            fields to signature
        }
    } catch (error: IOException) {
        null
    }
}
