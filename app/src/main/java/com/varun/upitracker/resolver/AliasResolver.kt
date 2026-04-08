package com.varun.upitracker.resolver

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.Friend

sealed class ResolvedAs {
    data class AsFriend(
        val friendId: Long,
        val name: String,
        val isConfident: Boolean   // false = raw name match, show overlay for confirmation
    ) : ResolvedAs()

    data class AsMerchant(
        val merchantId: Long,
        val name: String,
        val categories: List<String>
    ) : ResolvedAs()

    object Unknown : ResolvedAs()
}

class AliasResolver(private val db: AppDatabase) {

    /**
     * Main entry point. Call this after parsing an SMS.
     * @param payeeRaw  the raw string from the SMS
     * @param direction "CREDIT" or "DEBIT"
     */
    suspend fun resolve(payeeRaw: String, direction: String): ResolvedAs {
        return when (direction) {
            "CREDIT" -> resolveCredit(payeeRaw)
            "DEBIT"  -> resolveDebit(payeeRaw)
            else     -> ResolvedAs.Unknown
        }
    }

    // ------------------------------------------------------------------
    // CREDIT: payeeRaw is a UPI ID e.g. "preetsangani9@pingpay"
    // ------------------------------------------------------------------

    private suspend fun resolveCredit(upiId: String): ResolvedAs {
        // 1. Check friends first — most credits are repayments
        val friendUpi = db.friendDao().findByUpiId(upiId)
        if (friendUpi != null) {
            val friend = db.friendDao().getFriendById(friendUpi.friendId)
            if (friend != null) {
                return ResolvedAs.AsFriend(
                    friendId    = friend.id,
                    name        = friend.name,
                    isConfident = true   // UPI ID match is unambiguous
                )
            }
        }

        // 2. Check merchants — refunds and prizes arrive via UPI too
        val merchantUpi = db.merchantDao().findByUpiId(upiId)
        if (merchantUpi != null) {
            val merchant = db.merchantDao().getMerchantById(merchantUpi.merchantId)
            if (merchant != null) {
                val categories = db.categoryDao()
                    .getCategoriesForMerchant(merchant.id)
                    .map { it.name }
                return ResolvedAs.AsMerchant(
                    merchantId = merchant.id,
                    name       = merchant.name,
                    categories = categories
                )
            }
        }

        return ResolvedAs.Unknown
    }

    // ------------------------------------------------------------------
    // DEBIT: payeeRaw is a display name e.g. "MAHESHBHAI RAMANBHAI RAVA"
    // ------------------------------------------------------------------

    private suspend fun resolveDebit(rawName: String): ResolvedAs {
        val normalised = rawName.trim()

        // 1. Check merchants first — majority of debits are merchant payments
        val merchantRaw = db.merchantDao().findByRawName(normalised)
        if (merchantRaw != null) {
            val merchant = db.merchantDao().getMerchantById(merchantRaw.merchantId)
            if (merchant != null) {
                val categories = db.categoryDao()
                    .getCategoriesForMerchant(merchant.id)
                    .map { it.name }
                return ResolvedAs.AsMerchant(
                    merchantId = merchant.id,
                    name       = merchant.name,
                    categories = categories
                )
            }
        }

        // 2. Check friends — show pre-filled overlay but always require confirmation
        val friendRaw = db.friendDao().findByRawName(normalised)
        if (friendRaw != null) {
            val friend = db.friendDao().getFriendById(friendRaw.friendId)
            if (friend != null) {
                return ResolvedAs.AsFriend(
                    friendId    = friend.id,
                    name        = friend.name,
                    isConfident = false  // Raw name match — always confirm in overlay
                )
            }
        }

        return ResolvedAs.Unknown
    }
}