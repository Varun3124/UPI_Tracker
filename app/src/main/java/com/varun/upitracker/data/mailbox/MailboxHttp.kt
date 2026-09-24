package com.varun.upitracker.data.mailbox

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * What went wrong talking to the mailbox, at the granularity a caller can act on.
 *
 * The same idea as [com.varun.upitracker.data.backup.DriveException], with the extra kinds Firestore
 * needs: a rules refusal means something specific here -- usually "they have not linked you" -- and
 * is nothing like a network failure.
 */
class MailboxException(message: String, val kind: Kind, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** This build has no Firebase project, or this install is not signed in to the mailbox. */
        NOT_READY,

        /** The session is gone. Turn the mailbox on again. */
        UNAUTHORIZED,

        /** The security rules said no. For a send: the recipient has not linked this account. */
        PERMISSION_DENIED,

        NOT_FOUND,

        ALREADY_EXISTS,

        /** No network, or the server is busy. Try later and change nothing. */
        TRANSIENT,

        PERMANENT
    }
}

/**
 * Firebase Auth and Firestore over plain HTTP, the way [com.varun.upitracker.data.backup.DriveAppDataClient]
 * talks to Drive and for the same reasons: no Google client libraries, no `google-services` plugin,
 * and a request is a few lines of `HttpURLConnection`.
 *
 * Never logs a request body or a token -- both carry credentials, and a body may carry a sealed
 * message whose metadata is still nobody's business.
 */
class MailboxHttp(private val config: MailboxConfig) {

    private companion object {
        const val TAG = "MailboxHttp"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000

        /** Firebase Auth's ways of saying a session cannot be refreshed any more. */
        val SESSION_ENDED = listOf(
            "TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN",
            "INVALID_ID_TOKEN", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN"
        )
    }

    suspend fun request(
        method: String,
        url: String,
        bearer: String? = null,
        contentType: String? = null,
        payload: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            contentType?.let { setRequestProperty("Content-Type", it) }
            // Lets an API key restricted to this app, signed with this certificate, be used at all.
            setRequestProperty("X-Android-Package", config.androidPackage)
            config.androidCertSha1?.let { setRequestProperty("X-Android-Cert", it) }
            if (payload != null) {
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
            }
        }

        try {
            payload?.let { connection.outputStream.use { stream -> stream.write(it) } }

            val status = connection.responseCode
            if (status in 200..299) {
                return@withContext connection.inputStream.use { it.readText() }
            }

            val detail = connection.errorStream?.use { it.readText() }.orEmpty()
            val error = runCatching { JSONObject(detail).optJSONObject("error") }.getOrNull()
            val errorStatus = error?.optString("status")?.takeIf { it.isNotBlank() }
            val errorMessage = error?.optString("message")?.takeIf { it.isNotBlank() }
            // The path only: the query string can hold the API key.
            Log.w(TAG, "$method ${url.substringBefore('?')} -> $status $errorStatus ${errorMessage?.take(200)}")
            val kind = kindOf(status, errorStatus, errorMessage)
            throw MailboxException(describe(kind, status), kind)
        } catch (error: MailboxException) {
            throw error
        } catch (error: IOException) {
            throw MailboxException(
                "Could not reach the friends mailbox. Check your connection and try again.",
                MailboxException.Kind.TRANSIENT,
                error
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun kindOf(status: Int, errorStatus: String?, errorMessage: String?): MailboxException.Kind = when {
        status == 401 -> MailboxException.Kind.UNAUTHORIZED
        errorMessage != null && SESSION_ENDED.any { errorMessage.startsWith(it) } -> MailboxException.Kind.UNAUTHORIZED
        // A 403 is also how Google says an API is switched off or a key is restricted. Only the
        // rules' own refusal means "not allowed to write there"; anything else is a setup problem.
        status == 403 && errorMessage?.contains("insufficient permissions", ignoreCase = true) == true ->
            MailboxException.Kind.PERMISSION_DENIED
        status == 404 || errorStatus == "NOT_FOUND" -> MailboxException.Kind.NOT_FOUND
        status == 409 || errorStatus == "ALREADY_EXISTS" -> MailboxException.Kind.ALREADY_EXISTS
        status == 429 || status >= 500 || errorStatus == "UNAVAILABLE" -> MailboxException.Kind.TRANSIENT
        else -> MailboxException.Kind.PERMANENT
    }

    private fun describe(kind: MailboxException.Kind, status: Int): String = when (kind) {
        MailboxException.Kind.UNAUTHORIZED -> "Your mailbox sign-in has ended. Turn the friends mailbox on again."
        MailboxException.Kind.PERMISSION_DENIED -> "The friends mailbox would not allow that."
        MailboxException.Kind.NOT_FOUND -> "The friends mailbox has no record of that."
        MailboxException.Kind.ALREADY_EXISTS -> "That is already in the friends mailbox."
        MailboxException.Kind.TRANSIENT -> "The friends mailbox is busy right now. Try again shortly."
        MailboxException.Kind.NOT_READY, MailboxException.Kind.PERMANENT ->
            "The friends mailbox refused the request ($status)."
    }

    private fun InputStream.readText(): String = readBytes().toString(Charsets.UTF_8)
}

/** Reads a JSON reply, turning a malformed one into a [MailboxException] rather than a crash. */
internal fun parseJson(body: String): JSONObject = try {
    JSONObject(body)
} catch (error: JSONException) {
    throw MailboxException(
        "The friends mailbox sent a reply this app could not read.",
        MailboxException.Kind.PERMANENT,
        error
    )
}
