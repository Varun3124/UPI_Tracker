package com.varun.upitracker.database

import androidx.room.TypeConverter
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.BalanceSnapshotSource
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.database.entity.FixedDepositStatus

class Converters {
    @TypeConverter fun toAccountType(value: String?): AccountType? = value?.let(AccountType::valueOf)
    @TypeConverter fun fromAccountType(value: AccountType?): String? = value?.name
    @TypeConverter fun toAccountTransferType(value: String?): AccountTransferType? = value?.let(AccountTransferType::valueOf)
    @TypeConverter fun fromAccountTransferType(value: AccountTransferType?): String? = value?.name
    @TypeConverter fun toEntrySource(value: String?): EntrySource? = value?.let(EntrySource::valueOf)
    @TypeConverter fun fromEntrySource(value: EntrySource?): String? = value?.name
    @TypeConverter fun toFixedDepositStatus(value: String?): FixedDepositStatus? = value?.let(FixedDepositStatus::valueOf)
    @TypeConverter fun fromFixedDepositStatus(value: FixedDepositStatus?): String? = value?.name
    @TypeConverter fun toBalanceSnapshotSource(value: String?): BalanceSnapshotSource? = value?.let(BalanceSnapshotSource::valueOf)
    @TypeConverter fun fromBalanceSnapshotSource(value: BalanceSnapshotSource?): String? = value?.name
}
