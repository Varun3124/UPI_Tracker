package com.varun.upitracker.domain.parcel

/**
 * The two things a person says about themselves for their friends' apps: a name to be shown as, and
 * optionally the UPI ID they pay from. Both travel in every parcel they paste, which is how the
 * reader's app knows who sent it without being told.
 *
 * Only the rules live here, so the parcel grammar and the screens that ask for them agree on what is
 * acceptable. Where they are kept is [com.varun.upitracker.data.prefs.SelfIdentity].
 */
object SenderIdentity {

    const val MAX_ALIAS = 40

    /**
     * `handle@psp`. Hyphens and dots are ordinary in a handle (`9023631695-2@ybl`); the provider part is
     * letters and digits only. Matched after lowercasing, since banks print the same ID in either case.
     */
    private val UPI_ID = Regex("[a-z0-9][a-z0-9._-]{0,255}@[a-z][a-z0-9]{1,63}")

    /** The alias as it will be kept, or null when there is nothing usable in [raw]. */
    fun normaliseAlias(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() && it.length <= MAX_ALIAS }

    /** The UPI ID as it will be kept and compared, or null when [raw] does not look like one. */
    fun normaliseUpiId(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { UPI_ID.matches(it) }
}
