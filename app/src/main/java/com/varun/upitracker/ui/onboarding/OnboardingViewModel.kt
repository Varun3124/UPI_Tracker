package com.varun.upitracker.ui.onboarding

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.sms.SmsBacklogScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FriendInput(
    val name: String,
    val upiId: String
)

data class OnboardingUiState(
    val isBusy: Boolean = false,
    val statusMessage: String = ""
)

sealed interface OnboardingEffect {
    data object NavigateToDashboard : OnboardingEffect
    data class ShowError(val message: String) : OnboardingEffect
}

interface OnboardingRepository {
    suspend fun saveFriends(friends: List<FriendInput>)
    suspend fun scanSmsBacklog()
    fun markOnboardingComplete()
}

class DefaultOnboardingRepository(context: Context) : OnboardingRepository {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(
        SmsBacklogScanner.PREF_NAME,
        Context.MODE_PRIVATE
    )

    override suspend fun saveFriends(friends: List<FriendInput>) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                friends.forEach { friendInput ->
                    val name = friendInput.name.trim()
                    if (name.isEmpty()) return@forEach

                    val initials = buildInitials(name)
                    val friendId = db.friendDao().insertFriend(
                        Friend(
                            name = name,
                            avatarInitials = initials,
                            addedEpoch = System.currentTimeMillis()
                        )
                    )

                    val upiId = friendInput.upiId.trim()
                    if (upiId.isNotEmpty()) {
                        db.friendDao().insertUpiId(
                            FriendUpiId(friendId = friendId, upiId = upiId)
                        )
                    }
                }
            }
        }
    }

    override suspend fun scanSmsBacklog() {
        withContext(Dispatchers.IO) {
            SmsBacklogScanner(appContext).scan()
        }
    }

    override fun markOnboardingComplete() {
        prefs.edit().putBoolean(ONBOARDING_COMPLETE_KEY, true).apply()
    }

    private fun buildInitials(name: String): String {
        return name.split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
    }

    private companion object {
        const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
    }
}

class OnboardingViewModel(
    private val repository: OnboardingRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(OnboardingUiState())
    val uiState: LiveData<OnboardingUiState> = _uiState

    private val _effects = MutableSharedFlow<OnboardingEffect>()
    val effects = _effects.asSharedFlow()

    fun saveFriendsAndScan(friends: List<FriendInput>) {
        viewModelScope.launch {
            runOnboardingFlow(friends)
        }
    }

    fun scanOnly() {
        viewModelScope.launch {
            runOnboardingFlow(emptyList())
        }
    }

    private suspend fun runOnboardingFlow(friends: List<FriendInput>) {
        try {
            updateState(isBusy = true, message = "Saving your friends...")
            repository.saveFriends(friends)

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