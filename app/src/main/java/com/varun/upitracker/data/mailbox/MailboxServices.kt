package com.varun.upitracker.data.mailbox

import android.content.Context
import com.varun.upitracker.data.backup.DriveAppDataClient
import com.varun.upitracker.domain.mailbox.MailboxCrypto
import com.varun.upitracker.domain.mailbox.MailboxEnvelope
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.mailbox.MailboxKind
import com.varun.upitracker.domain.mailbox.PublicMailboxKeys

/**
 * One set of mailbox clients per process, built on first use.
 *
 * The app has no dependency injection, and these must be shared rather than rebuilt per screen:
 * [MailboxAuth] holds the in-memory ID token and the lock that stops two screens refreshing it at once.
 */
class MailboxServices private constructor(context: Context) {

    val config: MailboxConfig = MailboxConfig.from(context)
    val http: MailboxHttp = MailboxHttp(config)
    val authClient: FirebaseAuthClient = FirebaseAuthClient(config, http)
    val firestore: FirestoreClient = FirestoreClient(config, http)
    val sessionStore: MailboxSessionStore = MailboxSessionStore(context)
    val auth: MailboxAuth = MailboxAuth(config, authClient, sessionStore)
    val keyStore: IdentityKeyStore = IdentityKeyStore(context)
    val drive: DriveAppDataClient = DriveAppDataClient()

    /**
     * Signs [body] as [identity], seals it to [recipientKeys], and drops it into [recipientUid]'s inbox
     * as one new message. Returns the message's id, which is also its Firestore document id.
     *
     * Every small message the app sends goes through here: link control messages, and the proposals
     * and answers about an agreed balance. Parcels are built the same way in
     * [MailboxSendRepository], which also records a delivery per transaction.
     */
    suspend fun post(
        identity: MailboxIdentity,
        idToken: String,
        recipientUid: String,
        recipientKeys: PublicMailboxKeys,
        kind: MailboxKind,
        body: String
    ): String {
        val messageId = MailboxIds.newRandomId()
        val envelope = MailboxEnvelope(kind, messageId, identity.uid, recipientUid, System.currentTimeMillis(), body)
        firestore.create(
            idToken,
            "inbox/$recipientUid/messages",
            messageId,
            mapOf(
                "from" to FirestoreValue.Text(identity.uid),
                "ciphertext" to FirestoreValue.Bytes(MailboxCrypto.seal(envelope, identity.keys, recipientKeys))
            )
        )
        return messageId
    }

    /**
     * Ends the session and drops this phone's copy of the keys, which Drive still holds. For signing
     * out of Google entirely: the mailbox is the same account, so it stops too.
     */
    fun endSessionOnThisPhone() {
        sessionStore.read()?.uid?.let(keyStore::delete)
        auth.signOut()
    }

    companion object {
        @Volatile
        private var instance: MailboxServices? = null

        fun get(context: Context): MailboxServices = instance ?: synchronized(this) {
            instance ?: MailboxServices(context.applicationContext).also { instance = it }
        }
    }
}
