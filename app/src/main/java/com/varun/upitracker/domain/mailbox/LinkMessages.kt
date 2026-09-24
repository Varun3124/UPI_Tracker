package com.varun.upitracker.domain.mailbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** A reply to an invite: "I hold your code, and this is who I am." The body of [MailboxKind.LINK_ACCEPT]. */
data class LinkAccept(
    val inviteId: String,
    /** How the invitee's Google account names them. Shown to the inviter, never matched. */
    val responderName: String,
    /** Exactly as published in the invitee's `/users/{uid}`: base64url Tink public keysets. */
    val encryptionKey: String,
    val signingKey: String,
    /** [InviteCode.proof] over this reply, keyed by the secret only the code carried. */
    val proof: String
)

/**
 * The bodies of the three link messages.
 *
 * [MailboxKind.LINK_CONFIRMED] needs no body: the envelope's verified sender and recipient already
 * say everything. [MailboxKind.UNLINK] carries one word saying why.
 */
object LinkMessages {

    /** "That was not me you linked" -- the inviter did not recognise the reply. */
    const val UNLINK_DECLINED = "DECLINED"

    /** "I have unlinked you." */
    const val UNLINK_REMOVED = "REMOVED"

    private const val MAX_FIELD_CHARS = 8 * 1024

    fun encodeAccept(accept: LinkAccept): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(accept.inviteId)
            data.writeUTF(accept.responderName.take(InviteCode.MAX_NAME_HINT))
            data.writeUTF(accept.encryptionKey)
            data.writeUTF(accept.signingKey)
            data.writeUTF(accept.proof)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decodeAccept(body: String): LinkAccept? = try {
        DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(body))).use { data ->
            val accept = LinkAccept(
                inviteId = data.readUTF(),
                responderName = data.readUTF(),
                encryptionKey = data.readUTF(),
                signingKey = data.readUTF(),
                proof = data.readUTF()
            )
            if (data.read() != -1) return null
            accept.takeIf {
                MailboxIds.isRandomId(it.inviteId) &&
                    it.responderName.isNotBlank() && it.responderName.length <= InviteCode.MAX_NAME_HINT &&
                    it.encryptionKey.isNotEmpty() && it.encryptionKey.length <= MAX_FIELD_CHARS &&
                    it.signingKey.isNotEmpty() && it.signingKey.length <= MAX_FIELD_CHARS &&
                    it.proof.isNotEmpty() && it.proof.length <= MAX_FIELD_CHARS
            }
        }
    } catch (error: IOException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }
}
