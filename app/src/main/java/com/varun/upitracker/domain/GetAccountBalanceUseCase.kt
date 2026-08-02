package com.varun.upitracker.domain

import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase

class GetAccountBalanceUseCase(
    db: AppDatabase,
    private val repository: AccountRepository = AccountRepository(db)
) {
    suspend operator fun invoke(accountId: String, atEpoch: Long = System.currentTimeMillis()): Long {
        return repository.getBalance(accountId, atEpoch)
    }
}
