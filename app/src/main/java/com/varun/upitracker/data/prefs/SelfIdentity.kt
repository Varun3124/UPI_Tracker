package com.varun.upitracker.data.prefs

import android.content.Context
import com.varun.upitracker.domain.parcel.SenderIdentity

/**
 * Who this person is to their friends' apps: the alias every pasted parcel carries, and optionally
 * their UPI ID. Kept in the app's preferences, so a backup carries it like any other preference.
 *
 * Validated on the way in by [SenderIdentity]; anything read back is re-checked the same way, so a
 * value written by hand or by an older build can never reach a parcel malformed.
 */
class SelfIdentity(context: Context) {

    private val prefs = AppPrefs.of(context)

    fun alias(): String? = SenderIdentity.normaliseAlias(prefs.getString(AppPrefs.SELF_ALIAS, null))

    fun upiId(): String? = SenderIdentity.normaliseUpiId(prefs.getString(AppPrefs.SELF_UPI_ID, null))

    /**
     * Saves both. [upiId] may be blank, which clears it; one that is present but malformed is refused
     * along with the alias, so the person is told rather than having it silently dropped.
     */
    fun set(alias: String, upiId: String?): Result {
        val cleanAlias = SenderIdentity.normaliseAlias(alias)
            ?: return Result.Invalid("Enter a name of up to ${SenderIdentity.MAX_ALIAS} characters.")
        val cleanUpi = if (upiId.isNullOrBlank()) {
            null
        } else {
            SenderIdentity.normaliseUpiId(upiId)
                ?: return Result.Invalid("That does not look like a UPI ID, which reads like name@bank.")
        }
        prefs.edit()
            .putString(AppPrefs.SELF_ALIAS, cleanAlias)
            .apply { if (cleanUpi == null) remove(AppPrefs.SELF_UPI_ID) else putString(AppPrefs.SELF_UPI_ID, cleanUpi) }
            .apply()
        return Result.Saved
    }

    sealed interface Result {
        data object Saved : Result
        data class Invalid(val message: String) : Result
    }
}
