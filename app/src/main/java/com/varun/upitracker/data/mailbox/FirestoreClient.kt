package com.varun.upitracker.data.mailbox

import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** The only field types the mailbox stores. */
sealed interface FirestoreValue {
    data class Text(val value: String) : FirestoreValue
    class Bytes(val value: ByteArray) : FirestoreValue
    data class Time(val epochMillis: Long) : FirestoreValue
}

/** One document as the REST API returns it. */
class FirestoreDocument(
    /** The last path segment. */
    val id: String,
    private val fields: JSONObject,
    /** Set by the server; what a message's age is judged by, since no client clock is trusted. */
    val createTimeEpoch: Long
) {
    fun text(name: String): String? =
        fields.optJSONObject(name)?.takeIf { it.has("stringValue") }?.getString("stringValue")

    /** Firestore writes bytes as standard base64; nothing else is accepted in a bytes field. */
    fun bytes(name: String): ByteArray? =
        fields.optJSONObject(name)?.takeIf { it.has("bytesValue") }?.let {
            runCatching { Base64.getDecoder().decode(it.getString("bytesValue")) }.getOrNull()
        }

    fun has(name: String): Boolean = fields.has(name)
}

class FirestorePage(val documents: List<FirestoreDocument>, val nextPageToken: String?)

/**
 * The handful of Cloud Firestore REST calls the mailbox makes.
 *
 * Every call carries the user's Firebase ID token, so every call is checked against
 * `firebase/firestore.rules` exactly as an SDK call would be. Paths are built only from uids and ids
 * that have already passed [com.varun.upitracker.domain.mailbox.MailboxIds], so nothing here escapes
 * a path segment.
 */
class FirestoreClient(config: MailboxConfig, private val http: MailboxHttp) {

    private companion object {
        const val JSON = "application/json; charset=UTF-8"
    }

    private val documents =
        "https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents"

    /** Null when the document does not exist. */
    suspend fun get(idToken: String, path: String): FirestoreDocument? = try {
        parseDocument(parseJson(http.request("GET", "$documents/$path", bearer = idToken)))
    } catch (error: MailboxException) {
        if (error.kind == MailboxException.Kind.NOT_FOUND) null else throw error
    }

    /** Fails with [MailboxException.Kind.ALREADY_EXISTS] rather than overwrite. */
    suspend fun create(idToken: String, collection: String, documentId: String, fields: Map<String, FirestoreValue>) {
        http.request(
            "POST",
            "$documents/$collection?documentId=${URLEncoder.encode(documentId, "UTF-8")}",
            bearer = idToken,
            contentType = JSON,
            payload = body(fields)
        )
    }

    /** Creates the document, or replaces every field of an existing one. */
    suspend fun set(idToken: String, path: String, fields: Map<String, FirestoreValue>) {
        http.request("PATCH", "$documents/$path", bearer = idToken, contentType = JSON, payload = body(fields))
    }

    suspend fun list(idToken: String, collection: String, pageSize: Int, pageToken: String? = null): FirestorePage {
        val url = buildString {
            append("$documents/$collection?pageSize=$pageSize")
            pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val reply = parseJson(http.request("GET", url, bearer = idToken))
        // An empty collection comes back as `{}`, with no documents array at all.
        val array = reply.optJSONArray("documents") ?: JSONArray()
        return FirestorePage(
            documents = (0 until array.length()).mapNotNull { parseDocument(array.getJSONObject(it)) },
            nextPageToken = reply.optString("nextPageToken").takeIf { it.isNotBlank() }
        )
    }

    /** Succeeds whether or not the document existed -- the rules permitting. */
    suspend fun delete(idToken: String, path: String) {
        http.request("DELETE", "$documents/$path", bearer = idToken)
    }

    private fun body(fields: Map<String, FirestoreValue>): ByteArray {
        val encoded = JSONObject()
        fields.forEach { (name, value) ->
            encoded.put(
                name,
                when (value) {
                    is FirestoreValue.Text -> JSONObject().put("stringValue", value.value)
                    is FirestoreValue.Bytes -> JSONObject().put("bytesValue", Base64.getEncoder().encodeToString(value.value))
                    is FirestoreValue.Time -> JSONObject().put("timestampValue", Instant.ofEpochMilli(value.epochMillis).toString())
                }
            )
        }
        return JSONObject().put("fields", encoded).toString().toByteArray(Charsets.UTF_8)
    }

    private fun parseDocument(json: JSONObject): FirestoreDocument? {
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
        val createTime = try {
            Instant.parse(json.optString("createTime")).toEpochMilli()
        } catch (error: DateTimeParseException) {
            0L
        }
        return FirestoreDocument(
            id = name.substringAfterLast('/'),
            fields = json.optJSONObject("fields") ?: JSONObject(),
            createTimeEpoch = createTime
        )
    }
}
