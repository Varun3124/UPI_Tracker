package com.varun.upitracker.ui.transactionentry

sealed interface TransactionEntryEffect {
    data class ShowMessage(val message: String) : TransactionEntryEffect
    data object HideKeyboard : TransactionEntryEffect
    data object NavigateBack : TransactionEntryEffect
    data class RunLegacyAction(val action: TransactionEntryAction) : TransactionEntryEffect
}
