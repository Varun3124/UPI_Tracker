package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.MerchantCategory

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: com.varun.upitracker.database.entity.Category): Long

    @Update
    suspend fun updateCategory(category: com.varun.upitracker.database.entity.Category)

    @Delete
    suspend fun deleteCategory(category: com.varun.upitracker.database.entity.Category)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkMerchantCategory(link: com.varun.upitracker.database.entity.MerchantCategory)

    @Delete
    suspend fun unlinkMerchantCategory(link: com.varun.upitracker.database.entity.MerchantCategory)

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): LiveData<List<com.varun.upitracker.database.entity.Category>>

    // Get all categories for a merchant
    @Query("""
        SELECT c.* FROM categories c
        INNER JOIN merchant_categories mc ON c.id = mc.categoryId
        WHERE mc.merchantId = :merchantId
    """)
    suspend fun getCategoriesForMerchant(merchantId: Long): List<com.varun.upitracker.database.entity.Category>

    // Get all merchants in a category
    @Query("""
        SELECT merchantId FROM merchant_categories 
        WHERE categoryId = :categoryId
    """)
    suspend fun getMerchantIdsForCategory(categoryId: Long): List<Long>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAllCategoriesSync(): List<com.varun.upitracker.database.entity.Category>

    @Query("SELECT * FROM categories WHERE id = :categoryId LIMIT 1")
    suspend fun getCategoryById(categoryId: Long): com.varun.upitracker.database.entity.Category?

    @Query("SELECT * FROM categories WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByNormalizedName(name: String): com.varun.upitracker.database.entity.Category?

    @Query("SELECT COUNT(*) FROM merchant_categories WHERE categoryId = :categoryId")
    suspend fun getMerchantLinkCount(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM transaction_category_splits WHERE categoryId = :categoryId")
    suspend fun getSplitCount(categoryId: Long): Int
}
