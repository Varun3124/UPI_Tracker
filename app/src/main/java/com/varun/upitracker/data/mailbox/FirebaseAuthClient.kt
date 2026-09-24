package com.varun.upitracker.data.mailbox

import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

/** A Firebase session, as the token endpoints hand it back. */
data class FirebaseTokens(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtEpoch: Long
)

/**
 * Firebase Authentication's REST API: exchange a Google ID token for a Firebase session, keep it
 * fresh, and delete the account behind it.
 *
 * Signing in to Firebase is what turns "someone with a Google account" into a `request.auth.uid` the
 * security rules can key on -- and a uid scoped to this Firebase project, so friends never see the
 * global Google identifier.
 */
class FirebaseAuthClient(private val config: MailboxConfig, private val http: MailboxHttp) {

    private companion object {
        const val IDENTITY = "https://identitytoolkit.googleapis.com/v1"
        const val SECURE_TOKEN = "https://securetoken.googleapis.com/v1"
        const val JSON = "application/json; charset=UTF-8"
        const val FORM = "application/x-www-form-urlencoded"

        /** What Firebase issues ID tokens for when a reply omits it. */
        const val DEFAULT_LIFETIME_SECONDS = 3600L
    }

    suspend fun signInWithGoogle(googleIdToken: String): FirebaseTokens {
        val body = JSONObject()
            .put("postBody", "id_token=" + URLEncoder.encode(googleIdToken, "UTF-8") + "&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnIdpCredential", true)
            .put("returnSecureToken", true)
        val reply = parseJson(
            http.request("POST", "$IDENTITY/accounts:signInWithIdp?key=${key()}", contentType = JSON,
                payload = body.toString().toByteArray(Charsets.UTF_8))
        )
        return tokens(reply, uidField = "localId", idField = "idToken", refreshField = "refreshToken", lifetimeField = "expiresIn")
    }

    suspend fun refresh(refreshToken: String): FirebaseTokens {
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val reply = parseJson(
            http.request("POST", "$SECURE_TOKEN/token?key=${key()}", contentType = FORM,
                payload = form.toByteArray(Charsets.UTF_8))
        )
        return tokens(reply, uidField = "user_id", idField = "id_token", refreshField = "refresh_token", lifetimeField = "expires_in")
    }

    /** Firebase only allows this for a recent sign-in, so callers sign in again just before. */
    suspend fun deleteAccount(idToken: String) {
        http.request(
            "POST", "$IDENTITY/accounts:delete?key=${key()}", contentType = JSON,
            payload = JSONObject().put("idToken", idToken).toString().toByteArray(Charsets.UTF_8)
        )
    }

    private fun key(): String {
        if (!config.isConfigured) {
            throw MailboxException("This version of the app has no friends mailbox set up.", MailboxException.Kind.NOT_READY)
        }
        return URLEncoder.encode(config.apiKey, "UTF-8")
    }

    private fun tokens(
        reply: JSONObject,
        uidField: String,
        idField: String,
        refreshField: String,
        lifetimeField: String
    ): FirebaseTokens = try {
        FirebaseTokens(
            uid = reply.getString(uidField),
            idToken = reply.getString(idField),
            refreshToken = reply.getString(refreshField),
            expiresAtEpoch = System.currentTimeMillis() +
                (reply.optString(lifetimeField).toLongOrNull() ?: DEFAULT_LIFETIME_SECONDS) * 1000L
        )
    } catch (error: JSONException) {
        throw MailboxException(
            "Firebase sent a sign-in reply this app could not read.",
            MailboxException.Kind.PERMANENT,
            error
        )
    }
}
