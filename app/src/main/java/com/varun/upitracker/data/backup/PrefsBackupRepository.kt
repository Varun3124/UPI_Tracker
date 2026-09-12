package com.varun.upitracker.data.backup

import android.content.Context
import android.util.Log
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.domain.backup.BackupFormat
import com.varun.upitracker.domain.backup.BackupPref
import com.varun.upitracker.domain.backup.BackupPrefKeys
import com.varun.upitracker.domain.backup.PrefType

/**
 * Carries the app's preferences in and out of a backup.
 *
 * Not an optional extra alongside the database. Three of this app's preferences are correctness
 * state, and a database-only backup would be a data bug rather than an inconvenience:
 *  - `parcel_origin_<friendId>` is the token behind `Transaction.sharedRefId`. Lose it and the next
 *    parcel shared with that friend arrives on their device as duplicates of rows they already have.
 *  - the three `*_backfill_v1_done` watermarks. Lose them and the backfills re-run on the next
 *    launch and flip already-reviewed transactions back to pending.
 *
 * What travels is decided by [BackupPrefKeys.isBackedUp] -- a denylist, so a preference added by some
 * future feature is carried without anybody having to remember it.
 */
class PrefsBackupRepository(context: Context) {

    private companion object {
        private const val TAG = "PrefsBackup"
    }

    private val prefs = AppPrefs.of(context)

    fun dump(): List<BackupPref> = prefs.all
        .filterKeys(BackupPrefKeys::isBackedUp)
        .mapNotNull { (key, value) -> encode(key, value) }
        .sortedBy { it.key }

    private fun encode(key: String, value: Any?): BackupPref? = when (value) {
        is Boolean -> BackupPref(key, PrefType.BOOLEAN, value.toString())
        is Long -> BackupPref(key, PrefType.LONG, value.toString())
        is Int -> BackupPref(key, PrefType.INT, value.toString())
        is Float -> BackupPref(key, PrefType.FLOAT, value.toString())
        is String -> BackupPref(key, PrefType.STRING, value)
        is Set<*> -> BackupPref(
            key,
            PrefType.STRING_SET,
            value.filterIsInstance<String>().joinToString(BackupFormat.SET_DELIMITER)
        )
        else -> {
            // Nothing in this app stores anything else, but a silently dropped preference is worse
            // than a logged one.
            Log.w(TAG, "Skipping preference '$key' of unsupported type ${value?.javaClass?.name}")
            null
        }
    }

    /**
     * Replaces the backed-up preferences with [entries].
     *
     * Keys under [BackupPrefKeys.INTERNAL_PREFIX] are left exactly as they are, on both sides of the
     * copy: this phone's own sync bookkeeping describes this phone. Restoring another device's
     * revision would make this install believe it had already uploaded work it has not.
     *
     * Existing backed-up keys are cleared first so a restore leaves the same state a fresh install
     * plus this backup would have, rather than a merge of two devices' settings.
     */
    fun restore(entries: List<BackupPref>) {
        val editor = prefs.edit()
        prefs.all.keys.filter(BackupPrefKeys::isBackedUp).forEach(editor::remove)

        entries.forEach { entry ->
            if (!BackupPrefKeys.isBackedUp(entry.key)) {
                Log.w(TAG, "Backup tried to set the internal preference '${entry.key}'; ignored.")
                return@forEach
            }
            when (entry.type) {
                PrefType.BOOLEAN -> editor.putBoolean(entry.key, entry.value.toBoolean())
                PrefType.LONG -> entry.value.toLongOrNull()?.let { editor.putLong(entry.key, it) }
                PrefType.INT -> entry.value.toIntOrNull()?.let { editor.putInt(entry.key, it) }
                PrefType.FLOAT -> entry.value.toFloatOrNull()?.let { editor.putFloat(entry.key, it) }
                PrefType.STRING -> editor.putString(entry.key, entry.value)
                PrefType.STRING_SET -> editor.putStringSet(
                    entry.key,
                    // An empty stored set is an empty set, not a set holding one empty string.
                    entry.value.split(BackupFormat.SET_DELIMITER).filter { it.isNotEmpty() }.toSet()
                )
            }
        }
        // Committed synchronously: the caller relaunches the app straight after a restore, and an
        // apply() racing that could lose the whole write.
        editor.commit()
    }
}
