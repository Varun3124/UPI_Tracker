package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.varun.upitracker.database.model.MerchantAliasBundle

@Dao
interface MerchantDao {

    @Insert
    suspend fun insertMerchant(merchant: com.varun.upitracker.database.entity.Merchant): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawName(rawName: com.varun.upitracker.database.entity.MerchantRawName)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpiId(upiId: com.varun.upitracker.database.entity.MerchantUpiId)

    @Update
    suspend fun updateMerchant(merchant: com.varun.upitracker.database.entity.Merchant)

    @Query("SELECT * FROM merchants ORDER BY name ASC")
    fun getAllMerchants(): LiveData<List<com.varun.upitracker.database.entity.Merchant>>

    @Query("SELECT * FROM merchants WHERE id = :id")
    suspend fun getMerchantById(id: Long): com.varun.upitracker.database.entity.Merchant?

    // Primary debit resolution
    @Query("SELECT * FROM merchant_raw_names WHERE rawName = :rawName LIMIT 1")
    suspend fun findByRawName(rawName: String): com.varun.upitracker.database.entity.MerchantRawName?

    // Credit resolution (refunds/prizes)
    @Query("SELECT * FROM merchant_upi_ids WHERE upiId = :upiId LIMIT 1")
    suspend fun findByUpiId(upiId: String): com.varun.upitracker.database.entity.MerchantUpiId?

    @Query("SELECT * FROM merchant_raw_names WHERE merchantId = :merchantId")
    suspend fun getRawNamesForMerchant(merchantId: Long): List<com.varun.upitracker.database.entity.MerchantRawName>

    @Query("SELECT * FROM merchant_upi_ids WHERE merchantId = :merchantId")
    suspend fun getUpiIdsForMerchant(merchantId: Long): List<com.varun.upitracker.database.entity.MerchantUpiId>

    @Query("SELECT * FROM merchants WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): com.varun.upitracker.database.entity.Merchant?

    @Query("SELECT * FROM merchants WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByNormalizedName(name: String): com.varun.upitracker.database.entity.Merchant?

    @Query("SELECT * FROM merchants ORDER BY name ASC")
    suspend fun getAllMerchantsSync(): List<com.varun.upitracker.database.entity.Merchant>

    @Transaction
    @Query("SELECT * FROM merchants ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getAliasBundles(): List<com.varun.upitracker.database.model.MerchantAliasBundle>

    @Query("UPDATE merchant_raw_names SET merchantId = :merchantId WHERE id = :mappingId")
    suspend fun reassignRawName(mappingId: Long, merchantId: Long)

    @Query("UPDATE merchant_upi_ids SET merchantId = :merchantId WHERE id = :mappingId")
    suspend fun reassignUpiId(mappingId: Long, merchantId: Long)

    @Query("UPDATE merchant_raw_names SET merchantId = :targetId WHERE merchantId = :sourceId")
    suspend fun moveAllRawNames(sourceId: Long, targetId: Long)

    @Query("UPDATE merchant_upi_ids SET merchantId = :targetId WHERE merchantId = :sourceId")
    suspend fun moveAllUpiIds(sourceId: Long, targetId: Long)

    @Delete
    suspend fun deleteMerchant(merchant: com.varun.upitracker.database.entity.Merchant)

    @Delete
    suspend fun deleteRawName(rawName: com.varun.upitracker.database.entity.MerchantRawName)

    @Delete
    suspend fun deleteUpiId(upiId: com.varun.upitracker.database.entity.MerchantUpiId)
}
