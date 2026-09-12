package com.varun.upitracker.data.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.varun.upitracker.R
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.domain.backup.BackupPrefKeys
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Who is signed in. The email is for display; nothing is keyed off it. */
data class GoogleAccount(val email: String, val displayName: String?)

sealed interface SignInResult {
    data class Success(val account: GoogleAccount) : SignInResult
    /** The user backed out. Not an error, and nothing should be said about it. */
    data object Cancelled : SignInResult
    /** No Google account on the device, or none the picker would offer. */
    data object NoAccount : SignInResult
    data class Failed(val reason: String) : SignInResult
}

sealed interface DriveAuthResult {
    data class Authorized(val accessToken: String) : DriveAuthResult
    /** The user has to approve Drive access. Launch this, then call [GoogleAccountRepository.resultFrom]. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthResult
    data class Failed(val reason: String) : DriveAuthResult
}

/**
 * Signing in, and getting permission to touch Drive.
 *
 * These are two separate flows on purpose, which is how Google now splits them: Credential Manager
 * says *who you are*, `AuthorizationClient` says *what this app may reach*. Keeping them apart means
 * signing in never asks for Drive, and Drive is only asked for when a backup actually happens.
 *
 * Nothing about the session is persisted beyond the account email, and that only so the screen can
 * say who is signed in. **Access tokens are never stored** -- they last about an hour, and a fresh
 * one is cheap to ask for. Google Play services remembers the grant, so asking again is silent once
 * consent stands.
 */
class GoogleAccountRepository(context: Context) {

    private companion object {
        private const val TAG = "GoogleAccount"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }

    private val appContext = context.applicationContext
    private val prefs = AppPrefs.of(appContext)
    private val webClientId: String = appContext.getString(R.string.google_web_client_id)

    /** Null when nobody has signed in on this install. */
    fun signedInAccount(): String? = prefs.getString(BackupPrefKeys.ACCOUNT_EMAIL, null)

    /**
     * Forgets the account. Deliberately leaves the database, the preferences and the Drive file
     * alone: signing out is saying "stop using this account here", not "throw my data away".
     */
    fun signOut() {
        prefs.edit().remove(BackupPrefKeys.ACCOUNT_EMAIL).apply()
    }

    suspend fun signIn(activity: Activity): SignInResult {
        val option = GetGoogleIdOption.Builder()
            // False so the picker offers every account on the device, not only ones that have
            // already used this app. A first sign-in would otherwise show an empty sheet.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        return try {
            val response = CredentialManager.create(appContext)
                .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())

            // Checked rather than assumed: Credential Manager is a general mechanism, and
            // createFrom would throw on anything that is not a Google ID token. Only one option was
            // requested, so a different type here means something is wrong with the setup, and a
            // readable message beats a stack trace.
            val raw = response.credential
            if (raw !is CustomCredential || raw.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignInResult.Failed("Google returned a kind of sign-in this app cannot use.")
            }
            val credential = GoogleIdTokenCredential.createFrom(raw.data)
            val account = GoogleAccount(email = credential.id, displayName = credential.displayName)
            prefs.edit().putString(BackupPrefKeys.ACCOUNT_EMAIL, account.email).apply()
            SignInResult.Success(account)
        } catch (error: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (error: NoCredentialException) {
            SignInResult.NoAccount
        } catch (error: GetCredentialException) {
            Log.w(TAG, "Sign-in failed", error)
            SignInResult.Failed("Could not sign in to Google. ${error.message.orEmpty()}".trim())
        }
    }

    /**
     * Asks for a Drive access token, prompting only if consent is not already on file.
     *
     * Wrapped by hand rather than through `kotlinx-coroutines-play-services` -- that would be a whole
     * dependency for one `Task`.
     */
    suspend fun authorizeDrive(activity: Activity): DriveAuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(activity)
                .authorize(request)
                .addOnSuccessListener { result -> continuation.resume(interpret(result)) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Drive authorization failed", error)
                    continuation.resume(
                        DriveAuthResult.Failed(
                            "Could not get permission for Google Drive. ${error.message.orEmpty()}".trim()
                        )
                    )
                }
        }
    }

    /**
     * A Drive token without an Activity, for the automatic backup at launch.
     *
     * Succeeds only when consent already stands. When it does not, the caller has nowhere to show a
     * consent screen anyway -- the launcher activity has already finished -- so the right answer is
     * to skip quietly and leave it to the user to press the button.
     */
    suspend fun authorizeDriveSilently(): DriveAuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(appContext)
                .authorize(request)
                .addOnSuccessListener { result -> continuation.resume(interpret(result)) }
                .addOnFailureListener { error ->
                    Log.d(TAG, "Silent Drive authorization unavailable: ${error.message}")
                    continuation.resume(DriveAuthResult.Failed("Drive access is not granted yet."))
                }
        }
    }

    /** Reads the outcome of the consent screen launched for [DriveAuthResult.NeedsConsent]. */
    fun resultFrom(activity: Activity, data: Intent?): DriveAuthResult = try {
        interpret(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data))
    } catch (error: ApiException) {
        Log.w(TAG, "Could not read the authorization result", error)
        DriveAuthResult.Failed("Google Drive access was not granted.")
    }

    private fun interpret(result: AuthorizationResult): DriveAuthResult {
        result.pendingIntent?.let { if (result.hasResolution()) return DriveAuthResult.NeedsConsent(it) }
        val token = result.accessToken
        return if (token.isNullOrBlank()) {
            // Consent came back granted but empty. Treat it as a refusal rather than proceeding with
            // no token and failing later with a confusing 401.
            DriveAuthResult.Failed("Google did not return access to Drive. Try again.")
        } else {
            DriveAuthResult.Authorized(token)
        }
    }
}
