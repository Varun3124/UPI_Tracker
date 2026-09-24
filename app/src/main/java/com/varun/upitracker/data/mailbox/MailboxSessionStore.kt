package com.varun.upitracker.data.mailbox

import android.content.Context
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKeys
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/** Who is signed in to the mailbox on this install. */
data class MailboxSession(
    val uid: String,
    val email: String,
    /** What the Google account calls them. Goes into invites and replies as a hint, never matched. */
    val displayName: String,
    val refreshToken: String,
    /** When the inbox was last collected. Throttles collection on dashboard resume. */
    val lastSyncEpoch: Long = 0L
)

/**
 * The mailbox session, in a sealed file outside every backup.
 *
 * Not in the app's preferences: those ride Android Auto Backup and the Drive backup both, and a
 * refresh token restored onto another phone is a live credential turning up somewhere it was never
 * given.
 */
class MailboxSessionStore(context: Context) {

    private companion object {
        const val LABEL = "dhanmoney-mailbox-session"
    }

    private val file = File(File(context.applicationContext.noBackupFilesDir, "mailbox"), "session.bin")

    @Synchronized
    fun read(): MailboxSession? {
        val plaintext = LocalVault.read(file, LABEL) ?: return null
        return try {
            val json = JSONObject(plaintext.toString(Charsets.UTF_8))
            MailboxSession(
                uid = json.getString("uid"),
                email = json.getString("email"),
                displayName = json.getString("displayName"),
                refreshToken = json.getString("refreshToken"),
                lastSyncEpoch = json.optLong("lastSyncEpoch", 0L)
            ).takeIf { MailboxIds.isUid(it.uid) }
        } catch (error: JSONException) {
            null
        }
    }

    @Synchronized
    fun write(session: MailboxSession) {
        val json = JSONObject()
            .put("uid", session.uid)
            .put("email", session.email)
            .put("displayName", session.displayName)
            .put("refreshToken", session.refreshToken)
            .put("lastSyncEpoch", session.lastSyncEpoch)
        LocalVault.write(file, json.toString().toByteArray(Charsets.UTF_8), LABEL)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}

/**
 * The private mailbox keys on this phone, one sealed file per account.
 *
 * Per account so that signing in as someone else can never pick up another person's keys. The
 * recoverable copy is the one in Drive; this is the everyday one.
 */
class IdentityKeyStore(context: Context) {

    private val directory = File(context.applicationContext.noBackupFilesDir, "mailbox")

    private fun fileFor(uid: String): File {
        // The uid becomes part of a file name; only a real one may.
        require(MailboxIds.isUid(uid)) { "Not an account id." }
        return File(directory, "identity-$uid.bin")
    }

    private fun label(uid: String) = "dhanmoney-mailbox-identity:$uid"

    fun load(uid: String): MailboxKeys? =
        LocalVault.read(fileFor(uid), label(uid))?.let(MailboxKeys::parsePrivate)

    fun save(uid: String, keys: MailboxKeys) {
        LocalVault.write(fileFor(uid), keys.serializePrivate(), label(uid))
    }

    fun delete(uid: String) {
        fileFor(uid).delete()
    }
}
