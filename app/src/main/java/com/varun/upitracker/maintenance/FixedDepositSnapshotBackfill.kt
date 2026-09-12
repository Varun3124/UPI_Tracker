package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.sms.SmsBacklogScanner
import kotlinx.coroutines.runBlocking
import java.util.UUID

private const val TAG = "FdSnapshotBackfill"

/**
 * Gives every fixed deposit the opening snapshot it should always have had.
 *
 * A deposit is the one account whose starting balance is never in doubt: it is the principal, on
 * the day it was booked, by definition. Every other account type gets a snapshot when it is
 * created, but FDs were made through [com.varun.upitracker.data.repository.AccountRepository.createFixedDeposit],
 * which recorded the booking transfer and left the balance to be derived from it. That left them
 * looking unreconciled forever, which under
 * [com.varun.upitracker.domain.BalanceConfidence] would mark every total containing an FD as
 * speculation for all time -- and wrongly, because the principal is the one figure nobody is
 * guessing at.
 *
 * Dated at the booking instant, not before it: the FD_BOOKING transfer carries the same epoch, and
 * `getBalance` sums `(snapshotEpoch, atEpoch]`, so the transfer falls outside the window and the
 * principal is counted exactly once.
 *
 * Guarded by [PREF_DONE], and skips any deposit that already has a snapshot of its own, so a user
 * who reconciled one by hand keeps their own figure.
 */
class FixedDepositSnapshotBackfill(private val context: Context) {

    companion object {
        const val PREF_NAME = SmsBacklogScanner.PREF_NAME
        const val PREF_DONE = "fd_opening_snapshot_backfill_v1_done"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun run() {
        if (prefs.getBoolean(PREF_DONE, false)) return

        val db = AppDatabase.getInstance(context)
        val details = db.fixedDepositDao().getAllSync()
        val alreadyReconciled = db.balanceSnapshotDao().getAccountIdsWithSnapshots().toSet()
        var added = 0

        db.runInTransaction {
            runBlocking {
                details.forEach { detail ->
                    if (detail.accountId in alreadyReconciled) return@forEach
                    // A detail row can outlive its account only if the FK was bypassed, but the
                    // snapshot's own FK is RESTRICT and would throw rather than skip.
                    if (db.accountDao().getById(detail.accountId) == null) return@forEach

                    db.balanceSnapshotDao().insert(
                        BalanceSnapshot(
                            id = UUID.randomUUID().toString(),
                            accountId = detail.accountId,
                            snapshotEpoch = detail.bookedEpoch,
                            balancePaise = detail.principalPaise,
                            source = BalanceSnapshotSource.MANUAL,
                            notes = "Principal at booking"
                        )
                    )
                    added++
                }
            }
        }

        prefs.edit().putBoolean(PREF_DONE, true).apply()
        Log.d(TAG, "FD opening snapshots complete: added=$added of deposits=${details.size}")
    }
}
