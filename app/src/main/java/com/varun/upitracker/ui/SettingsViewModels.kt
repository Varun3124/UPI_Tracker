package com.varun.upitracker.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.database.entity.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(context: Context) : ViewModel() {
    private val repository = SettingsRepository(context.applicationContext)
    private val _balancePaise = MutableLiveData<Long?>()
    val balancePaise: LiveData<Long?> = _balancePaise

    fun loadBalance() {
        viewModelScope.launch {
            _balancePaise.value = withContext(Dispatchers.IO) { repository.getTotalBalancePaise() }
        }
    }

    fun saveBalance(paise: Long, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.updateTotalBalancePaise(paise) }
                loadBalance()
            } catch (error: Exception) {
                onError(error.message ?: "Could not save balance.")
            }
        }
    }
}

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

class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(context) as T
            modelClass.isAssignableFrom(CategorySettingsViewModel::class.java) -> CategorySettingsViewModel(context) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
