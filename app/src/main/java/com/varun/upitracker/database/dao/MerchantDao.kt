package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId

@Dao
interface MerchantDao {

    @Insert
    suspend fun insertMerchant(merchant: Merchant): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawName(rawName: MerchantRawName)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpiId(upiId: MerchantUpiId)

    @Update
    suspend fun updateMerchant(merchant: Merchant)

    @Query("SELECT * FROM merchants ORDER BY name ASC")
    fun getAllMerchants(): LiveData<List<Merchant>>

    @Query("SELECT * FROM merchants WHERE id = :id")
    suspend fun getMerchantById(id: Long): Merchant?

    // Primary debit resolution
    @Query("SELECT * FROM merchant_raw_names WHERE rawName = :rawName LIMIT 1")
    suspend fun findByRawName(rawName: String): MerchantRawName?

    // Credit resolution (refunds/prizes)
    @Query("SELECT * FROM merchant_upi_ids WHERE upiId = :upiId LIMIT 1")
    suspend fun findByUpiId(upiId: String): MerchantUpiId?

    @Query("SELECT * FROM merchant_raw_names WHERE merchantId = :merchantId")
    suspend fun getRawNamesForMerchant(merchantId: Long): List<MerchantRawName>

    @Query("SELECT * FROM merchants WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Merchant?

    @Query("SELECT * FROM merchants ORDER BY name ASC")
    suspend fun getAllMerchantsSync(): List<Merchant>

    @Delete
    suspend fun deleteMerchant(merchant: Merchant)

    @Delete
    suspend fun deleteRawName(rawName: MerchantRawName)
}