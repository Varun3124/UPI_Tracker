package com.varun.upitracker.domain.mailbox

import com.varun.upitracker.domain.parcel.PasteFraming
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What an invite to link says, once read back out of the text that was pasted. */
data class LinkInviteCode(
    /** The server-visible half: it names the invite document, and lets the invitee write one reply. */
    val inviteId: String,
    /** Never sent to the server. Proves to the inviter that a reply came from someone holding the code. */
    val secret: String,
    val ownerUid: String,
    /** Of the inviter's published keys, so the invitee notices if the server hands out others. */
    val ownerFingerprint: String,
    /** What the inviter calls themselves. Shown, never matched against a friend's name. */
    val nameHint: String,
    val expiresEpoch: Long
)

sealed interface InviteDecodeResult {
    data class Ok(val invite: LinkInviteCode) : InviteDecodeResult
    data class Failed(val reason: String) : InviteDecodeResult
}

/**
 * An invite to link, as a pasteable `DHANLINK1.<crc32>.<payload>`.
 *
 * It travels the same way a parcel does -- through a chat app the two people already trust to say
 * who is talking -- and that is the point: the chat is the out-of-band channel that tells the invitee
 * the code really came from the inviter, and the secret inside it is what later tells the inviter
 * the reply really came from whoever received it.
 */
object InviteCode {

    const val PREFIX = "DHANLINK1"

    private const val FAMILY = "DHANLINK"

    const val MAX_NAME_HINT = 60

    private val FINGERPRINT = Regex("[0-9a-f]{32}")

    fun encode(invite: LinkInviteCode): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(invite.inviteId)
            data.writeUTF(invite.secret)
            data.writeUTF(invite.ownerUid)
            data.writeUTF(invite.ownerFingerprint)
            data.writeUTF(invite.nameHint.take(MAX_NAME_HINT))
            data.writeLong(invite.expiresEpoch)
        }
        return PasteFraming.frame(PREFIX, out.toByteArray())
    }

    fun decode(text: String): InviteDecodeResult {
        val payload = when (val framed = PasteFraming.unframe(text, PREFIX, FAMILY)) {
            is PasteFraming.Result.Ok -> framed.payload
            PasteFraming.Result.Empty -> return InviteDecodeResult.Failed("There is nothing here to read.")
            PasteFraming.Result.Foreign -> return InviteDecodeResult.Failed("That does not look like a link invite.")
            PasteFraming.Result.OtherVersion -> return InviteDecodeResult.Failed(
                "This invite was made by a newer version of the app. Update, then try again."
            )
            PasteFraming.Result.BadChecksum, PasteFraming.Result.Corrupted -> return InviteDecodeResult.Failed(
                "This invite is damaged, most likely cut short when it was copied. Ask for it to be sent again."
            )
        }

        val invite = try {
            DataInputStream(ByteArrayInputStream(payload)).use { data ->
                val read = LinkInviteCode(
                    inviteId = data.readUTF(),
                    secret = data.readUTF(),
                    ownerUid = data.readUTF(),
                    ownerFingerprint = data.readUTF(),
                    nameHint = data.readUTF(),
                    expiresEpoch = data.readLong()
                )
                if (data.read() != -1) return InviteDecodeResult.Failed("This invite is damaged. Ask for it to be sent again.")
                read
            }
        } catch (error: IOException) {
            return InviteDecodeResult.Failed("This invite is damaged. Ask for it to be sent again.")
        }

        val wellFormed = MailboxIds.isRandomId(invite.inviteId) &&
            MailboxIds.isRandomId(invite.secret) &&
            MailboxIds.isUid(invite.ownerUid) &&
            FINGERPRINT.matches(invite.ownerFingerprint) &&
            invite.nameHint.isNotBlank() && invite.nameHint.length <= MAX_NAME_HINT &&
            invite.expiresEpoch > 0
        return if (wellFormed) {
            InviteDecodeResult.Ok(invite)
        } else {
            InviteDecodeResult.Failed("This invite is damaged. Ask for it to be sent again.")
        }
    }

    fun isExpired(invite: LinkInviteCode, nowEpoch: Long): Boolean = nowEpoch >= invite.expiresEpoch

    /** Whether pasted text is meant to be an invite -- of any version -- rather than a parcel. */
    fun looksLikeInvite(text: String): Boolean = PasteFraming.prefixOf(text)?.startsWith(FAMILY) == true

    /** The path a tapped invite link lands on, both on the web page and in this app's intent filter. */
    const val LINK_PATH = "/link"

    /**
     * The invite as a link, because chat apps make a URL tappable and a bare code only copyable.
     *
     * The code sits in the fragment, after the `#`. Browsers never send a fragment to the server and
     * neither do the link previews chat apps fetch, so the page behind the link -- and whoever runs
     * it -- never sees the secret. Only the app that opens the link does.
     */
    fun link(host: String, code: String): String = "https://$host$LINK_PATH#$code"

    /** The invite inside a tapped link, a pasted link, or plain pasted code. Null when there is none. */
    fun codeFrom(text: String): String? =
        text.trim().substringAfterLast('#').trim().takeIf(::looksLikeInvite)

    /**
     * What an invitee sends back to prove they hold the code: an HMAC keyed by the secret the server
     * never saw, over everything the reply asks the inviter to trust. A server that read the invite
     * id off the wire still cannot forge one.
     */
    fun proof(secret: String, inviteId: String, responderUid: String, responderFingerprint: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
        val message = listOf("DHANLINK1-accept", inviteId, responderUid, responderFingerprint).joinToString("\n")
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** Constant-time, so a wrong proof takes as long to reject as a nearly right one. */
    fun proofMatches(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
}
