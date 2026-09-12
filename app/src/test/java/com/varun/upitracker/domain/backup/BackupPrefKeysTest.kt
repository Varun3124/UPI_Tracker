package com.varun.upitracker.domain.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPrefKeysTest {

    @Test
    fun `the correctness-critical keys are backed up`() {
        // Losing any of these is a data bug, not a cosmetic one: the parcel origin tokens cause
        // duplicate rows on a friend's device, and the backfill watermarks cause already-reviewed
        // transactions to be flipped back to pending on next launch.
        listOf(
            "parcel_origin_7",
            "parcel_origin_412",
            "category_split_backfill_v1_done",
            "merchant_credit_review_backfill_v1_done",
            "fd_opening_snapshot_backfill_v1_done",
            "onboarding_complete",
            "trends_account_scope"
        ).forEach { key ->
            assertTrue(key, BackupPrefKeys.isBackedUp(key))
        }
    }

    @Test
    fun `sync bookkeeping never travels`() {
        // Restoring another device's revision or id would make this phone believe it had already
        // uploaded work it has not.
        listOf(
            BackupPrefKeys.DEVICE_ID,
            BackupPrefKeys.REVISION,
            BackupPrefKeys.LAST_SYNCED_REVISION,
            BackupPrefKeys.LAST_SYNCED_DEVICE_ID,
            BackupPrefKeys.LAST_SUCCESS_EPOCH,
            BackupPrefKeys.DRIVE_FILE_ID,
            BackupPrefKeys.ACCOUNT_EMAIL
        ).forEach { key ->
            assertFalse(key, BackupPrefKeys.isBackedUp(key))
        }
    }

    @Test
    fun `every internal key sits under the one prefix`() {
        // The denylist is only safe to reason about if there is exactly one thing to remember.
        listOf(
            BackupPrefKeys.DEVICE_ID,
            BackupPrefKeys.REVISION,
            BackupPrefKeys.LAST_SYNCED_REVISION,
            BackupPrefKeys.LAST_SYNCED_DEVICE_ID,
            BackupPrefKeys.LAST_SUCCESS_EPOCH,
            BackupPrefKeys.DRIVE_FILE_ID,
            BackupPrefKeys.ACCOUNT_EMAIL
        ).forEach { key ->
            assertTrue(key, key.startsWith(BackupPrefKeys.INTERNAL_PREFIX))
        }
    }

    @Test
    fun `an unknown future key is carried by default`() {
        // The point of a denylist: the next feature's preference is backed up without anyone
        // remembering to add it.
        assertTrue(BackupPrefKeys.isBackedUp("some_feature_we_have_not_written_yet"))
    }
}
