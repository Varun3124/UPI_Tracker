package com.varun.upitracker.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requirement test: signing in must never cost the user data they already had.
 *
 * Every case below is a way that could happen. The two invariants at the bottom are the ones worth
 * breaking the build over.
 */
class BackupPolicyTest {

    private val ourDevice = "device-ours"
    private val theirDevice = "device-theirs"
    private val appSchema = 15

    private val untouched = LocalBackupState(0, 0, 0, 0, 0)
    private val seededOnly = untouched // categories are seeded on install and deliberately don't count
    private val populated = LocalBackupState(
        transactionCount = 412, accountCount = 3, friendCount = 11,
        transferCount = 4, snapshotCount = 6
    )

    private fun remote(
        deviceId: String = theirDevice,
        revision: Long = 5L,
        schemaVersion: Int = 15
    ) = RemoteBackupInfo(
        fileId = "file-1",
        schemaVersion = schemaVersion,
        createdAtEpoch = 1_700_000_000_000L,
        deviceId = deviceId,
        revision = revision,
        transactionCount = 380,
        accountCount = 2
    )

    // --- isUntouched ----------------------------------------------------------------------------

    @Test
    fun `a database holding only seeded categories counts as untouched`() {
        assertTrue(seededOnly.isUntouched)
    }

    @Test
    fun `any one of the five tables makes it touched`() {
        assertFalse(untouched.copy(transactionCount = 1).isUntouched)
        assertFalse(untouched.copy(accountCount = 1).isUntouched)
        assertFalse(untouched.copy(friendCount = 1).isUntouched)
        assertFalse(untouched.copy(transferCount = 1).isUntouched)
        assertFalse(untouched.copy(snapshotCount = 1).isUntouched)
    }

    // --- upload ---------------------------------------------------------------------------------

    @Test
    fun `first upload from a phone with data is safe`() {
        assertEquals(
            BackupDecision.SafeUpload,
            BackupPolicy.decideUpload(populated, remote = null, lastSyncedRevision = 0L, ourDeviceId = ourDevice)
        )
    }

    @Test
    fun `replacing our own backup is safe`() {
        assertEquals(
            BackupDecision.SafeUpload,
            BackupPolicy.decideUpload(populated, remote(deviceId = ourDevice, revision = 9L), 3L, ourDevice)
        )
    }

    @Test
    fun `a foreign backup we have already synced past is ours to replace`() {
        assertEquals(
            BackupDecision.SafeUpload,
            BackupPolicy.decideUpload(populated, remote(revision = 5L), lastSyncedRevision = 5L, ourDeviceId = ourDevice)
        )
    }

    @Test
    fun `a foreign backup newer than our last sync is never overwritten`() {
        val decision = BackupPolicy.decideUpload(populated, remote(revision = 9L), 5L, ourDevice)
        assertTrue("$decision", decision is BackupDecision.UploadWouldOverwriteNewer)
    }

    @Test
    fun `an empty install never uploads over a real backup`() {
        // The likeliest accident of all: a fresh install signs in and the opportunistic backup fires
        // before the user has restored. That would replace their history with nothing.
        val decision = BackupPolicy.decideUpload(untouched, remote(), lastSyncedRevision = 99L, ourDeviceId = ourDevice)
        assertTrue("$decision", decision is BackupDecision.UploadWouldOverwriteNewer)
    }

    @Test
    fun `an empty install with no backup has simply nothing to do`() {
        val decision = BackupPolicy.decideUpload(untouched, remote = null, lastSyncedRevision = 0L, ourDeviceId = ourDevice)
        assertTrue("$decision", decision is BackupDecision.Nothing)
    }

    // --- restore --------------------------------------------------------------------------------

    @Test
    fun `restoring onto an untouched install risks nothing`() {
        val decision = BackupPolicy.decideRestore(untouched, remote(), appSchema)
        assertTrue("$decision", decision is BackupDecision.SafeRestore)
    }

    @Test
    fun `restoring over real data reports what is at stake`() {
        val decision = BackupPolicy.decideRestore(populated, remote(), appSchema)
        assertTrue("$decision", decision is BackupDecision.RestoreWouldReplaceLocal)
        decision as BackupDecision.RestoreWouldReplaceLocal
        // The dialog has to be able to say "this phone has 412, the backup has 380".
        assertEquals(412, decision.local.transactionCount)
        assertEquals(380, decision.remote.transactionCount)
    }

    @Test
    fun `a backup from a newer schema is refused, not attempted`() {
        val decision = BackupPolicy.decideRestore(populated, remote(schemaVersion = 16), appSchema)
        assertTrue("$decision", decision is BackupDecision.Incompatible)
    }

    @Test
    fun `a backup older than the oldest migration is refused`() {
        // AppDatabase registers no migration below 8, so Room could not open it.
        val decision = BackupPolicy.decideRestore(populated, remote(schemaVersion = 7), appSchema)
        assertTrue("$decision", decision is BackupDecision.Incompatible)
        assertTrue(BackupPolicy.decideRestore(populated, remote(schemaVersion = 8), appSchema) !is BackupDecision.Incompatible)
    }

    @Test
    fun `an older but supported schema restores`() {
        // A v14 backup into a v15 app: columns added since take their defaults.
        val decision = BackupPolicy.decideRestore(populated, remote(schemaVersion = 14), appSchema)
        assertTrue("$decision", decision is BackupDecision.RestoreWouldReplaceLocal)
    }

    @Test
    fun `restoring with no backup is nothing to do`() {
        assertTrue(BackupPolicy.decideRestore(populated, null, appSchema) is BackupDecision.Nothing)
    }

    @Test
    fun `incompatibility is judged before local state`() {
        // An untouched install must not get a SafeRestore for a file it cannot read.
        assertTrue(BackupPolicy.decideRestore(untouched, remote(schemaVersion = 99), appSchema) is BackupDecision.Incompatible)
    }

    // --- the invariants -------------------------------------------------------------------------

    @Test
    fun `no restore verdict ever means do it without asking`() {
        // SafeRestore is "you have nothing to lose", not "go ahead silently". There is deliberately
        // no decision that authorises an unattended restore, and there must never be one.
        val decisions = listOf(untouched, populated).flatMap { local ->
            listOf(null, remote(), remote(schemaVersion = 16), remote(schemaVersion = 7)).map { r ->
                BackupPolicy.decideRestore(local, r, appSchema)
            }
        }
        assertTrue(decisions.isNotEmpty())
        decisions.forEach { decision ->
            assertTrue(
                "$decision",
                decision is BackupDecision.SafeRestore ||
                    decision is BackupDecision.RestoreWouldReplaceLocal ||
                    decision is BackupDecision.Incompatible ||
                    decision is BackupDecision.Nothing
            )
        }
    }

    @Test
    fun `an upload is never silently allowed over work we have not seen`() {
        // Sweep every revision relationship; a foreign backup ahead of our watermark must never be
        // SafeUpload, whatever the local state.
        for (remoteRevision in 0L..6L) {
            for (syncedRevision in 0L..6L) {
                val decision = BackupPolicy.decideUpload(
                    populated, remote(deviceId = theirDevice, revision = remoteRevision), syncedRevision, ourDevice
                )
                if (remoteRevision > syncedRevision) {
                    assertTrue(
                        "remote=$remoteRevision synced=$syncedRevision gave $decision",
                        decision is BackupDecision.UploadWouldOverwriteNewer
                    )
                } else {
                    assertEquals(BackupDecision.SafeUpload, decision)
                }
            }
        }
    }
}
