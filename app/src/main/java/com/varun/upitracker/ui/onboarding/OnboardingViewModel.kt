package com.varun.upitracker.ui.onboarding

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.DefaultOnboardingRepository
import com.varun.upitracker.data.repository.OnboardingRepository
import com.varun.upitracker.database.entity.AccountType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class AccountInput(
    val label: String,
    val type: AccountType,
    val initialBalancePaise: Long,
    val snapshotEpoch: Long
)

data class OnboardingUiState(
    val isBusy: Boolean = false,
    val statusMessage: String = ""
)

sealed interface OnboardingEffect {
    data object NavigateToDashboard : OnboardingEffect
    data class ShowError(val message: String) : OnboardingEffect
}

class OnboardingViewModel(
    private val repository: OnboardingRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(OnboardingUiState())
    val uiState: LiveData<OnboardingUiState> = _uiState

    private val _effects = MutableSharedFlow<OnboardingEffect>()
    val effects = _effects.asSharedFlow()

    fun saveAccountsAndScan(accounts: List<AccountInput>) {
        viewModelScope.launch {
            runOnboardingAccountsFlow(accounts)
        }
    }

    private suspend fun runOnboardingAccountsFlow(accounts: List<AccountInput>) {
        try {
            updateState(isBusy = true, message = "Saving your accounts...")
            repository.saveAccounts(accounts)

            updateState(isBusy = true, message = "Scanning your SMS history...")
            repository.scanSmsBacklog()
            repository.markOnboardingComplete()

            updateState(isBusy = false, message = "Done! Taking you to your dashboard...")
            _effects.emit(OnboardingEffect.NavigateToDashboard)
        } catch (exception: Exception) {
            updateState(isBusy = false, message = "")
            _effects.emit(
                OnboardingEffect.ShowError(
                    exception.message ?: "Unable to complete onboarding."
                )
            )
        }
    }

    private fun updateState(isBusy: Boolean, message: String) {
        _uiState.value = OnboardingUiState(isBusy = isBusy, statusMessage = message)
    }
}

class OnboardingViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(DefaultOnboardingRepository(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}