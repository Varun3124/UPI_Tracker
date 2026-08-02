package com.varun.upitracker.database.entity

enum class AccountType {
    CASH,
    SAVINGS,
    FD,
    INVESTMENT_INVESTED,
    INVESTMENT_UNINVESTED
}

enum class AccountTransferType {
    CASH_WITHDRAWAL,
    CASH_DEPOSIT,
    FD_BOOKING,
    FD_RETURN,
    FD_INTEREST_CREDIT,
    SAVINGS_INTEREST_CREDIT,
    INVESTMENT_BUY,
    INVESTMENT_SELL,
    GENERIC_TRANSFER
}

enum class EntrySource {
    SMS,
    MANUAL,
    BANK_STATEMENT
}

enum class FixedDepositStatus {
    ACTIVE,
    MATURED,
    WITHDRAWN_PREMATURE
}

enum class BalanceSnapshotSource {
    MANUAL,
    BANK_STATEMENT
}
