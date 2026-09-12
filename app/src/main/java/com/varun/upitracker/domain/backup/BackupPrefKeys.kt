package com.varun.upitracker.domain.backup

/**
 * Which preferences travel in a backup, and which describe only this phone.
 *
 * A **denylist**, deliberately, not an allowlist. An allowlist is the safer-looking choice and is the
 * wrong one: the next feature to store a preference would be silently left out of every backup, and
 * nobody would notice until a restore. A denylist means new preferences are carried by default and
 * only sync bookkeeping has to be remembered -- and that all lives under one prefix, right here.
 *
 * The stakes are not cosmetic. Three of this app's preferences are correctness state:
 *  - `parcel_origin_<friendId>` is the origin token behind `Transaction.sharedRefId`. Lose it and the
 *    next parcel shared with that friend mints a fresh token, so every row they already imported
 *    arrives again as a duplicate.
 *  - the three `*_backfill_v1_done` watermarks. Lose them and the backfills re-run on next launch and
 *    flip already-reviewed transactions back to `isPending = true`.
 */
object BackupPrefKeys {

    /**
     * Everything this install knows about syncing, which is meaningless on another device and
     * actively harmful if restored: a restored revision or device id would make this phone believe
     * it had already uploaded work it has not.
     */
    const val INTERNAL_PREFIX = "backup_"

    const val DEVICE_ID = INTERNAL_PREFIX + "device_id"
    const val REVISION = INTERNAL_PREFIX + "revision"
    const val LAST_SYNCED_REVISION = INTERNAL_PREFIX + "last_synced_revision"
    const val LAST_SYNCED_DEVICE_ID = INTERNAL_PREFIX + "last_synced_device_id"
    const val LAST_SUCCESS_EPOCH = INTERNAL_PREFIX + "last_success_epoch"
    const val DRIVE_FILE_ID = INTERNAL_PREFIX + "drive_file_id"
    const val ACCOUNT_EMAIL = INTERNAL_PREFIX + "account_email"

    /** True when [key] belongs in a backup. */
    fun isBackedUp(key: String): Boolean = !key.startsWith(INTERNAL_PREFIX)
}
