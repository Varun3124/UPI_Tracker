package com.varun.upitracker.data.mailbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This install's Firebase session: who is signed in to the mailbox, and a fresh ID token on demand.
 *
 * ID tokens last an hour and are only ever held in memory. The refresh token is the one long-lived
 * credential, and it lives in [MailboxSessionStore].
 */
class MailboxAuth(
    private val config: MailboxConfig,
    private val client: FirebaseAuthClient,
    private val store: MailboxSessionStore
) {

    private companion object {
        /** Refreshed this long before expiry, so a token never runs out mid-request. */
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    }

    private val mutex = Mutex()

    @Volatile
    private var tokens: FirebaseTokens? = null

    fun session(): MailboxSession? = store.read()

    /**
     * Exchanges a Google ID token -- straight from Credential Manager, never stored -- for a Firebase
     * session, and remembers it.
     */
    suspend fun signIn(googleIdToken: String, email: String, displayName: String?): MailboxSession = mutex.withLock {
        requireConfigured()
        val fresh = client.signInWithGoogle(googleIdToken)
        val previous = store.read()
        val session = MailboxSession(
            uid = fresh.uid,
            email = email,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
            refreshToken = fresh.refreshToken,
            lastSyncEpoch = previous?.takeIf { it.uid == fresh.uid }?.lastSyncEpoch ?: 0L
        )
        store.write(session)
        tokens = fresh
        session
    }

    /** A Firebase ID token with at least a few minutes left. */
    suspend fun idToken(forceRefresh: Boolean = false): String = mutex.withLock {
        requireConfigured()
        val session = store.read()
            ?: throw MailboxException("Turn the friends mailbox on first.", MailboxException.Kind.NOT_READY)
        val current = tokens
        if (!forceRefresh && current != null && current.uid == session.uid &&
            current.expiresAtEpoch - System.currentTimeMillis() > REFRESH_MARGIN_MS
        ) {
            return@withLock current.idToken
        }
        val fresh = try {
            client.refresh(session.refreshToken)
        } catch (error: MailboxException) {
            if (error.kind == MailboxException.Kind.UNAUTHORIZED) end()
            throw error
        }
        if (fresh.uid != session.uid) {
            end()
            throw MailboxException("Your mailbox sign-in changed. Turn the friends mailbox on again.", MailboxException.Kind.UNAUTHORIZED)
        }
        tokens = fresh
        if (fresh.refreshToken != session.refreshToken) store.write(session.copy(refreshToken = fresh.refreshToken))
        fresh.idToken
    }

    /**
     * Runs [block] with a token and the signed-in uid, and once more with a forced refresh when the
     * server calls the first token stale -- a revoked or clock-skewed token is not worth an error.
     */
    suspend fun <T> authorized(block: suspend (idToken: String, uid: String) -> T): T {
        val uid = store.read()?.uid
            ?: throw MailboxException("Turn the friends mailbox on first.", MailboxException.Kind.NOT_READY)
        return try {
            block(idToken(), uid)
        } catch (error: MailboxException) {
            if (error.kind != MailboxException.Kind.UNAUTHORIZED) throw error
            block(idToken(forceRefresh = true), uid)
        }
    }

    fun recordSync(epoch: Long) {
        store.read()?.let { store.write(it.copy(lastSyncEpoch = epoch)) }
    }

    /** Forgets the session on this phone. The account, and everything in Firestore, stays. */
    fun signOut() {
        end()
    }

    private fun end() {
        store.clear()
        tokens = null
    }

    private fun requireConfigured() {
        if (!config.isConfigured) {
            throw MailboxException("This version of the app has no friends mailbox set up.", MailboxException.Kind.NOT_READY)
        }
    }
}
