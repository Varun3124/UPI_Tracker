package com.varun.upitracker.data.mailbox

import com.varun.upitracker.domain.mailbox.PublicMailboxKeys

/**
 * Someone's keys as they published them in `/users/{uid}`, or null when there are none worth using:
 * no document, keys that do not parse, or a stored fingerprint that disagrees with the keys beside it.
 *
 * The keys decide, never the stored fingerprint -- it is there so a person can be told what to
 * compare, not so the app can skip computing it.
 */
suspend fun FirestoreClient.publishedKeys(idToken: String, uid: String): PublicMailboxKeys? {
    val document = get(idToken, "users/$uid") ?: return null
    val keys = PublicMailboxKeys.fromText(
        document.text("encKey") ?: return null,
        document.text("sigKey") ?: return null
    ) ?: return null
    val stored = document.text("fingerprint")
    return keys.takeIf { stored == null || stored == it.fingerprint }
}
