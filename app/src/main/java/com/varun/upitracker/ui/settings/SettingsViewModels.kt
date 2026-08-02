package com.varun.upitracker.ui.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.AccountCreateRequest
import com.varun.upitracker.data.repository.AccountDeleteResult
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.FixedDepositCreateRequest
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.data.repository.SettingsRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class CategorySettingsViewModel(context: Context) : ViewModel() {
    private val repository = SettingsRepository(context.applicationContext)
    private val _categories = MutableLiveData<List<Category>>(emptyList())
    val categories: LiveData<List<Category>> = _categories

    fun loadCategories() {
        viewModelScope.launch {
            _categories.value = withContext(Dispatchers.IO) { repository.getCategories() }
        }
    }

    fun createCategory(name: String, onError: (String) -> Unit) = mutate(onError) { repository.createCategory(name) }
    fun renameCategory(id: Long, name: String, onError: (String) -> Unit) = mutate(onError) { repository.renameCategory(id, name) }
    fun deleteCategory(id: Long, replacementId: Long?, onError: (String) -> Unit) = mutate(onError) {
        repository.deleteCategory(id, replacementId)
    }

    fun isCategoryInUse(id: Long, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            callback(withContext(Dispatchers.IO) { repository.isCategoryInUse(id) })
        }
    }

    fun replacementCategories(excludingId: Long, callback: (List<Category>) -> Unit) {
        viewModelScope.launch {
            callback(withContext(Dispatchers.IO) { repository.getReplacementCategories(excludingId) })
        }
    }

    private fun mutate(onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                loadCategories()
            } catch (error: Exception) {
                onError(error.message ?: "Could not update categories.")
            }
        }
    }
}

data class AccountRowUi(
    val account: Account,
    val balancePaise: Long,
    val snapshots: List<BalanceSnapshot>
)

data class AccountsUiState(
    val rows: List<AccountRowUi> = emptyList(),
    val sourceAccounts: List<Account> = emptyList()
)

class AccountsViewModel(context: Context) : ViewModel() {
    private val repository = AccountRepository(AppDatabase.getInstance(context.applicationContext))
    private val _state = MutableLiveData(AccountsUiState())
    val state: LiveData<AccountsUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val accounts = repository.getAccounts()
                val now = System.currentTimeMillis()
                AccountsUiState(
                    rows = accounts.map { account ->
                        AccountRowUi(
                            account = account,
                            balancePaise = repository.getBalance(account.id, now),
                            snapshots = repository.getSnapshots(account.id)
                        )
                    },
                    sourceAccounts = accounts.filter {
                        !it.isArchived && (it.type == AccountType.CASH || it.type == AccountType.SAVINGS)
                    }
                )
            }
        }
    }

    fun createAccount(request: AccountCreateRequest, onError: (String) -> Unit) =
        mutate(onError) { repository.createAccount(request) }

    fun createFixedDeposit(request: FixedDepositCreateRequest, onError: (String) -> Unit) =
        mutate(onError) { repository.createFixedDeposit(request) }

    fun updateAccountLabel(accountId: String, label: String, onError: (String) -> Unit) =
        mutate(onError) { repository.updateAccountLabel(accountId, label) }

    fun setDefault(accountId: String, onError: (String) -> Unit) =
        mutate(onError) { repository.setDefault(accountId) }

    fun deleteOrArchive(accountId: String, onResult: (AccountDeleteResult) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.deleteOrArchive(accountId) }
                onResult(result)
                load()
            } catch (error: Exception) {
                onError(error.message ?: "Could not update account.")
            }
        }
    }

    fun addSnapshot(
        accountId: String,
        snapshotEpoch: Long,
        balancePaise: Long,
        source: BalanceSnapshotSource,
        notes: String?,
        onError: (String) -> Unit
    ) = mutate(onError) {
        repository.addSnapshot(accountId, snapshotEpoch, balancePaise, source, notes)
    }

    fun updateSnapshot(snapshot: BalanceSnapshot, onError: (String) -> Unit) =
        mutate(onError) { repository.updateSnapshot(snapshot) }

    fun deleteSnapshot(snapshotId: String, onError: (String) -> Unit) =
        mutate(onError) { repository.deleteSnapshot(snapshotId) }

    private fun mutate(onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                load()
            } catch (error: Exception) {
                onError(error.message ?: "Could not update account.")
            }
        }
    }
}

class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(CategorySettingsViewModel::class.java) -> CategorySettingsViewModel(context) as T
            modelClass.isAssignableFrom(AccountsViewModel::class.java) -> AccountsViewModel(context) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
