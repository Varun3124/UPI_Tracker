package com.varun.upitracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.varun.upitracker.database.dao.AppSettingsDao
import com.varun.upitracker.database.dao.BudgetDao
import com.varun.upitracker.database.dao.CategoryDao
import com.varun.upitracker.database.dao.FriendDao
import com.varun.upitracker.database.dao.IouDao
import com.varun.upitracker.database.dao.MerchantDao
import com.varun.upitracker.database.dao.TransactionCategorySplitDao
import com.varun.upitracker.database.dao.TransactionDao
import com.varun.upitracker.database.dao.TransactionPartyDao
import com.varun.upitracker.database.dao.TransactionShareDao
import com.varun.upitracker.database.entity.AppSettings
import com.varun.upitracker.database.entity.BudgetSettings
import com.varun.upitracker.database.entity.Category
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
import com.varun.upitracker.database.entity.TransactionParty
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
        TransactionParty::class,
        TransactionShare::class,
        IouEntry::class,
        Merchant::class,
        MerchantRawName::class,
        MerchantUpiId::class,
        Category::class,
        MerchantCategory::class,
        AppSettings::class,
        BudgetSettings::class,
        TransactionCategorySplit::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun friendDao(): FriendDao
    abstract fun iouDao(): IouDao
    abstract fun transactionPartyDao(): TransactionPartyDao
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN mySharePaise INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transaction_category_splits (
                        transactionId INTEGER NOT NULL,
                        categoryId INTEGER NOT NULL,
                        myAmountPaise INTEGER NOT NULL,
                        partyAmountPaise INTEGER NOT NULL,
                        PRIMARY KEY(transactionId, categoryId),
                        FOREIGN KEY(transactionId) REFERENCES transactions(id) ON DELETE CASCADE,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_category_splits_transactionId ON transaction_category_splits(transactionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_category_splits_categoryId ON transaction_category_splits(categoryId)")

                db.execSQL("DELETE FROM merchant_categories")
                db.execSQL("DELETE FROM categories")
                DEFAULT_CATEGORIES.forEach { name ->
                    db.execSQL("INSERT INTO categories (name) VALUES ('$name')")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the new transactions table with the full schema (matching Transaction entity)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `observedDirection` TEXT,
                        `payeeRaw` TEXT NOT NULL,
                        `payeeType` TEXT NOT NULL,
                        `mySharePaise` INTEGER,
                        `resolvedFriendId` INTEGER,
                        `resolvedMerchantId` INTEGER,
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
                        `dateEpoch` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `isPending` INTEGER NOT NULL,
                        FOREIGN KEY(`resolvedFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`resolvedMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payerFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payerMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())

                // 2. Copy and transform data from the old transactions table
                db.execSQL("""
                    INSERT INTO `transactions_new` (
                        `id`, `amountPaise`, `direction`, `observedDirection`, `payeeRaw`, `payeeType`, `mySharePaise`,
                        `resolvedFriendId`, `resolvedMerchantId`,
                        `payerActorType`, `payerFriendId`, `payerMerchantId`, `payerRawLabel`,
                        `payeeActorType`, `payeeFriendId`, `payeeMerchantId`, `payeeRawLabel`,
                        `reason`, `upiRefId`, `dateEpoch`, `source`, `isPending`
                    )
                    SELECT
                        `id`, `amountPaise`, `direction`, `direction`, `payeeRaw`, `payeeType`, `mySharePaise`,
                        `resolvedFriendId`, `resolvedMerchantId`,
                        CASE
                            WHEN `direction` = 'DEBIT' THEN 'ME'
                            WHEN `payeeType` = 'FRIEND' THEN 'FRIEND'
                            WHEN `payeeType` = 'MERCHANT' THEN 'MERCHANT'
                            ELSE 'UNKNOWN'
                        END,
                        CASE WHEN `direction` = 'CREDIT' AND `payeeType` = 'FRIEND' THEN `resolvedFriendId` ELSE NULL END,
                        CASE WHEN `direction` = 'CREDIT' AND `payeeType` = 'MERCHANT' THEN `resolvedMerchantId` ELSE NULL END,
                        CASE WHEN `direction` = 'CREDIT' THEN `payeeRaw` ELSE NULL END,
                        CASE
                            WHEN `direction` = 'CREDIT' THEN 'ME'
                            WHEN `payeeType` = 'FRIEND' THEN 'FRIEND'
                            WHEN `payeeType` = 'MERCHANT' THEN 'MERCHANT'
                            ELSE 'UNKNOWN'
                        END,
                        CASE WHEN `direction` = 'DEBIT' AND `payeeType` = 'FRIEND' THEN `resolvedFriendId` ELSE NULL END,
                        CASE WHEN `direction` = 'DEBIT' AND `payeeType` = 'MERCHANT' THEN `resolvedMerchantId` ELSE NULL END,
                        CASE WHEN `direction` = 'DEBIT' THEN `payeeRaw` ELSE NULL END,
                        `reason`, `upiRefId`, `dateEpoch`, `source`, `isPending`
                    FROM `transactions`
                """.trimIndent())

                // 3. Replace old table with new one
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")

                // 4. Re-create all indices (names must match Room's auto-generated names or entity-specified names)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_resolvedFriendId` ON `transactions`(`resolvedFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_resolvedMerchantId` ON `transactions`(`resolvedMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerFriendId` ON `transactions`(`payerFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerMerchantId` ON `transactions`(`payerMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeFriendId` ON `transactions`(`payeeFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeMerchantId` ON `transactions`(`payeeMerchantId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_upiRefId` ON `transactions`(`upiRefId`)")

                // 5. Create transaction_shares table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transaction_shares` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `participantType` TEXT NOT NULL,
                        `friendId` INTEGER,
                        `shareSide` TEXT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`friendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_shares_transactionId` ON `transaction_shares`(`transactionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_shares_friendId` ON `transaction_shares`(`friendId`)")

                // 6. Migrate data from various sources to transaction_shares
                db.execSQL("""
                    INSERT INTO `transaction_shares` (`transactionId`, `participantType`, `friendId`, `shareSide`, `amountPaise`)
                    SELECT `id`, 'ME', NULL,
                        CASE WHEN `direction` = 'CREDIT' THEN 'MEANT_TO_RECEIVE' ELSE 'MEANT_TO_PAY' END,
                        COALESCE(`mySharePaise`, `amountPaise`)
                    FROM `transactions`
                    WHERE COALESCE(`mySharePaise`, `amountPaise`) > 0
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `transaction_shares` (`transactionId`, `participantType`, `friendId`, `shareSide`, `amountPaise`)
                    SELECT `transactionId`, 'FRIEND', `friendId`, 'MEANT_TO_PAY', `amountPaise`
                    FROM `iou_entries`
                    WHERE `amountPaise` > 0
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `transaction_shares` (`transactionId`, `participantType`, `friendId`, `shareSide`, `amountPaise`)
                    SELECT `transactionId`, 'FRIEND', `friendId`, 'MEANT_TO_PAY', `spentOnThemPaise`
                    FROM `transaction_parties`
                    WHERE `spentOnThemPaise` > 0
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the new transactions table with the correct schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `observedDirection` TEXT,
                        `payeeRaw` TEXT NOT NULL,
                        `payeeType` TEXT NOT NULL,
                        `mySharePaise` INTEGER,
                        `resolvedFriendId` INTEGER,
                        `resolvedMerchantId` INTEGER,
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
                        `dateEpoch` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `isPending` INTEGER NOT NULL,
                        FOREIGN KEY(`resolvedFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`resolvedMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payerFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payerMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeFriendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`payeeMerchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())

                // 2. Copy data from the potentially malformed transactions table
                // We use COALESCE for actor types to handle potential nulls if the previous migration failed to set defaults
                db.execSQL("""
                    INSERT INTO `transactions_new` (
                        `id`, `amountPaise`, `direction`, `observedDirection`, `payeeRaw`, `payeeType`, `mySharePaise`,
                        `resolvedFriendId`, `resolvedMerchantId`,
                        `payerActorType`, `payerFriendId`, `payerMerchantId`, `payerRawLabel`,
                        `payeeActorType`, `payeeFriendId`, `payeeMerchantId`, `payeeRawLabel`,
                        `reason`, `upiRefId`, `dateEpoch`, `source`, `isPending`
                    )
                    SELECT
                        `id`, `amountPaise`, `direction`, `observedDirection`, `payeeRaw`, `payeeType`, `mySharePaise`,
                        `resolvedFriendId`, `resolvedMerchantId`,
                        COALESCE(`payerActorType`, 'ME'), `payerFriendId`, `payerMerchantId`, `payerRawLabel`,
                        COALESCE(`payeeActorType`, 'UNKNOWN'), `payeeFriendId`, `payeeMerchantId`, `payeeRawLabel`,
                        `reason`, `upiRefId`, `dateEpoch`, `source`, `isPending`
                    FROM `transactions`
                """.trimIndent())

                // 3. Replace old table
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")

                // 4. Re-create all indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_resolvedFriendId` ON `transactions`(`resolvedFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_resolvedMerchantId` ON `transactions`(`resolvedMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerFriendId` ON `transactions`(`payerFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payerMerchantId` ON `transactions`(`payerMerchantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeFriendId` ON `transactions`(`payeeFriendId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_payeeMerchantId` ON `transactions`(`payeeMerchantId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_upiRefId` ON `transactions`(`upiRefId`)")
                
                // 5. Ensure transaction_shares table exists (in case MIGRATION_2_3 failed before creating it)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transaction_shares` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `participantType` TEXT NOT NULL,
                        `friendId` INTEGER,
                        `shareSide` TEXT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`friendId`) REFERENCES `friends`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_shares_transactionId` ON `transaction_shares`(`transactionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_shares_friendId` ON `transaction_shares`(`friendId`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_settings` (
                        `id` INTEGER NOT NULL,
                        `totalBalancePaise` INTEGER,
                        `updatedEpoch` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // A previous failed 4->5 attempt may already have created these
                // expression indexes. Drop them so the migrated schema matches
                // the Room entity model exactly during validation.
                db.execSQL("DROP INDEX IF EXISTS `index_friends_name_normalized`")
                db.execSQL("DROP INDEX IF EXISTS `index_merchants_name_normalized`")
                db.execSQL("DROP INDEX IF EXISTS `index_categories_name_normalized`")

                mergeDuplicateFriends(db)
                mergeDuplicateMerchants(db)
                mergeDuplicateCategories(db)
            }
        }

        private fun mergeDuplicateFriends(db: SupportSQLiteDatabase) {
            val groups = linkedMapOf<String, MutableList<Long>>()
            db.query("SELECT id, name FROM friends ORDER BY id ASC").use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val normalized = normalizeKey(cursor.getString(nameIndex))
                    groups.getOrPut(normalized) { mutableListOf() }.add(id)
                }
            }
            groups.values.forEach { ids ->
                if (ids.size < 2) return@forEach
                val targetId = ids.first()
                ids.drop(1).forEach { sourceId ->
                    db.execSQL("UPDATE transactions SET resolvedFriendId = ? WHERE resolvedFriendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transactions SET payerFriendId = ? WHERE payerFriendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transactions SET payeeFriendId = ? WHERE payeeFriendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transaction_shares SET friendId = ? WHERE friendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transaction_parties SET friendId = ? WHERE friendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE iou_entries SET friendId = ? WHERE friendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE friend_raw_names SET friendId = ? WHERE friendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE friend_upi_ids SET friendId = ? WHERE friendId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("DELETE FROM friends WHERE id = ?", arrayOf(sourceId))
                }
            }
        }

        private fun mergeDuplicateMerchants(db: SupportSQLiteDatabase) {
            val groups = linkedMapOf<String, MutableList<Long>>()
            db.query("SELECT id, name FROM merchants ORDER BY id ASC").use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val normalized = normalizeKey(cursor.getString(nameIndex))
                    groups.getOrPut(normalized) { mutableListOf() }.add(id)
                }
            }
            groups.values.forEach { ids ->
                if (ids.size < 2) return@forEach
                val targetId = ids.first()
                ids.drop(1).forEach { sourceId ->
                    db.execSQL("UPDATE transactions SET resolvedMerchantId = ? WHERE resolvedMerchantId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transactions SET payerMerchantId = ? WHERE payerMerchantId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE transactions SET payeeMerchantId = ? WHERE payeeMerchantId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE merchant_raw_names SET merchantId = ? WHERE merchantId = ?", arrayOf(targetId, sourceId))
                    db.execSQL("UPDATE merchant_upi_ids SET merchantId = ? WHERE merchantId = ?", arrayOf(targetId, sourceId))
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO merchant_categories (merchantId, categoryId)
                        SELECT ?, categoryId FROM merchant_categories WHERE merchantId = ?
                        """.trimIndent(),
                        arrayOf(targetId, sourceId)
                    )
                    db.execSQL("DELETE FROM merchant_categories WHERE merchantId = ?", arrayOf(sourceId))
                    db.execSQL("DELETE FROM merchants WHERE id = ?", arrayOf(sourceId))
                }
            }
        }

        private fun mergeDuplicateCategories(db: SupportSQLiteDatabase) {
            val groups = linkedMapOf<String, MutableList<Long>>()
            db.query("SELECT id, name FROM categories ORDER BY id ASC").use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val normalized = normalizeKey(cursor.getString(nameIndex))
                    groups.getOrPut(normalized) { mutableListOf() }.add(id)
                }
            }
            groups.values.forEach { ids ->
                if (ids.size < 2) return@forEach
                val targetId = ids.first()
                ids.drop(1).forEach { sourceId ->
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO merchant_categories (merchantId, categoryId)
                        SELECT merchantId, ? FROM merchant_categories WHERE categoryId = ?
                        """.trimIndent(),
                        arrayOf(targetId, sourceId)
                    )
                    db.execSQL("DELETE FROM merchant_categories WHERE categoryId = ?", arrayOf(sourceId))

                    db.query(
                        "SELECT transactionId, myAmountPaise, partyAmountPaise FROM transaction_category_splits WHERE categoryId = ?",
                        arrayOf(sourceId)
                    ).use { cursor ->
                        val txIndex = cursor.getColumnIndexOrThrow("transactionId")
                        val myIndex = cursor.getColumnIndexOrThrow("myAmountPaise")
                        val partyIndex = cursor.getColumnIndexOrThrow("partyAmountPaise")
                        while (cursor.moveToNext()) {
                            val txId = cursor.getLong(txIndex)
                            val myAmount = cursor.getLong(myIndex)
                            val partyAmount = cursor.getLong(partyIndex)
                            db.query(
                                "SELECT myAmountPaise, partyAmountPaise FROM transaction_category_splits WHERE transactionId = ? AND categoryId = ? LIMIT 1",
                                arrayOf(txId, targetId)
                            ).use { existing ->
                                if (existing.moveToFirst()) {
                                    val mergedMy = existing.getLong(0) + myAmount
                                    val mergedParty = existing.getLong(1) + partyAmount
                                    db.execSQL(
                                        "UPDATE transaction_category_splits SET myAmountPaise = ?, partyAmountPaise = ? WHERE transactionId = ? AND categoryId = ?",
                                        arrayOf(mergedMy, mergedParty, txId, targetId)
                                    )
                                    db.execSQL(
                                        "DELETE FROM transaction_category_splits WHERE transactionId = ? AND categoryId = ?",
                                        arrayOf(txId, sourceId)
                                    )
                                } else {
                                    db.execSQL(
                                        "UPDATE transaction_category_splits SET categoryId = ? WHERE transactionId = ? AND categoryId = ?",
                                        arrayOf(targetId, txId, sourceId)
                                    )
                                }
                            }
                        }
                    }
                    db.execSQL("DELETE FROM categories WHERE id = ?", arrayOf(sourceId))
                }
            }
        }

        private fun normalizeKey(value: String?): String = value?.trim()?.lowercase() ?: ""

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "upi_tracker_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

