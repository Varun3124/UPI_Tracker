package com.varun.upitracker.data.mailbox

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.domain.mailbox.IdentityDecision
import com.varun.upitracker.domain.mailbox.IdentityPolicy
import com.varun.upitracker.domain.mailbox.MailboxKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the friends mailbox stands on this install. */
sealed interface MailboxStatus {

    /** This build was made without a Firebase project. */
    data object NotConfigured : MailboxStatus

    data object Off : MailboxStatus

    /** Signed in, but this phone holds no key. Turning the mailbox on again fetches it from Drive. */
    data class NeedsKeys(val email: String) : MailboxStatus

    data class On(
        val uid: String,
        val email: String,
        val displayName: String,
        val fingerprint: String
    ) : MailboxStatus
}

data class TurnOnResult(
    val status: MailboxStatus.On,
    /** Friends who linked before encrypt to a key nobody holds now. They have to re-link. */
    val replacedPublishedKey: Boolean,
    /** This is not the account the links on this phone were made with, so they were removed. */
    val removedOtherAccountsLinks: Boolean
)

/** The signed-in account and its keys, ready for use. */
class MailboxIdentity(val uid: String, val displayName: String, val keys: MailboxKeys)

/**
 * Turning the friends mailbox on and off: the Firebase session, the keys, and where copies of them
 * live.
 *
 * Deliberately knows nothing about Activities, like [com.varun.upitracker.data.backup.BackupRepository]:
 * the screen does the Google sheet and the Drive consent, and hands the tokens in.
 */
class MailboxIdentityRepository(context: Context, private val db: AppDatabase) {

    companion object {
        private const val TAG = "MailboxIdentity"

        /**
         * The account the links in this database were made with. A normal, backed-up preference on
         * purpose: it describes the links, and travels with them.
         */
        const val LINKS_OWNER_UID = "mailbox_links_owner_uid"

        fun identityFileName(uid: String) = "dhanmoney-identity-$uid.key"

        private const val PROP_UID = "uid"
        private const val PROP_FINGERPRINT = "fingerprint"

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 50
    }

    private val services = MailboxServices.get(context)
    private val prefs = AppPrefs.of(context)

    fun isConfigured(): Boolean = services.config.isConfigured

    suspend fun status(): MailboxStatus = withContext(Dispatchers.IO) {
        if (!services.config.isConfigured) return@withContext MailboxStatus.NotConfigured
        val session = services.auth.session() ?: return@withContext MailboxStatus.Off
        val keys = services.keyStore.load(session.uid) ?: return@withContext MailboxStatus.NeedsKeys(session.email)
        MailboxStatus.On(session.uid, session.email, session.displayName, keys.fingerprint)
    }

    /**
     * Whether this phone is signed in to the mailbox. Cheaper than [status]: the dashboard asks on
     * every load, and this reads one small file rather than unwrapping the keys as well.
     */
    suspend fun isSignedIn(): Boolean = withContext(Dispatchers.IO) {
        services.config.isConfigured && services.auth.session() != null
    }

    /** The account and its keys, or null when the mailbox is not fully on. */
    suspend fun identity(): MailboxIdentity? = withContext(Dispatchers.IO) {
        val session = services.auth.session() ?: return@withContext null
        val keys = services.keyStore.load(session.uid) ?: return@withContext null
        MailboxIdentity(session.uid, session.displayName, keys)
    }

    /**
     * Signs in, finds or makes the keys, and publishes them. See [IdentityPolicy] for which copy
     * wins; the order below is the one rule it leaves to the caller -- **Drive before publishing**.
     */
    suspend fun turnOn(
        googleIdToken: String,
        email: String,
        displayName: String?,
        driveToken: String
    ): TurnOnResult = withContext(Dispatchers.IO) {
        val session = services.auth.signIn(googleIdToken, email, displayName)
        val uid = session.uid

        // Links pin one account's relationships. Under a different account they describe somebody
        // else, and sending on them would go out under the wrong name.
        val linksOwner = prefs.getString(LINKS_OWNER_UID, null)
        val removedLinks = linksOwner != null && linksOwner != uid && db.mailboxDao().getAllLinks().isNotEmpty()
        if (linksOwner != null && linksOwner != uid) {
            db.withTransaction {
                db.mailboxDao().deleteAllLinks()
                db.mailboxDao().deleteAllInvites()
            }
        }
        prefs.edit().putString(LINKS_OWNER_UID, uid).apply()

        val idToken = services.auth.idToken()
        val local = services.keyStore.load(uid)
        val driveFile = services.drive.list(driveToken).firstOrNull { it.name == identityFileName(uid) }
        val driveKeys = driveFile
            ?.takeIf { it.appProperties[PROP_UID] == uid }
            ?.let { MailboxKeys.parsePrivate(services.drive.download(driveToken, it.id)) }
        val published = services.firestore.get(idToken, "users/$uid")?.text("fingerprint")

        val decision = IdentityPolicy.decide(local?.fingerprint, driveKeys?.fingerprint, published)
        Log.d(TAG, "Key decision: ${decision::class.simpleName}")
        val keys: MailboxKeys
        val replaced: Boolean
        when (decision) {
            is IdentityDecision.UseLocal -> {
                keys = checkNotNull(local)
                if (decision.uploadToDrive) uploadIdentity(driveToken, uid, keys, driveFile?.id)
                if (decision.publish) publish(idToken, uid, keys)
                replaced = decision.replacesPublished
            }
            is IdentityDecision.RestoreFromDrive -> {
                keys = checkNotNull(driveKeys)
                services.keyStore.save(uid, keys)
                if (decision.publish) publish(idToken, uid, keys)
                replaced = decision.replacesPublished
            }
            is IdentityDecision.Generate -> {
                keys = MailboxKeys.generate()
                uploadIdentity(driveToken, uid, keys, driveFile?.id)
                services.keyStore.save(uid, keys)
                publish(idToken, uid, keys)
                replaced = decision.replacesPublished
            }
        }

        TurnOnResult(
            status = MailboxStatus.On(uid, session.email, session.displayName, keys.fingerprint),
            replacedPublishedKey = replaced,
            removedOtherAccountsLinks = removedLinks
        )
    }

    /** Stops the mailbox on this phone. Links, messages and the Drive copy of the keys all stay. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        services.endSessionOnThisPhone()
    }

    /**
     * Deletes the mailbox account: everything it put in Firestore, the Drive copy of its keys, and
     * the Firebase account itself. Transactions are never touched.
     *
     * Starts with a fresh sign-in because Firebase only deletes an account whose sign-in is recent.
     * Each server step is attempted even if an earlier one failed, and only the account deletion
     * itself is allowed to stop the rest -- a half-deleted account is worse than a slightly untidy one.
     */
    suspend fun deleteAccount(
        googleIdToken: String,
        email: String,
        displayName: String?,
        driveToken: String
    ) = withContext(Dispatchers.IO) {
        val session = services.auth.signIn(googleIdToken, email, displayName)
        val uid = session.uid
        val idToken = services.auth.idToken()
        val firestore = services.firestore

        // Messages this account sent that nobody collected: take them back.
        val uidsByFriend = db.mailboxDao().getAllLinks().associate { it.friendId to it.uid }
        db.mailboxDao().getAllDeliveredMessages().forEach { delivered ->
            val friendUid = uidsByFriend[delivered.friendId] ?: return@forEach
            attempt("take back a message") { firestore.delete(idToken, "inbox/$friendUid/messages/${delivered.messageId}") }
        }
        db.mailboxDao().getAllInvites().forEach { invite ->
            attempt("withdraw an invite") { firestore.delete(idToken, "invites/${invite.id}") }
        }
        attempt("empty the inbox") { deleteCollection(idToken, "inbox/$uid/messages") }
        attempt("remove contacts") { deleteCollection(idToken, "users/$uid/contacts") }
        attempt("remove the published keys") { firestore.delete(idToken, "users/$uid") }
        attempt("remove the Drive copy of the keys") {
            services.drive.list(driveToken)
                .filter { it.name == identityFileName(uid) }
                .forEach { services.drive.delete(driveToken, it.id) }
        }

        services.authClient.deleteAccount(idToken)

        services.endSessionOnThisPhone()
        db.withTransaction {
            db.mailboxDao().deleteAllLinks()
            db.mailboxDao().deleteAllInvites()
            db.mailboxDao().deleteAllMessages()
            db.mailboxDao().deleteAllDeliveries()
        }
        prefs.edit().remove(LINKS_OWNER_UID).apply()
    }

    private suspend fun publish(idToken: String, uid: String, keys: MailboxKeys) {
        val public = keys.publicKeys
        services.firestore.set(
            idToken,
            "users/$uid",
            mapOf(
                "encKey" to FirestoreValue.Text(public.encryptionKeyText),
                "sigKey" to FirestoreValue.Text(public.signingKeyText),
                "fingerprint" to FirestoreValue.Text(public.fingerprint),
                "updatedAt" to FirestoreValue.Time(System.currentTimeMillis())
            )
        )
    }

    private suspend fun uploadIdentity(driveToken: String, uid: String, keys: MailboxKeys, existingFileId: String?) {
        services.drive.upload(
            accessToken = driveToken,
            fileId = existingFileId,
            name = identityFileName(uid),
            bytes = keys.serializePrivate(),
            appProperties = mapOf(PROP_UID to uid, PROP_FINGERPRINT to keys.fingerprint)
        )
    }

    private suspend fun deleteCollection(idToken: String, collection: String) {
        repeat(MAX_PAGES) {
            val page = services.firestore.list(idToken, collection, PAGE_SIZE)
            if (page.documents.isEmpty()) return
            page.documents.forEach { services.firestore.delete(idToken, "$collection/${it.id}") }
        }
    }

    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "Could not $what while deleting the account", error)
        }
    }
}
