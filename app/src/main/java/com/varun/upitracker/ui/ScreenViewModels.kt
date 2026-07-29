package com.varun.upitracker.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TransactionEntryReferenceData(
    val friends: List<Friend> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val transaction: Transaction? = null
)

class TransactionEntryViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _referenceData = MutableLiveData<TransactionEntryReferenceData>()
    val referenceData: LiveData<TransactionEntryReferenceData> = _referenceData

    fun load(transactionId: Long?) {
        viewModelScope.launch {
            _referenceData.value = withContext(Dispatchers.IO) {
                TransactionEntryReferenceData(
                    friends = db.friendDao().getAllFriendsByFrequency(),
                    merchants = db.merchantDao().getAllMerchantsSync(),
                    categories = db.categoryDao().getAllCategoriesSync(),
                    accounts = db.accountDao().getActiveByTypes(listOf(AccountType.CASH, AccountType.SAVINGS)),
                    transaction = transactionId?.let { db.transactionDao().getTransactionById(it) }
                )
            }
        }
    }
}

data class AllTransactionsUiState(val transactions: List<Transaction> = emptyList())

class AllTransactionsViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _uiState = MutableLiveData(AllTransactionsUiState())
    val uiState: LiveData<AllTransactionsUiState> = _uiState

    fun loadCurrentMonth() {
        viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) {
                val startOfMonth = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                AllTransactionsUiState(db.transactionDao().getTransactionsSinceSync(startOfMonth))
            }
        }
    }
}

data class FriendDetailUiState(
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
            _uiState.value = withContext(Dispatchers.IO) {
                FriendDetailUiState(
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
