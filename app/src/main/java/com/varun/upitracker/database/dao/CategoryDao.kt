package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.MerchantCategory

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkMerchantCategory(link: MerchantCategory)

    @Delete
    suspend fun unlinkMerchantCategory(link: MerchantCategory)

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): LiveData<List<Category>>

    // Get all categories for a merchant
    @Query("""
        SELECT c.* FROM categories c
        INNER JOIN merchant_categories mc ON c.id = mc.categoryId
        WHERE mc.merchantId = :merchantId
    """)
    suspend fun getCategoriesForMerchant(merchantId: Long): List<Category>

    // Get all merchants in a category
    @Query("""
        SELECT merchantId FROM merchant_categories 
        WHERE categoryId = :categoryId
    """)
    suspend fun getMerchantIdsForCategory(categoryId: Long): List<Long>
}