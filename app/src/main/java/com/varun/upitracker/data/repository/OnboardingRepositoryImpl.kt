package com.varun.upitracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.sms.SmsBacklogScanner
import com.varun.upitracker.ui.onboarding.AccountInput
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit

interface OnboardingRepository {
    suspend fun saveAccounts(accounts: List<AccountInput>)
    suspend fun scanSmsBacklog()
    fun markOnboardingComplete()
}

class DefaultOnboardingRepository(context: Context) : OnboardingRepository {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(
        SmsBacklogScanner.PREF_NAME,
        Context.MODE_PRIVATE
    )

    override suspend fun saveAccounts(accounts: List<AccountInput>) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                accounts.forEach { input ->
                    val label = input.label.trim()
                    if (label.isEmpty()) return@forEach

                    val accountId = UUID.randomUUID().toString()
                    db.accountDao().insert(
                        Account(
                            id = accountId,
                            type = input.type,
                            label = label,
                            addedEpoch = System.currentTimeMillis()
                        )
                    )

                    db.balanceSnapshotDao().insert(
                        BalanceSnapshot(
                            id = UUID.randomUUID().toString(),
                            accountId = accountId,
                            snapshotEpoch = input.snapshotEpoch,
                            balancePaise = input.initialBalancePaise,
                            source = BalanceSnapshotSource.MANUAL,
                            notes = null
                        )
                    )
                }
            }
        }
    }

    override suspend fun scanSmsBacklog() {
        withContext(Dispatchers.IO) {
            SmsBacklogScanner(appContext).scan()
        }
    }

    override fun markOnboardingComplete() {
        prefs.edit { putBoolean(ONBOARDING_COMPLETE_KEY, true) }
    }

    private companion object {
        const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
    }
}
