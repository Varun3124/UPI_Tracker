package com.varun.upitracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.varun.upitracker.database.dao.*
import com.varun.upitracker.database.entity.*
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
        IouEntry::class,
        Merchant::class,
        MerchantRawName::class,
        MerchantUpiId::class,
        Category::class,
        MerchantCategory::class,
        BudgetSettings::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun friendDao(): FriendDao
    abstract fun iouDao(): IouDao
    abstract fun transactionPartyDao(): TransactionPartyDao
    abstract fun merchantDao(): MerchantDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val DEFAULT_CATEGORIES = listOf(
            "Food", "Transport", "Shopping", "Entertainment",
            "Movies", "Health", "Utilities", "Travel", "Other"
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "upi_tracker_db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Pre-seed categories on first creation
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