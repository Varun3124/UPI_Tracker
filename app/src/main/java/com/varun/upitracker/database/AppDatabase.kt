package com.varun.upitracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.varun.upitracker.database.dao.AccountDao
import com.varun.upitracker.database.dao.AccountTransferDao
import com.varun.upitracker.database.dao.BalanceSnapshotDao
import com.varun.upitracker.database.dao.BudgetDao
import com.varun.upitracker.database.dao.CategoryDao
import com.varun.upitracker.database.dao.FixedDepositDao
import com.varun.upitracker.database.dao.FriendDao
import com.varun.upitracker.database.dao.IouDao
import com.varun.upitracker.database.dao.MerchantDao
import com.varun.upitracker.database.dao.TransactionCategorySplitDao
import com.varun.upitracker.database.dao.TransactionDao
import com.varun.upitracker.database.dao.TransactionShareDao
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BudgetSettings
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.entity.FixedDepositDetail
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.IouEntry
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantCategory
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.entity.TransactionShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Transaction::class,
        Friend::class,
        FriendUpiId::class,
        FriendRawName::class,
        TransactionShare::class,
        IouEntry::class,
        Merchant::class,
        MerchantRawName::class,
        MerchantUpiId::class,
        Category::class,
        MerchantCategory::class,
        BudgetSettings::class,
        TransactionCategorySplit::class,
        Account::class,
        FixedDepositDetail::class,
        AccountTransfer::class,
        BalanceSnapshot::class
    ],
    version = 15,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun accountTransferDao(): AccountTransferDao
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
    abstract fun budgetDao(): BudgetDao
    abstract fun categoryDao(): CategoryDao
    abstract fun fixedDepositDao(): FixedDepositDao
    abstract fun friendDao(): FriendDao
    abstract fun iouDao(): IouDao
    abstract fun merchantDao(): MerchantDao
    abstract fun categorySplitDao(): TransactionCategorySplitDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionShareDao(): TransactionShareDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Seeded on fresh installs only -- [Callback.onCreate] never fires on an upgrade, so
         * MIGRATION_12_13 seeds the INCOME half separately. Keep the two lists in step.
         */
        private val DEFAULT_CATEGORIES = listOf(
            "Food & Drink" to CategoryKind.EXPENSE,
            "Entertainment" to CategoryKind.EXPENSE,
            "Transport" to CategoryKind.EXPENSE,
            "Essentials" to CategoryKind.EXPENSE,
            "Gift" to CategoryKind.EXPENSE,
            "Gift" to CategoryKind.INCOME,
            "Refund" to CategoryKind.INCOME,
            "Dividend" to CategoryKind.INCOME
        )

        /** The INCOME rows MIGRATION_12_13 backfills into existing installs. */
        private val SEEDED_INCOME_CATEGORIES =
            DEFAULT_CATEGORIES.filter { it.second == CategoryKind.INCOME }.map { it.first }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        `payerActorType` TEXT NOT NULL,
                        `payerFriendId` INTEGER,
                        `payerMerchantId` INTEGER,
                        `payerRawLabel` TEXT,
                        `payeeActorType` TEXT NOT NULL,
                        `payeeFriendId` INTEGER,
                        `payeeMerchantId` INTEGER,
                        `payeeRawLabel` TEXT,
                        `reason` TEXT,
                        `upiRefId` TEXT,
                        `myAccountId` TEXT,
                        `dateEpoch` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `isPending` INTEGER NOT NULL,
                        FOREIGN KEY(`payerFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payerMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`myAccountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `transactions_new` (
                        `id`, `amountPaise`, `payerActorType`, `payerFriendId`, `payerMerchantId`, `payerRawLabel`,
                        `payeeActorType`, `payeeFriendId`, `payeeMerchantId`, `payeeRawLabel`,
                        `reason`, `upiRefId`, `myAccountId`, `dateEpoch`, `source`, `isPending`
                    )
                    SELECT
                        `id`, `amountPaise`, `payerActorType`, `payerFriendId`, `payerMerchantId`, `payerRawLabel`,
                        `payeeActorType`, `payeeFriendId`, `payeeMerchantId`, `payeeRawLabel`,
                        `reason`, `upiRefId`, `myAccountId`, `dateEpoch`, `source`, `isPending`
                    FROM `transactions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerFriendId` ON `transactions`(`payerFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerMerchantId` ON `transactions`(`payerMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeFriendId` ON `transactions`(`payeeFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeMerchantId` ON `transactions`(`payeeMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_myAccountId` ON `transactions`(`myAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_myAccountId_dateEpoch` ON `transactions`(`myAccountId`, `dateEpoch`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_upiRefId` ON `transactions`(`upiRefId`)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `account` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `account_transfer_new` (
                        `id` TEXT NOT NULL,
                        `fromAccountId` TEXT,
                        `toAccountId` TEXT,
                        `amountFromPaise` INTEGER NOT NULL,
                        `amountToPaise` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `dateEpoch` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `statementRefNo` TEXT,
                        `notes` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`fromAccountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(`toAccountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `account_transfer_new` (
                        `id`, `fromAccountId`, `toAccountId`, `amountFromPaise`, `amountToPaise`,
                        `type`, `dateEpoch`, `source`, `statementRefNo`, `notes`
                    )
                    SELECT
                        `id`, `fromAccountId`, `toAccountId`, `amountFromPaise`, `amountToPaise`,
                        `type`, `dateEpoch`, `source`, `statementRefNo`, `notes`
                    FROM `account_transfer`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `account_transfer`")
                db.execSQL("ALTER TABLE `account_transfer_new` RENAME TO `account_transfer`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transfer_fromAccountId_dateEpoch` ON `account_transfer`(`fromAccountId`, `dateEpoch`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transfer_toAccountId_dateEpoch` ON `account_transfer`(`toAccountId`, `dateEpoch`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_account_transfer_statementRefNo` ON `account_transfer`(`statementRefNo`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `balance_snapshot_new` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `snapshotEpoch` INTEGER NOT NULL,
                        `balancePaise` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `notes` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `balance_snapshot_new` (
                        `id`, `accountId`, `snapshotEpoch`, `balancePaise`, `source`, `notes`
                    )
                    SELECT `id`, `accountId`, `snapshotEpoch`, `balancePaise`, `source`, `notes`
                    FROM `balance_snapshot`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `balance_snapshot`")
                db.execSQL("ALTER TABLE `balance_snapshot_new` RENAME TO `balance_snapshot`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_snapshot_accountId_snapshotEpoch` ON `balance_snapshot`(`accountId`, `snapshotEpoch`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fixed_deposit_detail_new` (
                        `accountId` TEXT NOT NULL,
                        `principalPaise` INTEGER NOT NULL,
                        `sourceAccountId` TEXT NOT NULL,
                        `bookedEpoch` INTEGER NOT NULL,
                        `maturityEpoch` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`accountId`),
                        FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(`sourceAccountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `fixed_deposit_detail_new` (
                        `accountId`, `principalPaise`, `sourceAccountId`, `bookedEpoch`, `maturityEpoch`, `status`
                    )
                    SELECT `accountId`, `principalPaise`, `sourceAccountId`, `bookedEpoch`, `maturityEpoch`, `status`
                    FROM `fixed_deposit_detail`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `fixed_deposit_detail`")
                db.execSQL("ALTER TABLE `fixed_deposit_detail_new` RENAME TO `fixed_deposit_detail`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fixed_deposit_detail_sourceAccountId` ON `fixed_deposit_detail`(`sourceAccountId`)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(`account`)")
                var columnExists = false
                while (cursor.moveToNext()) {
                    val nameColumnIndex = cursor.getColumnIndex("name")
                    if (nameColumnIndex != -1) {
                        val name = cursor.getString(nameColumnIndex)
                        if (name == "isArchived") {
                            columnExists = true
                            break
                        }
                    }
                }
                cursor.close()

                if (!columnExists) {
                    db.execSQL("ALTER TABLE `account` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "transactions", "statementRefNo")) {
                    db.execSQL("ALTER TABLE `transactions` ADD COLUMN `statementRefNo` TEXT")
                }
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_statementRefNo` " +
                        "ON `transactions`(`statementRefNo`)"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "transactions", "ledgerEffect")) {
                    db.execSQL(
                        "ALTER TABLE `transactions` ADD COLUMN `ledgerEffect` TEXT NOT NULL " +
                            "DEFAULT 'DEBT'"
                    )
                }
                if (!hasColumn(db, "transactions", "refundsTransactionId")) {
                    // No DEFAULT clause on purpose: SQLite only permits ADD COLUMN with a
                    // REFERENCES clause when the column's default is NULL, and an omitted
                    // default is exactly that. This is why no table rebuild is needed.
                    db.execSQL(
                        "ALTER TABLE `transactions` ADD COLUMN `refundsTransactionId` INTEGER " +
                            "REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT"
                    )
                }
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_refundsTransactionId` " +
                        "ON `transactions`(`refundsTransactionId`)"
                )

                if (!hasColumn(db, "categories", "kind")) {
                    db.execSQL(
                        "ALTER TABLE `categories` ADD COLUMN `kind` TEXT NOT NULL " +
                            "DEFAULT 'EXPENSE'"
                    )
                }
                // The old index is unique on `name` alone, which would reject "Gift" existing
                // as both an expense and an income category. Drop before creating.
                db.execSQL("DROP INDEX IF EXISTS `index_categories_name`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name_kind` " +
                        "ON `categories`(`name`, `kind`)"
                )

                // onCreate's seeding never runs on an upgrade, so without this the INCOME picker
                // is empty on every existing install and unlinked credits cannot be categorised.
                SEEDED_INCOME_CATEGORIES.forEach { name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `categories` (`name`, `kind`) VALUES (?, 'INCOME')",
                        arrayOf<Any>(name)
                    )
                }
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The statistics screen made this worth paying for: it runs seven of these per
                // weekly view, and `dateEpoch` had no index of its own -- only the composite
                // (myAccountId, dateEpoch), whose leading column none of these queries constrain.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_dateEpoch` " +
                        "ON `transactions`(`dateEpoch`)"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Both columns are nullable with no DEFAULT and neither is a foreign key, so
                // ADD COLUMN is legal outright -- no table rebuild, unlike 8->9 and 9->10.
                if (!hasColumn(db, "transactions", "sharedRefId")) {
                    db.execSQL("ALTER TABLE `transactions` ADD COLUMN `sharedRefId` TEXT")
                }
                // Unique like index_transactions_upiRefId: re-applying a parcel already applied
                // must fail at the insert rather than duplicate the row. Existing rows all hold
                // NULL here, and SQLite counts NULLs as distinct, so none of them collide.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_sharedRefId` " +
                        "ON `transactions`(`sharedRefId`)"
                )

                if (!hasColumn(db, "transaction_shares", "rawLabel")) {
                    db.execSQL("ALTER TABLE `transaction_shares` ADD COLUMN `rawLabel` TEXT")
                }
            }
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameColumnIndex = cursor.getColumnIndex("name")
                if (nameColumnIndex == -1) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumnIndex) == column) return true
                }
            }
            return false
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "upi_tracker_db"
                )
                    .addMigrations(
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val instance = getInstance(context)
                                DEFAULT_CATEGORIES.forEach { (name, kind) ->
                                    instance.categoryDao().insertCategory(Category(name = name, kind = kind))
                                }
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
