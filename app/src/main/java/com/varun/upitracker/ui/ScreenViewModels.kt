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
                    accounts = db.accountDao().getActiveByTypes(listOf(AccountType.CASH, AccountType.SAVINGS)),
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
    val selectedMonthStartEpoch: Long = 0L
)

class AllTransactionsViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private var selectedMonthStartEpoch: Long = startOfMonth(Calendar.getInstance())
    private val _uiState = MutableLiveData(
        AllTransactionsUiState(selectedMonthStartEpoch = selectedMonthStartEpoch)
    )
    val uiState: LiveData<AllTransactionsUiState> = _uiState

    fun loadCurrentMonth() {
        loadMonth(selectedMonthStartEpoch)
    }

    fun loadMonth(monthStartEpoch: Long) {
        selectedMonthStartEpoch = monthStartEpoch
        viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) {
                val endOfMonth = Calendar.getInstance().apply {
                    timeInMillis = monthStartEpoch
                    add(Calendar.MONTH, 1)
                }.timeInMillis
                val transactions = db.transactionDao()
                    .getTransactionsBetweenSync(monthStartEpoch, endOfMonth)
                    .map(LedgerEntry::Tx)
                val transfers = db.accountTransferDao()
                    .getTransfersBetween(monthStartEpoch, endOfMonth)
                    .map(LedgerEntry::Transfer)
                AllTransactionsUiState(
                    entries = (transactions + transfers).sortedByDescending { it.dateEpoch },
                    accountLabels = db.accountDao().getAllSync().associate { it.id to it.label },
                    selectedMonthStartEpoch = monthStartEpoch
                )
            }
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.transactionShareDao().deleteForTransaction(transactionId)
                    db.transactionDao().deleteById(transactionId)
                }
            }
            loadMonth(selectedMonthStartEpoch)
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
            loadMonth(selectedMonthStartEpoch)
        }
    }

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
