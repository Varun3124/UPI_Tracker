package com.varun.upitracker.ui.transactionentry

import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Merchant

data class TransactionEntryUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSmsSource: Boolean = false,
    val smsPayerLockedToMe: Boolean = false,
    val smsPayeeLockedToMe: Boolean = false,
    val smsPayerAliasFallback: String = "",
    val smsPayeeAliasFallback: String = "",
    val amountRaw: String = "",
    val description: String = "",
    val selectedAccountId: String = "",
    val payer: EndpointUiState = EndpointUiState(),
    val payee: EndpointUiState = EndpointUiState(),
    val payerShares: List<ShareRowUiState> = emptyList(),
    val payeeShares: List<ShareRowUiState> = emptyList(),
    val categories: List<CategoryRowUiState> = emptyList(),
    val topInfoText: String = "",
    val totalBalanceText: String = "",
    val payerBalanceText: String = "",
    val payeeBalanceText: String = "",
    val showCategorySection: Boolean = false,
    val friends: List<Friend> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList()
)

data class EndpointUiState(
    val actorType: String = "",
    val friendId: Long? = null,
    val merchantId: Long? = null,
    val alias: String = "",
    val isMerchantToggleChecked: Boolean = false,
    val isAliasInputEnabled: Boolean = true
)

data class ShareRowUiState(
    val key: String,
    val participantType: String,
    val friendId: Long?,
    val label: String,
    val initials: String,
    val amountPaise: Long
)

data class CategoryRowUiState(
    val categoryId: Long,
    val name: String,
    val isSelected: Boolean,
    val myAmountPaise: Long
)
