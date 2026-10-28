package com.varun.upitracker.ui.settings

/**
 * The suggestion email, kept apart from the screen so what lands in the inbox can be checked
 * without a device.
 *
 * The app has no server of its own, so a suggestion goes out through the user's own mail app: they
 * see exactly what is sent and from which address, and the app holds no mail credentials.
 */
object FeedbackEmail {

    const val RECIPIENT = "dhanmoney.feedback@gmail.com"
    const val SUBJECT = "App suggestion"

    /** What the user can send: anything but whitespace. */
    fun canSend(suggestion: String): Boolean = suggestion.isNotBlank()

    /**
     * The suggestion first, then the version and device below a rule, so a reply can say whether
     * the build it was written against already has the fix.
     */
    fun body(suggestion: String, appVersion: String, androidVersion: String, device: String): String =
        suggestion.trim() + "\n\n" +
            "--\n" +
            "App version: $appVersion\n" +
            "Android: $androidVersion\n" +
            "Device: $device\n"
}
