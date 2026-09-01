package com.varun.upitracker.domain

import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.AccountType

sealed interface TransferTypeResolution {
    data class Resolved(val type: AccountTransferType) : TransferTypeResolution
    data class Unsupported(val message: String) : TransferTypeResolution
}

/**
 * Derives the [AccountTransferType] for a transfer from the two accounts' [AccountType]s, so the
 * transaction entry screen needs no type picker.
 */
object AccountTransferTypeResolver {

    fun resolve(from: AccountType, to: AccountType): TransferTypeResolution = when {
        // Booking an FD also needs a principal and a maturity date, and creates the FD account plus
        // its FixedDepositDetail row. AccountRepository.createFixedDeposit already emits its own
        // FD_BOOKING transfer as part of that; deriving a second one here would leave a transfer
        // with no detail backing it and risk double-counting the principal.
        to == AccountType.FD ->
            TransferTypeResolution.Unsupported("Book fixed deposits from the Accounts screen")

        // Must precede the CASH clauses so FD -> CASH resolves to FD_RETURN, not CASH_WITHDRAWAL.
        from == AccountType.FD -> TransferTypeResolution.Resolved(AccountTransferType.FD_RETURN)

        from == AccountType.CASH -> TransferTypeResolution.Resolved(AccountTransferType.CASH_DEPOSIT)
        to == AccountType.CASH -> TransferTypeResolution.Resolved(AccountTransferType.CASH_WITHDRAWAL)

        from == AccountType.INVESTMENT_UNINVESTED && to == AccountType.INVESTMENT_INVESTED ->
            TransferTypeResolution.Resolved(AccountTransferType.INVESTMENT_BUY)

        from == AccountType.INVESTMENT_INVESTED && to == AccountType.INVESTMENT_UNINVESTED ->
            TransferTypeResolution.Resolved(AccountTransferType.INVESTMENT_SELL)

        else -> TransferTypeResolution.Resolved(AccountTransferType.GENERIC_TRANSFER)
    }
}
