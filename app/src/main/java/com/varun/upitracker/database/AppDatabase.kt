package com.varun.upitracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.varun.upitracker.database.dao.AppSettingsDao
import com.varun.upitracker.database.dao.BudgetDao
import com.varun.upitracker.database.dao.CategoryDao
import com.varun.upitracker.database.dao.FriendDao
import com.varun.upitracker.database.dao.IouDao
import com.varun.upitracker.database.dao.MerchantDao
import com.varun.upitracker.database.dao.TransactionCategorySplitDao
import com.varun.upitracker.database.dao.TransactionDao
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
import com.varun.upitracker.database.entity.TransactionShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        _root_ide_package_.com.varun.upitracker.database.entity.Transaction::class,
        _root_ide_package_.com.varun.upitracker.database.entity.Friend::class,
        _root_ide_package_.com.varun.upitracker.database.entity.FriendUpiId::class,
        _root_ide_package_.com.varun.upitracker.database.entity.FriendRawName::class,
        _root_ide_package_.com.varun.upitracker.database.entity.TransactionShare::class,
        _root_ide_package_.com.varun.upitracker.database.entity.IouEntry::class,
        _root_ide_package_.com.varun.upitracker.database.entity.Merchant::class,
        _root_ide_package_.com.varun.upitracker.database.entity.MerchantRawName::class,
        _root_ide_package_.com.varun.upitracker.database.entity.MerchantUpiId::class,
        _root_ide_package_.com.varun.upitracker.database.entity.Category::class,
        _root_ide_package_.com.varun.upitracker.database.entity.MerchantCategory::class,
        _root_ide_package_.com.varun.upitracker.database.entity.AppSettings::class,
        _root_ide_package_.com.varun.upitracker.database.entity.BudgetSettings::class,
        _root_ide_package_.com.varun.upitracker.database.entity.TransactionCategorySplit::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): com.varun.upitracker.database.dao.TransactionDao
    abstract fun friendDao(): com.varun.upitracker.database.dao.FriendDao
    abstract fun iouDao(): com.varun.upitracker.database.dao.IouDao
    abstract fun transactionShareDao(): com.varun.upitracker.database.dao.TransactionShareDao
    abstract fun merchantDao(): com.varun.upitracker.database.dao.MerchantDao
    abstract fun categoryDao(): com.varun.upitracker.database.dao.CategoryDao
    abstract fun appSettingsDao(): com.varun.upitracker.database.dao.AppSettingsDao
    abstract fun budgetDao(): com.varun.upitracker.database.dao.BudgetDao
    abstract fun categorySplitDao(): com.varun.upitracker.database.dao.TransactionCategorySplitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val DEFAULT_CATEGORIES = listOf(
            "Food & Drink", "Entertainment", "Transport", "Essentials"
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "upi_tracker_db"
                )
                    .fallbackToDestructiveMigration(true)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val instance = getInstance(context)
                                DEFAULT_CATEGORIES.forEach { name ->
                                    instance.categoryDao().insertCategory(
                                        _root_ide_package_.com.varun.upitracker.database.entity.Category(
                                            name = name
                                        )
                                    )
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
