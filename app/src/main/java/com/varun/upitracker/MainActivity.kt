package com.varun.upitracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.maintenance.CategorySplitBackfill
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.dashboard.DashboardActivity
import com.varun.upitracker.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)

        if (onboardingDone) {
            startActivity(Intent(this, DashboardActivity::class.java))
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Bare scope, not lifecycleScope: this activity finishes immediately below,
        // and the backfill must survive that to finish scanning existing transactions.
        CoroutineScope(Dispatchers.IO).launch {
            CategorySplitBackfill(applicationContext).run()
        }

        finish()
    }
}