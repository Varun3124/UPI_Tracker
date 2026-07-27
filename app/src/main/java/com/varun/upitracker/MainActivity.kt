package com.varun.upitracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.dashboard.DashboardActivity
import com.varun.upitracker.ui.onboarding.OnboardingActivity

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
        finish()
    }
}