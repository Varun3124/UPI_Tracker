package com.varun.upitracker.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.FixedDepositDetail
import com.varun.upitracker.database.entity.FixedDepositStatus
import com.varun.upitracker.domain.BalanceDeltaCalculator
import com.varun.upitracker.domain.TransferDeltaInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

data class AccountCreateRequest(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: AccountType,
    val addedEpoch: Long = System.currentTimeMillis(),
    val openingBalancePaise: Long? = null,
    val isDefault: Boolean = false
)

data class FixedDepositCreateRequest(
    val accountId: String = UUID.randomUUID().toString(),
    val label: String,
    val sourceAccountId: String,
    val principalPaise: Long,
    val bookedEpoch: Long,
    val maturityEpoch: Long,
    val source: EntrySource = EntrySource.MANUAL,
    val statementRefNo: String? = null,
    val isDefault: Boolean = false
)

sealed class AccountDeleteResult {
    data object Deleted : AccountDeleteResult()
    data object Archived : AccountDeleteResult()
}

class AccountMutationException(message: String) : IllegalStateException(message)

class AccountRepository private constructor(
    private val db: AppDatabase?,
    private val balanceDataSource: AccountBalanceDataSource
) {
    constructor(db: AppDatabase) : this(db, RoomAccountBalanceDataSource(db))

    internal constructor(balanceDataSource: AccountBalanceDataSource) : this(null, balanceDataSource)

    private val database: AppDatabase
        get() = db ?: throw IllegalStateException("This repository instance only supports balance calculations.")

    fun observeAccounts(): Flow<List<Account>> = database.accountDao().getAll()

    fun observeActiveAccounts(): LiveData<List<Account>> = database.accountDao().observeActiveAccounts()

    fun observeSnapshots(accountId: String): Flow<List<BalanceSnapshot>> =
        database.balanceSnapshotDao().getForAccount(accountId)

    suspend fun getAccounts(): List<Account> = database.accountDao().getAllSync()

    suspend fun getAccount(id: String): Account? = database.accountDao().getById(id)

    suspend fun getSnapshots(accountId: String): List<BalanceSnapshot> =
        observeSnapshots(accountId).first()

    suspend fun getDefaultAccountByType(type: AccountType): Account? {
        return database.accountDao().getByType(type).firstOrNull { it.isDefault && !it.isArchived }
            ?: database.accountDao().getByType(type).firstOrNull { !it.isArchived }
    }

    suspend fun getTransactionAccounts(): List<Account> {
        return database.accountDao().getActiveByTypes(listOf(AccountType.CASH, AccountType.SAVINGS))
    }

    suspend fun setDefault(accountId: String) {
        val account = database.accountDao().getById(accountId)
            ?: throw AccountMutationException("Account not found.")
        database.accountDao().setDefault(accountId, account.type)
    }

    suspend fun getBalance(accountId: String, atEpoch: Long = System.currentTimeMillis()): Long {
        val priorSnapshot = balanceDataSource.getLatestAtOrBefore(accountId, atEpoch)
        if (priorSnapshot != null) {
            return priorSnapshot.balancePaise + sumDeltas(
                accountId = accountId,
                fromEpochExclusive = priorSnapshot.snapshotEpoch,
                toEpochInclusive = atEpoch
            )
        }

        val futureSnapshot = balanceDataSource.getEarliestAfter(accountId, atEpoch)
        if (futureSnapshot != null) {
            return futureSnapshot.balancePaise - sumDeltas(
                accountId = accountId,
                fromEpochExclusive = atEpoch,
                toEpochInclusive = futureSnapshot.snapshotEpoch
            )
        }

        return sumDeltas(accountId, Long.MIN_VALUE, atEpoch)
    }

    suspend fun createAccount(request: AccountCreateRequest): String {
        if (request.type == AccountType.FD) {
            throw AccountMutationException("Use fixed deposit creation for FD accounts.")
        }
        if (request.label.isBlank()) throw AccountMutationException("Account label is required.")

        database.withTransaction {
            database.accountDao().insert(
                Account(
                    id = request.id,
                    type = request.type,
                    label = request.label.trim(),
                    addedEpoch = request.addedEpoch,
                    isDefault = false
                )
            )
            if (request.isDefault) database.accountDao().setDefault(request.id, request.type)
            request.openingBalancePaise?.takeIf { it != 0L }?.let { balance ->
                database.balanceSnapshotDao().insert(
                    BalanceSnapshot(
                        id = UUID.randomUUID().toString(),
                        accountId = request.id,
                        snapshotEpoch = request.addedEpoch,
                        balancePaise = balance,
                        source = BalanceSnapshotSource.MANUAL,
                        notes = "Opening balance"
                    )
                )
            }
        }
        return request.id
    }

    suspend fun createFixedDeposit(request: FixedDepositCreateRequest): String {
        if (request.label.isBlank()) throw AccountMutationException("Account label is required.")
        if (request.principalPaise <= 0L) throw AccountMutationException("Principal must be greater than zero.")
        if (request.maturityEpoch <= request.bookedEpoch) {
            throw AccountMutationException("Maturity date must be after booking date.")
        }
        if (database.accountDao().getById(request.sourceAccountId) == null) {
            throw AccountMutationException("Source account not found.")
        }

        database.withTransaction {
            database.accountDao().insert(
                Account(
                    id = request.accountId,
                    type = AccountType.FD,
                    label = request.label.trim(),
                    addedEpoch = request.bookedEpoch,
                    isDefault = false
                )
            )
            if (request.isDefault) database.accountDao().setDefault(request.accountId, AccountType.FD)
            database.fixedDepositDao().insert(
                FixedDepositDetail(
                    accountId = request.accountId,
                    principalPaise = request.principalPaise,
                    sourceAccountId = request.sourceAccountId,
                    bookedEpoch = request.bookedEpoch,
                    maturityEpoch = request.maturityEpoch,
                    status = FixedDepositStatus.ACTIVE
                )
            )
            database.accountTransferDao().insert(
                AccountTransfer(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = request.sourceAccountId,
                    toAccountId = request.accountId,
                    amountFromPaise = request.principalPaise,
                    amountToPaise = request.principalPaise,
                    type = AccountTransferType.FD_BOOKING,
                    dateEpoch = request.bookedEpoch,
                    source = request.source,
                    statementRefNo = request.statementRefNo
                )
            )
        }
        return request.accountId
    }

    suspend fun updateAccountLabel(accountId: String, label: String) {
        if (label.isBlank()) throw AccountMutationException("Account label is required.")
        val account = database.accountDao().getById(accountId)
            ?: throw AccountMutationException("Account not found.")
        database.accountDao().update(account.copy(label = label.trim()))
    }

    suspend fun deleteOrArchive(accountId: String): AccountDeleteResult {
        val account = database.accountDao().getById(accountId)
            ?: throw AccountMutationException("Account not found.")
        return if (hasHistory(accountId)) {
            database.accountDao().update(account.copy(isArchived = true, isDefault = false))
            AccountDeleteResult.Archived
        } else {
            database.accountDao().delete(account)
            AccountDeleteResult.Deleted
        }
    }

    suspend fun archive(accountId: String) {
        val account = database.accountDao().getById(accountId)
            ?: throw AccountMutationException("Account not found.")
        database.accountDao().update(account.copy(isArchived = true, isDefault = false))
    }

    suspend fun addSnapshot(
        accountId: String,
        snapshotEpoch: Long,
        balancePaise: Long,
        source: BalanceSnapshotSource = BalanceSnapshotSource.MANUAL,
        notes: String? = null
    ) {
        if (database.accountDao().getById(accountId) == null) {
            throw AccountMutationException("Account not found.")
        }
        database.balanceSnapshotDao().insert(
            BalanceSnapshot(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                snapshotEpoch = snapshotEpoch,
                balancePaise = balancePaise,
                source = source,
                notes = notes?.takeIf { it.isNotBlank() }
            )
        )
    }

    suspend fun updateSnapshot(snapshot: BalanceSnapshot) {
        if (database.accountDao().getById(snapshot.accountId) == null) {
            throw AccountMutationException("Account not found.")
        }
        database.balanceSnapshotDao().update(snapshot)
    }

    suspend fun deleteSnapshot(snapshotId: String) {
        val snapshot = database.balanceSnapshotDao().getById(snapshotId)
            ?: throw AccountMutationException("Snapshot not found.")
        database.balanceSnapshotDao().delete(snapshot)
    }

    /**
     * Raw insert. Performs no validation beyond the [AccountTransfer.statementRefNo] dedupe, so
     * one-sided imports (a null `fromAccountId`/`toAccountId`) can use it. Manually entered
     * transfers should go through [upsertTransfer], which validates first.
     */
    suspend fun recordTransfer(transfer: AccountTransfer): Boolean {
        transfer.statementRefNo?.let { ref ->
            if (database.accountTransferDao().findByStatementRefNo(ref) != null) return false
        }
        database.withTransaction {
            database.accountTransferDao().insert(transfer)
            if (transfer.type == AccountTransferType.FD_RETURN && transfer.fromAccountId != null) {
                updateFixedDepositOnReturn(transfer)
            }
        }
        return true
    }

    suspend fun getTransfer(id: String): AccountTransfer? =
        database.accountTransferDao().getById(id)

    suspend fun getRecentTransfers(limit: Int): List<AccountTransfer> =
        database.accountTransferDao().getRecentTransfers(limit)

    suspend fun getTransfersBetween(fromEpoch: Long, toEpoch: Long): List<AccountTransfer> =
        database.accountTransferDao().getTransfersBetween(fromEpoch, toEpoch)

    /**
     * Inserts a new transfer or updates an existing one with the same id.
     *
     * @return false only when a duplicate `statementRefNo` caused the insert to be skipped.
     * @throws AccountMutationException if the transfer is invalid, or if it is an existing
     *   [AccountTransferType.FD_RETURN] — see [deleteTransfer] for why those are frozen.
     */
    suspend fun upsertTransfer(transfer: AccountTransfer): Boolean {
        validateTransfer(transfer)
        val existing = database.accountTransferDao().getById(transfer.id)
            ?: return recordTransfer(transfer)
        if (existing.type == AccountTransferType.FD_RETURN) {
            throw AccountMutationException("Edit FD returns from the Accounts screen.")
        }
        database.accountTransferDao().update(transfer)
        return true
    }

    /**
     * Replaces a pending [com.varun.upitracker.database.entity.Transaction] with an
     * [AccountTransfer] in one step, for a reviewer classifying an SMS or bank-statement row that
     * turned out to be a movement between their own accounts.
     *
     * The transaction's shares, category splits and IOU entries are all `ON DELETE CASCADE` on
     * `transactions.id`; they are removed explicitly anyway so the cleanup does not depend on the
     * foreign-keys pragma being on.
     *
     * @throws AccountMutationException if the transfer is invalid, or if its `statementRefNo` is
     *   already held by another transfer.
     */
    suspend fun convertTransactionToTransfer(transactionId: Long, transfer: AccountTransfer) {
        validateTransfer(transfer)
        transfer.statementRefNo?.let { ref ->
            if (database.accountTransferDao().findByStatementRefNo(ref) != null) {
                throw AccountMutationException("A transfer for this statement row already exists.")
            }
        }
        database.withTransaction {
            database.iouDao().deleteForTransaction(transactionId)
            database.categorySplitDao().deleteForTransaction(transactionId)
            database.transactionShareDao().deleteForTransaction(transactionId)
            database.transactionDao().deleteById(transactionId)
            database.accountTransferDao().insert(transfer)
        }
    }

    /**
     * Deletes a transfer. Balances are derived, so removing the row is the entire reversal.
     *
     * An [AccountTransferType.FD_RETURN] cannot be deleted here: [recordTransfer] archived the FD
     * account and marked its detail matured/withdrawn on the way in, and neither is reversible
     * without knowing the deposit's prior status.
     */
    suspend fun deleteTransfer(id: String) {
        val existing = database.accountTransferDao().getById(id)
            ?: throw AccountMutationException("Transfer not found.")
        if (existing.type == AccountTransferType.FD_RETURN) {
            throw AccountMutationException("Delete FD returns from the Accounts screen.")
        }
        database.accountTransferDao().deleteById(id)
    }

    /**
     * Stricter than the entity, whose nullable account ids exist for one-sided imports. Source and
     * destination may match, and one leg may be zero, so that a credit or charge against a single
     * account (monthly savings interest, an account fee) is representable.
     */
    private suspend fun validateTransfer(transfer: AccountTransfer) {
        val from = transfer.fromAccountId
            ?: throw AccountMutationException("Source account is required.")
        val to = transfer.toAccountId
            ?: throw AccountMutationException("Destination account is required.")
        if (transfer.amountFromPaise < 0L || transfer.amountToPaise < 0L) {
            throw AccountMutationException("Transfer amounts can't be negative.")
        }
        if (transfer.amountFromPaise == 0L && transfer.amountToPaise == 0L) {
            throw AccountMutationException("Transfer amounts must be greater than zero.")
        }
        if (database.accountDao().getById(from) == null) {
            throw AccountMutationException("Source account not found.")
        }
        if (database.accountDao().getById(to) == null) {
            throw AccountMutationException("Destination account not found.")
        }
    }

    /**
     * Total spend since [fromEpoch], as the negated sum of every account's balance delta. Transfers
     * contribute automatically via [sumDeltas]: equal legs net to zero, a fee shows up as spend.
     */
    suspend fun getSpendSince(fromEpoch: Long): Long =
        -database.accountDao().getAllSync().sumOf { account ->
            sumDeltas(account.id, fromEpoch, Long.MAX_VALUE)
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
        createFixedDeposit(
            FixedDepositCreateRequest(
                accountId = accountId,
                label = label,
                sourceAccountId = sourceAccountId,
                principalPaise = principalPaise,
                bookedEpoch = bookedEpoch,
                maturityEpoch = maturityEpoch,
                source = source,
                statementRefNo = statementRefNo
            )
        )
    }

    private suspend fun hasHistory(accountId: String): Boolean {
        val dao = database.accountDao()
        return dao.countTransactionsForAccount(accountId) > 0 ||
            dao.countTransfersForAccount(accountId) > 0 ||
            dao.countSnapshotsForAccount(accountId) > 0 ||
            dao.countFixedDepositReferencesForAccount(accountId) > 0
    }

    private suspend fun updateFixedDepositOnReturn(transfer: AccountTransfer) {
        val fdAccountId = transfer.fromAccountId ?: return
        val detail = database.fixedDepositDao().getByAccountId(fdAccountId) ?: return
        val status = if (transfer.dateEpoch >= detail.maturityEpoch) {
            FixedDepositStatus.MATURED
        } else {
            FixedDepositStatus.WITHDRAWN_PREMATURE
        }
        database.fixedDepositDao().update(detail.copy(status = status))
        database.accountDao().getById(fdAccountId)?.let { account ->
            database.accountDao().update(account.copy(isArchived = true, isDefault = false))
        }
    }

    private suspend fun sumDeltas(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long {
        val transactionDelta = balanceDataSource
            .getTransactionDeltaSum(accountId, fromEpochExclusive, toEpochInclusive)

        val transferDelta = balanceDataSource
            .getTransferDeltasBetween(accountId, fromEpochExclusive, toEpochInclusive)
            .sumOf { transfer -> BalanceDeltaCalculator.transferDelta(accountId, transfer) }

        return transactionDelta + transferDelta
    }
}

internal interface AccountBalanceDataSource {
    suspend fun getLatestAtOrBefore(accountId: String, atEpoch: Long): BalanceSnapshot?
    suspend fun getEarliestAfter(accountId: String, atEpoch: Long): BalanceSnapshot?

    suspend fun getTransactionDeltaSum(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long

    suspend fun getTransferDeltasBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<TransferDeltaInput>
}

private class RoomAccountBalanceDataSource(private val db: AppDatabase) : AccountBalanceDataSource {
    override suspend fun getLatestAtOrBefore(accountId: String, atEpoch: Long): BalanceSnapshot? =
        db.balanceSnapshotDao().getLatestAtOrBefore(accountId, atEpoch)

    override suspend fun getEarliestAfter(accountId: String, atEpoch: Long): BalanceSnapshot? =
        db.balanceSnapshotDao().getEarliestAfter(accountId, atEpoch)

    override suspend fun getTransactionDeltaSum(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long {
        return db.transactionDao()
            .getTotalDeltaBetweenForAccount(accountId, fromEpochExclusive, toEpochInclusive) ?: 0L
    }

    override suspend fun getTransferDeltasBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<TransferDeltaInput> {
        return db.accountTransferDao()
            .getAccountTransfersBetween(accountId, fromEpochExclusive, toEpochInclusive)
            .map { transfer ->
                TransferDeltaInput(
                    fromAccountId = transfer.fromAccountId,
                    toAccountId = transfer.toAccountId,
                    amountFromPaise = transfer.amountFromPaise,
                    amountToPaise = transfer.amountToPaise
                )
            }
    }
}
