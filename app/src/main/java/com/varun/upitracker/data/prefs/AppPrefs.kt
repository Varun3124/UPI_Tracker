package com.varun.upitracker.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.varun.upitracker.sms.SmsBacklogScanner

/**
 * The app's one preferences file, and the names of the keys more than one file needs to agree on.
 *
 * Written because [ONBOARDING_COMPLETE] was spelled twice: once as a private constant in
 * [com.varun.upitracker.data.repository.DefaultOnboardingRepository], which writes it, and once as a
 * bare string literal in [com.varun.upitracker.MainActivity], which reads it. Backup and restore
 * makes a third reader, and three independent spellings of the key that decides whether a user sees
 * onboarding is one too many.
 *
 * Keys owned by a single file stay private to that file -- there is nothing to coordinate.
 */
object AppPrefs {

    /**
     * The file name still lives on [SmsBacklogScanner] because every existing caller reads it from
     * there. Aliased rather than re-declared so the two can never drift apart.
     */
    const val FILE_NAME: String = SmsBacklogScanner.PREF_NAME

    /** Whether the user has finished onboarding. The only gate between launch and the dashboard. */
    const val ONBOARDING_COMPLETE: String = "onboarding_complete"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
