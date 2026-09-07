package com.varun.upitracker.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.varun.upitracker.data.repository.AccountMutationException
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.data.repository.SettingsRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.domain.AccountTypes
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.transactionentry.EntrySide
import com.varun.upitracker.ui.transactionentry.TransactionEntryAction
import com.varun.upitracker.ui.transactionentry.TransactionEntryEffect
import com.varun.upitracker.ui.transactionentry.TransactionEntryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class TransactionEntryReferenceData(
    val friends: List<Friend> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** Accounts a normal transaction may be attributed to. */
    val accounts: List<Account> = emptyList(),
    /** Wider list used in transfer mode, where any active account is a valid endpoint. */
    val transferAccounts: List<Account> = emptyList(),
    val transaction: Transaction? = null,
    val transfer: AccountTransfer? = null
)

class TransactionEntryViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _referenceData = MutableLiveData<TransactionEntryReferenceData>()
    val referenceData: LiveData<TransactionEntryReferenceData> = _referenceData
    private val _uiState = MutableLiveData(TransactionEntryUiState())
    val uiState: LiveData<TransactionEntryUiState> = _uiState
    private val _effects = MutableLiveData<TransactionEntryEffect>()
    val effects: LiveData<TransactionEntryEffect> = _effects

    fun launchTask(task: suspend () -> Unit) {
        viewModelScope.launch {
            task()
        }
    }

    fun onAction(action: TransactionEntryAction) {
        when (action) {
            is TransactionEntryAction.AmountChanged -> {
                _uiState.value = (_uiState.value ?: TransactionEntryUiState()).copy(amountRaw = action.rawAmount)
            }

            is TransactionEntryAction.AccountSelected -> {
                val state = _uiState.value ?: TransactionEntryUiState()
                _uiState.value = if (action.side == EntrySide.PAYER) {
                    state.copy(payerAccountId = action.accountId)
                } else {
                    state.copy(payeeAccountId = action.accountId)
                }
            }

            is TransactionEntryAction.DescriptionChanged -> {
                _uiState.value = (_uiState.value ?: TransactionEntryUiState()).copy(description = action.text)
            }

            else -> Unit
        }
        _effects.value = TransactionEntryEffect.RunLegacyAction(action)
    }

    fun load(transactionId: Long?, transferId: String? = null) {
        viewModelScope.launch {
            _referenceData.value = withContext(Dispatchers.IO) {
                TransactionEntryReferenceData(
                    friends = db.friendDao().getAllFriendsByFrequency(),
                    merchants = db.merchantDao().getAllMerchantsSync(),
                    categories = db.categoryDao().getAllCategoriesSync(),
                    accounts = db.accountDao().getActiveByTypes(AccountTypes.LIQUID),
                    transferAccounts = db.accountDao().getActiveSync(),
                    transaction = transactionId?.let { db.transactionDao().getTransactionById(it) },
                    transfer = transferId?.let { db.accountTransferDao().getById(it) }
                )
            }
        }
    }
}

data class AllTransactionsUiState(
    val entries: List<LedgerEntry> = emptyList(),
    val accountLabels: Map<String, String> = emptyMap(),
    val rangeStartEpoch: Long = 0L,
    /** Exclusive, matching the `[from, to)` convention of the two range DAOs. */
    val rangeEndExclusiveEpoch: Long = 0L,
    /** True when the range came from the long-press picker rather than being a whole month. */
    val isCustomRange: Boolean = false,
    /** True when the long-press menu's "All time" choice is active. */
    val isAllTime: Boolean = false,
    val pendingOnly: Boolean = false,
    /** True when only entries where neither side of the transaction is ME should show. */
    val thirdPartyOnly: Boolean = false,
    /** Accounts offered by the filter dropdown. */
    val accounts: List<Account> = emptyList(),
    /** null means "All accounts". */
    val selectedAccountId: String? = null,
    /** Entries in the range before either filter, so the bar can show what it hides. */
    val totalEntryCount: Int = 0,
    val showBalance: Boolean = false,
    /** Combined balance of the in-scope accounts immediately before [rangeStartEpoch]. */
    val openingBalancePaise: Long = 0L,
    /** Opening plus every in-scope movement in the range. */
    val closingBalancePaise: Long = 0L,
    /** True when at least one account is in scope, so the figures mean something. */
    val hasBalance: Boolean = false,
    /** Balance standing after each entry, keyed by [stableId]. */
    val runningBalances: Map<String, Long> = emptyMap()
) {
    val isFiltered: Boolean get() = pendingOnly || thirdPartyOnly || selectedAccountId != null
}

class AllTransactionsViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val accountRepository = AccountRepository(db)
    private var rangeStartEpoch: Long = startOfMonth(Calendar.getInstance())
    private var rangeEndExclusiveEpoch: Long = nextMonth(rangeStartEpoch)
    private var isCustomRange: Boolean = false
    private var isAllTime: Boolean = false
    private var pendingOnly: Boolean = false
    private var thirdPartyOnly: Boolean = false
    private var selectedAccountId: String? = null
    private var showBalance: Boolean = false
    private var loadedEntries: List<LedgerEntry> = emptyList()
    private var loadedAccountLabels: Map<String, String> = emptyMap()
    private var loadedAccounts: List<Account> = emptyList()
    private var openingBalancePaise: Long = 0L
    private var hasBalance: Boolean = false
    private val _uiState = MutableLiveData(
        AllTransactionsUiState(
            rangeStartEpoch = rangeStartEpoch,
            rangeEndExclusiveEpoch = rangeEndExclusiveEpoch
        )
    )
    val uiState: LiveData<AllTransactionsUiState> = _uiState

    /** Reloads whatever range is showing, so a custom range survives returning to the screen. */
    fun loadCurrentMonth() {
        loadRange(rangeStartEpoch, rangeEndExclusiveEpoch, isCustomRange, isAllTime)
    }

    fun loadMonth(monthStartEpoch: Long) {
        loadRange(monthStartEpoch, nextMonth(monthStartEpoch), isCustom = false)
    }

    /**
     * Every transaction and transfer ever recorded. `rangeStartEpoch = 0` (the Unix epoch) rather
     * than [Long.MIN_VALUE] deliberately: [loadOpeningBalance] derives `rangeStartEpoch - 1`, and
     * subtracting from [Long.MIN_VALUE] would overflow. Nothing real predates 1970 anyway, and
     * `AccountRepository.getBalance` already free-falls to "assume zero" once it runs out of
     * snapshots and transactions to walk back through.
     */
    fun loadAllTime() {
        loadRange(0L, Long.MAX_VALUE, isCustom = false, isAllTime = true)
    }

    /**
     * Jumps to the next or previous whole month, anchored on the month currently in view — or on
     * today's month when the current view is "All time", where there is no sensible anchor.
     */
    fun shiftMonth(delta: Int) {
        val anchorMonthStart = if (isAllTime) {
            startOfMonth(Calendar.getInstance())
        } else {
            startOfMonth(Calendar.getInstance().apply { timeInMillis = rangeStartEpoch })
        }
        val shifted = Calendar.getInstance().apply {
            timeInMillis = anchorMonthStart
            add(Calendar.MONTH, delta)
        }.timeInMillis
        loadMonth(shifted)
    }

    /**
     * @param endExclusiveEpoch matches the `[from, to)` convention of the two range DAOs. For an
     *   inclusive To date the caller passes the start of the following day.
     */
    fun loadRange(startEpoch: Long, endExclusiveEpoch: Long, isCustom: Boolean, isAllTime: Boolean = false) {
        rangeStartEpoch = startEpoch
        rangeEndExclusiveEpoch = endExclusiveEpoch
        isCustomRange = isCustom
        this.isAllTime = isAllTime
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val transactions = db.transactionDao()
                    .getTransactionsBetweenSync(startEpoch, endExclusiveEpoch)
                    .map(LedgerEntry::Tx)
                val transfers = db.accountTransferDao()
                    .getTransfersBetween(startEpoch, endExclusiveEpoch)
                    .map(LedgerEntry::Transfer)
                loadedEntries = (transactions + transfers).sortedByDescending { it.dateEpoch }
                loadedAccountLabels = db.accountDao().getAllSync().associate { it.id to it.label }
                loadedAccounts = db.accountDao().getActiveSync()
                loadOpeningBalance()
            }
            emitState()
        }
    }

    /** Filters what is already loaded, so toggling costs no database round trip. */
    fun setPendingOnly(enabled: Boolean) {
        if (enabled == pendingOnly) return
        pendingOnly = enabled
        emitState()
    }

    /** Filters what is already loaded, so toggling costs no database round trip. */
    fun setThirdPartyOnly(enabled: Boolean) {
        if (enabled == thirdPartyOnly) return
        thirdPartyOnly = enabled
        emitState()
    }

    fun setShowBalance(enabled: Boolean) {
        if (enabled == showBalance) return
        showBalance = enabled
        emitState()
    }

    /**
     * @param accountId null to clear the filter and show every account again.
     *
     * Unlike the other filters this needs a database round trip, because changing the scope changes
     * which accounts the opening balance covers.
     */
    fun setAccountFilter(accountId: String?) {
        if (accountId == selectedAccountId) return
        selectedAccountId = accountId
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadOpeningBalance() }
            emitState()
        }
    }

    /**
     * The accounts a balance covers: the filtered one, or every active CASH and SAVINGS account
     * when the filter is on "All accounts". Investment and FD accounts are deliberately excluded
     * from the combined figure — this row is about spendable money.
     */
    private fun balanceScopeIds(): Set<String> {
        val accountId = selectedAccountId
        if (accountId != null) return setOf(accountId)
        return loadedAccounts
            .filter { AccountTypes.isLiquid(it.type) }
            .map { it.id }
            .toSet()
    }

    private suspend fun loadOpeningBalance() {
        val scope = balanceScopeIds()
        hasBalance = scope.isNotEmpty()
        // One millisecond before the range: getBalance's upper bound is inclusive, and an entry
        // dated exactly at the range start belongs inside the range, not before it.
        openingBalancePaise = scope.sumOf { accountRepository.getBalance(it, rangeStartEpoch - 1) }
    }

    private fun emitState() {
        val accountId = selectedAccountId
        val filtered = loadedEntries
            // Account transfers have no pending state, so that filter excludes them entirely.
            .filter { !pendingOnly || (it is LedgerEntry.Tx && it.transaction.isPending) }
            // Transfers never have ME as payer/payee, so this filter excludes them entirely too.
            .filter {
                !thirdPartyOnly ||
                    (it is LedgerEntry.Tx && it.transaction.amountPerspective() == AmountPerspective.NEUTRAL)
            }
            .filter { accountId == null || it.involvesAccount(accountId) }

        val scope = balanceScopeIds()
        // Accumulated over the unfiltered list: hidden entries still moved the balance.
        val balances = runningBalances(loadedEntries, scope, openingBalancePaise)

        _uiState.value = AllTransactionsUiState(
            entries = filtered,
            accountLabels = loadedAccountLabels,
            rangeStartEpoch = rangeStartEpoch,
            rangeEndExclusiveEpoch = rangeEndExclusiveEpoch,
            isCustomRange = isCustomRange,
            isAllTime = isAllTime,
            accounts = loadedAccounts,
            selectedAccountId = accountId,
            pendingOnly = pendingOnly,
            thirdPartyOnly = thirdPartyOnly,
            totalEntryCount = loadedEntries.size,
            showBalance = showBalance,
            openingBalancePaise = openingBalancePaise,
            closingBalancePaise = loadedEntries.firstOrNull()
                ?.let { balances[it.stableId()] }
                ?: openingBalancePaise,
            hasBalance = hasBalance,
            runningBalances = balances
        )
    }

    /**
     * A transaction sits on one account; a transfer touches two, and either endpoint counts as
     * involvement. A transaction with no account recorded matches no filter.
     */
    private fun LedgerEntry.involvesAccount(accountId: String): Boolean = when (this) {
        is LedgerEntry.Tx -> transaction.myAccountId == accountId
        is LedgerEntry.Transfer ->
            transfer.fromAccountId == accountId || transfer.toAccountId == accountId
    }

    fun deleteTransaction(transactionId: Long, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            // A purchase with refunds pointing at it cannot be deleted: the refund is real money
            // that arrived, and its category credit is attributed to this transaction's date.
            // Blocking beats silently dropping the refund or silently turning it into income.
            val refundCount = withContext(Dispatchers.IO) {
                db.transactionDao().getRefundIdsForOriginal(transactionId).size
            }
            if (refundCount > 0) {
                onError(
                    if (refundCount == 1) {
                        "A refund is linked to this transaction. Delete or unlink the refund first."
                    } else {
                        "$refundCount refunds are linked to this transaction. Delete or unlink them first."
                    }
                )
                return@launch
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.transactionShareDao().deleteForTransaction(transactionId)
                    db.transactionDao().deleteById(transactionId)
                }
            }
            loadCurrentMonth()
        }
    }

    fun deleteTransfer(transferId: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AccountRepository(db).deleteTransfer(transferId) }
            } catch (e: AccountMutationException) {
                onError(e.message ?: "Could not delete transfer")
                return@launch
            }
            loadCurrentMonth()
        }
    }

    private fun nextMonth(monthStartEpoch: Long): Long = Calendar.getInstance().apply {
        timeInMillis = monthStartEpoch
        add(Calendar.MONTH, 1)
    }.timeInMillis

    private fun startOfMonth(calendar: Calendar): Long {
        return calendar.apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

data class FriendDetailUiState(
    val isLoading: Boolean = true,
    val friend: Friend? = null,
    val summary: com.varun.upitracker.data.repository.FriendLedgerSummary? = null,
    val transactions: List<Transaction> = emptyList()
)

class FriendDetailViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _uiState = MutableLiveData(FriendDetailUiState())
    val uiState: LiveData<FriendDetailUiState> = _uiState

    fun load(friendId: Long) {
        viewModelScope.launch {
            _uiState.value = FriendDetailUiState(isLoading = true)
            _uiState.value = withContext(Dispatchers.IO) {
                FriendDetailUiState(
                    isLoading = false,
                    friend = db.friendDao().getFriendById(friendId),
                    summary = LedgerRepository(db).getSummaryForFriend(friendId),
                    transactions = db.transactionDao().getTransactionsForFriendSync(friendId)
                )
            }
        }
    }

    fun deleteTransaction(friendId: Long, transactionId: Long, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            // Mirrors AllTransactionsViewModel.deleteTransaction: a refund pointing at this
            // transaction must be dealt with first, same as everywhere else transactions are deleted.
            val refundCount = withContext(Dispatchers.IO) {
                db.transactionDao().getRefundIdsForOriginal(transactionId).size
            }
            if (refundCount > 0) {
                onError(
                    if (refundCount == 1) {
                        "A refund is linked to this transaction. Delete or unlink the refund first."
                    } else {
                        "$refundCount refunds are linked to this transaction. Delete or unlink them first."
                    }
                )
                return@launch
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.transactionShareDao().deleteForTransaction(transactionId)
                    db.transactionDao().deleteById(transactionId)
                }
            }
            load(friendId)
        }
    }
}

data class AliasMappingsCardItem(
    val id: Long,
    val title: String,
    val rawNames: List<AliasMappingsValueItem>,
    val upiIds: List<AliasMappingsValueItem>
)

data class AliasMappingsValueItem(val id: Long, val value: String)

data class AliasMappingsUiState(val cards: List<AliasMappingsCardItem> = emptyList())

class AliasMappingsViewModel(context: Context) : ViewModel() {
    private val repository = SettingsRepository(context.applicationContext)
    private val _uiState = MutableLiveData(AliasMappingsUiState())
    val uiState: LiveData<AliasMappingsUiState> = _uiState

    fun load(mode: String) {
        viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) {
                val cards = if (mode == AliasMappingsActivity.MODE_MERCHANT) {
                    repository.getMerchantAliasBundles().map { bundle ->
                        AliasMappingsCardItem(
                            id = bundle.merchant.id,
                            title = bundle.merchant.name,
                            rawNames = bundle.rawNames.sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds.sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.upiId) }
                        )
                    }
                } else {
                    repository.getFriendAliasBundles().map { bundle ->
                        AliasMappingsCardItem(
                            id = bundle.friend.id,
                            title = bundle.friend.name,
                            rawNames = bundle.rawNames.sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds.sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.upiId) }
                        )
                    }
                }
                AliasMappingsUiState(cards)
            }
        }
    }
}

class ScreenViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(TransactionEntryViewModel::class.java) -> TransactionEntryViewModel(context) as T
            modelClass.isAssignableFrom(AllTransactionsViewModel::class.java) -> AllTransactionsViewModel(context) as T
            modelClass.isAssignableFrom(FriendDetailViewModel::class.java) -> FriendDetailViewModel(context) as T
            modelClass.isAssignableFrom(AliasMappingsViewModel::class.java) -> AliasMappingsViewModel(context) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
