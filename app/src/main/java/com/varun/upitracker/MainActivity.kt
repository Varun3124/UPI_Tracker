package com.varun.upitracker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.data.prefs.AppPrefs
import com.varun.upitracker.maintenance.CategorySplitBackfill
import com.varun.upitracker.maintenance.FixedDepositSnapshotBackfill
import com.varun.upitracker.maintenance.IouRecoveryBackfill
import com.varun.upitracker.maintenance.MailboxCollection
import com.varun.upitracker.maintenance.MailboxSchedule
import com.varun.upitracker.maintenance.MerchantCreditReviewBackfill
import com.varun.upitracker.maintenance.OpportunisticBackup
import com.varun.upitracker.ui.dashboard.DashboardActivity
import com.varun.upitracker.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPrefs.of(this)
        val onboardingDone = prefs.getBoolean(AppPrefs.ONBOARDING_COMPLETE, false)

        if (onboardingDone) {
            startActivity(Intent(this, DashboardActivity::class.java))
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Bare scope, not lifecycleScope: this activity finishes immediately below,
        // and the backfill must survive that to finish scanning existing transactions.
        CoroutineScope(Dispatchers.IO).launch {
            CategorySplitBackfill(applicationContext).run()
            MerchantCreditReviewBackfill(applicationContext).run()
            FixedDepositSnapshotBackfill(applicationContext).run()
            IouRecoveryBackfill(applicationContext).run()
            // Last, and deliberately so: the backfills above write to the database, and a backup taken
            // while they were still running would capture a half-migrated state.
            OpportunisticBackup(applicationContext).run()
            // After the backup for the same reason: collecting writes to the database too, and what
            // arrives now belongs in the next backup rather than half in this one.
            MailboxCollection(applicationContext).run()
            // And keep collecting while the app is closed, or stop if the mailbox has been turned off.
            MailboxSchedule.applyTo(applicationContext)
        }

        finish()
    }
}
