package com.varun.upitracker.data.backup

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** One file in the app's private Drive folder. */
data class DriveFile(
    val id: String,
    val name: String,
    val modifiedTimeIso: String?,
    val sizeBytes: Long?,
    /**
     * Small key-values Drive stores beside the file. The backup mirrors its header into these so a
     * conflict can be judged from the listing alone, without downloading anything.
     */
    val appProperties: Map<String, String>
)

/**
 * What went wrong, at the only granularity the caller can act on.
 *
 * [UNAUTHORIZED] means ask for consent again; [TRANSIENT] means try later and change nothing;
 * [PERMANENT] means stop and tell the user.
 */
class DriveException(message: String, val kind: Kind, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Kind { UNAUTHORIZED, TRANSIENT, PERMANENT }
}

/**
 * Google Drive's app-data folder, over plain HTTP.
 *
 * Four REST calls against `spaces=appDataFolder`, written directly rather than through Google's API
 * client libraries. Those would add megabytes of dex to an APK that is already five dex files
 * without minification, to do what `HttpURLConnection` does in a few hundred lines. `org.json` comes
 * free with the platform, which is why the JSON handling lives here and not in `domain/`, where the
 * unit tests run on a plain JVM that has no `org.json`.
 *
 * The app-data folder is invisible in the user's Drive UI and readable only by this app signed with
 * this certificate. Files in it cannot be shared or moved to another space.
 */
class DriveAppDataClient {

    companion object {
        private const val TAG = "DriveAppData"

        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

        /**
         * Drive takes a single-request upload up to 5 MB. Stopping short of it turns "your backup
         * silently stopped working" into a message that says what happened, and leaves room for the
         * multipart wrapper around the payload.
         */
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    suspend fun list(accessToken: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val url = FILES +
            "?spaces=appDataFolder" +
            "&pageSize=100" +
            "&fields=" + encode("files(id,name,modifiedTime,size,appProperties)")
        val body = request(url, "GET", accessToken) { it.readText() }
        parseFileList(body)
    }

    suspend fun download(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        request("$FILES/${encode(fileId)}?alt=media", "GET", accessToken) { it.readBytes() }
    }

    suspend fun delete(accessToken: String, fileId: String) {
        withContext(Dispatchers.IO) {
            request("$FILES/${encode(fileId)}", "DELETE", accessToken) { it.readBytes() }
        }
    }

    /**
     * Creates [name], or replaces the contents of [fileId] when one is given.
     *
     * Multipart rather than resumable: a dump of this database is well under a megabyte, and
     * resumable would be two round trips and a state machine for no gain. [MAX_UPLOAD_BYTES] is the
     * guard that keeps that assumption honest.
     */
    suspend fun upload(
        accessToken: String,
        fileId: String?,
        name: String,
        bytes: ByteArray,
        appProperties: Map<String, String>
    ): DriveFile = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw DriveException(
                "This backup is too large to upload in one piece (${bytes.size / (1024 * 1024)} MB).",
                DriveException.Kind.PERMANENT
            )
        }

        val metadata = JSONObject().apply {
            put("name", name)
            // Only on create: Drive rejects a parent change on update, and the file is already there.
            if (fileId == null) put("parents", org.json.JSONArray().put("appDataFolder"))
            put("appProperties", JSONObject(appProperties.toMap<String, Any>()))
        }

        val boundary = "dhanmoney-" + UUID.randomUUID().toString().replace("-", "")
        val body = multipartBody(boundary, metadata.toString(), bytes)

        val url = if (fileId == null) {
            "$UPLOAD?uploadType=multipart&fields=" + encode("id,name,modifiedTime,size,appProperties")
        } else {
            "$UPLOAD/${encode(fileId)}?uploadType=multipart&fields=" +
                encode("id,name,modifiedTime,size,appProperties")
        }

        val response = request(
            url = url,
            method = if (fileId == null) "POST" else "PATCH",
            accessToken = accessToken,
            contentType = "multipart/related; boundary=$boundary",
            payload = body
        ) { it.readText() }

        parseFile(JSONObject(response))
    }

    /**
     * RFC 2387: metadata part first with a JSON content type, then the media part, then a closing
     * boundary. Built as bytes rather than a string because the payload is compressed binary.
     */
    private fun multipartBody(boundary: String, metadataJson: String, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 512)
        out.write(
            (
                "--$boundary\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                    metadataJson + "\r\n" +
                    "--$boundary\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun <T> request(
        url: String,
        method: String,
        accessToken: String,
        contentType: String? = null,
        payload: ByteArray? = null,
        read: (InputStream) -> T
    ): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (payload != null) {
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
            }
        }

        try {
            payload?.let { connection.outputStream.use { stream -> stream.write(it) } }

            val status = connection.responseCode
            if (status in 200..299) {
                return connection.inputStream.use(read)
            }

            val detail = connection.errorStream?.use { it.readText() }.orEmpty().take(400)
            Log.w(TAG, "$method $url -> $status $detail")
            throw DriveException(describe(status, detail), kindOf(status))
        } catch (error: DriveException) {
            throw error
        } catch (error: IOException) {
            // No network, DNS failure, a dropped connection: try again later, change nothing.
            throw DriveException(
                "Could not reach Google Drive. Check your connection and try again.",
                DriveException.Kind.TRANSIENT,
                error
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun kindOf(status: Int): DriveException.Kind = when {
        status == 401 -> DriveException.Kind.UNAUTHORIZED
        // 403 is overloaded: it covers both "rate limited, come back" and "you may not do this".
        // Rate limiting is the recoverable one and far likelier here, so treat it as transient.
        status == 403 || status == 429 || status >= 500 -> DriveException.Kind.TRANSIENT
        else -> DriveException.Kind.PERMANENT
    }

    private fun describe(status: Int, detail: String): String = when (kindOf(status)) {
        DriveException.Kind.UNAUTHORIZED -> "Google Drive access has expired. Grant it again to continue."
        DriveException.Kind.TRANSIENT -> "Google Drive is busy or unreachable right now. Try again shortly."
        DriveException.Kind.PERMANENT -> "Google Drive refused the request ($status)."
    }

    private fun parseFileList(body: String): List<DriveFile> = try {
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        (0 until files.length()).map { parseFile(files.getJSONObject(it)) }
    } catch (error: JSONException) {
        throw DriveException("Google Drive sent a reply this app could not read.", DriveException.Kind.PERMANENT, error)
    }

    private fun parseFile(json: JSONObject): DriveFile {
        val properties = json.optJSONObject("appProperties")
        return DriveFile(
            id = json.getString("id"),
            name = json.optString("name"),
            modifiedTimeIso = json.optString("modifiedTime").takeIf { it.isNotBlank() },
            // Drive sends size as a string, and omits it for some files entirely.
            sizeBytes = json.optString("size").toLongOrNull(),
            appProperties = properties?.keys()?.asSequence()
                ?.associateWith { properties.optString(it) }
                .orEmpty()
        )
    }

    private fun InputStream.readText(): String = readBytes().toString(Charsets.UTF_8)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
