package com.varun.upitracker.data.mailbox

import android.content.Context
import com.varun.upitracker.data.backup.DriveAppDataClient

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
