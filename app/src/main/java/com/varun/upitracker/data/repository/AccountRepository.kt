package com.varun.upitracker.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.DefaultAccounts
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.FixedDepositDetail
import com.varun.upitracker.database.entity.FixedDepositStatus
import java.util.UUID

class AccountRepository(private val db: AppDatabase) {

    fun observeActiveAccounts(): LiveData<List<Account>> = db.accountDao().observeActiveAccounts()

    suspend fun getTransactionAccounts(): List<Account> {
        ensureDefaultAccounts()
        return db.accountDao().getActiveByTypes(listOf(AccountType.CASH, AccountType.SAVINGS))
    }

    suspend fun getAccount(id: String): Account? = db.accountDao().getById(id)

    suspend fun ensureDefaultAccounts() {
        val now = System.currentTimeMillis()
        db.accountDao().insertAll(
            listOf(
                Account(DefaultAccounts.CASH_ID, AccountType.CASH, "Cash", now),
                Account(DefaultAccounts.SAVINGS_ID, AccountType.SAVINGS, "Savings", now)
            )
        )
    }

    suspend fun recordTransfer(transfer: AccountTransfer): Boolean {
        transfer.statementRefNo?.let { ref ->
            if (db.accountTransferDao().findByStatementRefNo(ref) != null) return false
        }
        db.withTransaction {
            db.accountTransferDao().insert(transfer)
            if (transfer.type == AccountTransferType.FD_RETURN && transfer.fromAccountId != null) {
                updateFixedDepositOnReturn(transfer)
            }
        }
        return true
    }

    suspend fun bookFixedDeposit(
        accountId: String,
        label: String,
        sourceAccountId: String,
        principalPaise: Long,
        bookedEpoch: Long,
        maturityEpoch: Long,
        source: EntrySource,
        statementRefNo: String? = null
    ) {
        db.withTransaction {
            db.accountDao().insert(
                Account(
                    id = accountId,
                    type = AccountType.FD,
                    label = label,
                    addedEpoch = bookedEpoch
                )
            )
            db.fixedDepositDao().insert(
                FixedDepositDetail(
                    accountId = accountId,
                    principalPaise = principalPaise,
                    sourceAccountId = sourceAccountId,
                    bookedEpoch = bookedEpoch,
                    maturityEpoch = maturityEpoch,
                    status = FixedDepositStatus.ACTIVE
                )
            )
            db.accountTransferDao().insert(
                AccountTransfer(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = sourceAccountId,
                    toAccountId = accountId,
                    amountFromPaise = principalPaise,
                    amountToPaise = principalPaise,
                    type = AccountTransferType.FD_BOOKING,
                    dateEpoch = bookedEpoch,
                    source = source,
                    statementRefNo = statementRefNo
                )
            )
        }
    }

    private suspend fun updateFixedDepositOnReturn(transfer: AccountTransfer) {
        val fdAccountId = transfer.fromAccountId ?: return
        val detail = db.fixedDepositDao().getByAccountId(fdAccountId) ?: return
        val status = if (transfer.dateEpoch >= detail.maturityEpoch) {
            FixedDepositStatus.MATURED
        } else {
            FixedDepositStatus.WITHDRAWN_PREMATURE
        }
        db.fixedDepositDao().update(detail.copy(status = status))
        db.accountDao().getById(fdAccountId)?.let { account ->
            db.accountDao().update(account.copy(isArchived = true))
        }
    }
}
