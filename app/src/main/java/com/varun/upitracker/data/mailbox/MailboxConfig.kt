package com.varun.upitracker.data.mailbox

import android.content.Context
import android.content.pm.PackageManager
import com.varun.upitracker.R
import java.security.MessageDigest

/**
 * The Firebase project the friends mailbox talks to, as this build was given it.
 *
 * Both values are public identifiers that ship inside every copy of the app, like
 * `google_web_client_id`. Blank means the build was made without a Firebase project, and every
 * mailbox screen says so rather than failing at its first request.
 */
class MailboxConfig(
    val apiKey: String,
    val projectId: String,
    val androidPackage: String,
    /** SHA-1 of the signing certificate, for an Android-restricted API key. Null if unreadable. */
    val androidCertSha1: String?
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && projectId.isNotBlank()

    companion object {
        fun from(context: Context): MailboxConfig {
            val app = context.applicationContext
            return MailboxConfig(
                apiKey = app.getString(R.string.firebase_api_key).trim(),
                projectId = app.getString(R.string.firebase_project_id).trim(),
                androidPackage = app.packageName,
                androidCertSha1 = signingCertSha1(app)
            )
        }

        /**
         * What Google checks an Android-restricted API key against. The Firebase SDK sends it for
         * itself; over plain REST, that is this app's job.
         */
        private fun signingCertSha1(context: Context): String? = runCatching {
            val info = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                .uppercase()
        }.getOrNull()
    }
}
