package com.varun.upitracker.ui.transactionentry

sealed interface TransactionEntryAction {
    data class ScreenLoaded(val transactionId: Long?) : TransactionEntryAction
    data class AmountChanged(val rawAmount: String) : TransactionEntryAction
    data class AccountSelected(val accountId: String) : TransactionEntryAction
    data class ActorTypeSelected(val side: EntrySide, val actorType: String) : TransactionEntryAction
    data class DateChanged(val dateEpoch: Long) : TransactionEntryAction
    data class ToggleMerchant(val side: EntrySide, val isMerchant: Boolean) : TransactionEntryAction
    data class AliasChanged(val side: EntrySide, val text: String) : TransactionEntryAction
    data class AliasSelected(val side: EntrySide, val selection: String) : TransactionEntryAction
    data class AddShare(val side: EntrySide) : TransactionEntryAction
    data class RemoveShare(val side: EntrySide, val rowIndex: Int) : TransactionEntryAction
    data class ShareNameChanged(val side: EntrySide, val rowIndex: Int, val text: String) : TransactionEntryAction
    data class ShareNameCommitted(val side: EntrySide, val rowIndex: Int) : TransactionEntryAction
    data class ShareAmountChanged(val side: EntrySide, val rowIndex: Int, val rawAmount: String) : TransactionEntryAction
    data class DescriptionChanged(val text: String) : TransactionEntryAction
    data class CategoryToggled(val categoryId: Long, val selected: Boolean) : TransactionEntryAction
    data class CategoryAmountChanged(val categoryId: Long, val rawAmount: String) : TransactionEntryAction
    data object SaveClicked : TransactionEntryAction
    data object CloseClicked : TransactionEntryAction
}

enum class EntrySide {
    PAYER,
    PAYEE
}
