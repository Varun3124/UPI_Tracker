package com.varun.upitracker.domain

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.domain.model.NetWorthSummary

class GetNetWorthSummaryUseCase(
    private val db: AppDatabase,
    private val getAccountBalance: GetAccountBalanceUseCase = GetAccountBalanceUseCase(db)
) {
    suspend operator fun invoke(atEpoch: Long = System.currentTimeMillis()): NetWorthSummary {
        val accounts = db.accountDao().getAllSync()
        suspend fun totalFor(predicate: (Account) -> Boolean): Long {
            return accounts.filter(predicate).sumOf { getAccountBalance(it.id, atEpoch) }
        }

        val liquid = totalFor { it.type == AccountType.CASH || it.type == AccountType.SAVINGS }
        val uninvested = totalFor { it.type == AccountType.INVESTMENT_UNINVESTED }
        val invested = totalFor { it.type == AccountType.INVESTMENT_INVESTED }
        val fdTotal = totalFor { it.type == AccountType.FD }
        val withMe = liquid + fdTotal + uninvested
        val unsettledIou = db.iouDao().getTotalUnsettledBalance() ?: 0L
        return NetWorthSummary(
            liquidAssetsPaise = liquid,
            uninvestedPaise = uninvested,
            investedPaise = invested,
            fixedDepositPaise = fdTotal,
            withMePaise = withMe,
            unsettledIouPaise = unsettledIou,
            totalPaise = withMe + invested + unsettledIou
        )
    }
}
