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
import com.varun.upitracker.database.dao.AppSettingsDao
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
import com.varun.upitracker.database.entity.AppSettings
import com.varun.upitracker.database.entity.BalanceSnapshot
import com.varun.upitracker.database.entity.BudgetSettings
import com.varun.upitracker.database.entity.Category
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
        AppSettings::class,
        BudgetSettings::class,
        TransactionCategorySplit::class,
        Account::class,
        FixedDepositDetail::class,
        AccountTransfer::class,
        BalanceSnapshot::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun accountTransferDao(): AccountTransferDao
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
    abstract fun fixedDepositDao(): FixedDepositDao
    abstract fun transactionDao(): TransactionDao
    abstract fun friendDao(): FriendDao
    abstract fun iouDao(): IouDao
    abstract fun transactionShareDao(): TransactionShareDao
    abstract fun merchantDao(): MerchantDao
    abstract fun categoryDao(): CategoryDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun budgetDao(): BudgetDao
    abstract fun categorySplitDao(): TransactionCategorySplitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val DEFAULT_CATEGORIES = listOf(
            "Food & Drink", "Entertainment", "Transport", "Essentials"
        )

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "upi_tracker_db"
                )
                    .addMigrations(MIGRATION_8_9)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val instance = getInstance(context)
                                DEFAULT_CATEGORIES.forEach { name ->
                                    instance.categoryDao().insertCategory(Category(name = name))
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
