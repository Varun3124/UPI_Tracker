package com.varun.upitracker.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId

data class MerchantAliasBundle(
    @Embedded
    val merchant: com.varun.upitracker.database.entity.Merchant,
    @Relation(parentColumn = "id", entityColumn = "merchantId")
    val rawNames: List<com.varun.upitracker.database.entity.MerchantRawName>,
    @Relation(parentColumn = "id", entityColumn = "merchantId")
    val upiIds: List<com.varun.upitracker.database.entity.MerchantUpiId>
)
