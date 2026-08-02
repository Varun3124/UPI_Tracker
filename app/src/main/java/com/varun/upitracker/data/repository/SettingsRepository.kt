package com.varun.upitracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantCategory
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.model.FriendAliasBundle
import com.varun.upitracker.database.model.MerchantAliasBundle
import com.varun.upitracker.sms.SmsBacklogScanner

class SettingsRepository(private val context: Context) {

    private val db by lazy { AppDatabase.getInstance(context) }
    private val prefs by lazy {
        context.getSharedPreferences(SmsBacklogScanner.PREF_NAME, Context.MODE_PRIVATE)
    }


    suspend fun getCategories(): List<Category> = db.categoryDao().getAllCategoriesSync()

    suspend fun isCategoryInUse(categoryId: Long): Boolean {
        return db.categoryDao().getMerchantLinkCount(categoryId) > 0 ||
            db.categoryDao().getSplitCount(categoryId) > 0
    }

    suspend fun getReplacementCategories(excludingCategoryId: Long): List<Category> {
        return db.categoryDao()
            .getAllCategoriesSync()
            .filter { it.id != excludingCategoryId }
    }

    suspend fun createCategory(name: String) {
        val normalizedName = requireName(name, "Category")
        db.withTransaction {
            val existing = db.categoryDao().findByNormalizedName(normalizedName)
            if (existing == null) {
                db.categoryDao().insertCategory(Category(name = normalizedName))
            } else if (existing.name != normalizedName) {
                db.categoryDao().updateCategory(existing.copy(name = normalizedName))
            }
        }
    }

    suspend fun renameCategory(categoryId: Long, name: String) {
        val normalizedName = requireName(name, "Category")
        db.withTransaction {
            val source = db.categoryDao().getCategoryById(categoryId)
                ?: throw SettingsMutationException("Category no longer exists.")
            val existing = db.categoryDao().findByNormalizedName(normalizedName)
            when {
                existing == null -> db.categoryDao().updateCategory(source.copy(name = normalizedName))
                existing.id == source.id -> db.categoryDao().updateCategory(source.copy(name = normalizedName))
                else -> {
                    mergeCategoryInto(source.id, existing.id)
                    if (existing.name != normalizedName) {
                        db.categoryDao().updateCategory(existing.copy(name = normalizedName))
                    }
                }
            }
        }
    }

    suspend fun deleteCategory(categoryId: Long, replacementCategoryId: Long? = null) {
        db.withTransaction {
            val category = db.categoryDao().getCategoryById(categoryId)
                ?: throw SettingsMutationException("Category no longer exists.")
            val isUsed = db.categoryDao().getMerchantLinkCount(categoryId) > 0 ||
                db.categoryDao().getSplitCount(categoryId) > 0
            if (!isUsed) {
                db.categoryDao().deleteCategory(category)
                return@withTransaction
            }
            val replacementId = replacementCategoryId
                ?: throw SettingsMutationException("Choose a replacement category before deleting this one.")
            if (replacementId == categoryId) {
                throw SettingsMutationException("Replacement category must be different.")
            }
            val replacement = db.categoryDao().getCategoryById(replacementId)
                ?: throw SettingsMutationException("Replacement category no longer exists.")
            mergeCategoryInto(category.id, replacement.id)
        }
    }

    suspend fun getFriendAliasBundles(): List<FriendAliasBundle> = db.friendDao().getAliasBundles()

    suspend fun getMerchantAliasBundles(): List<MerchantAliasBundle> = db.merchantDao().getAliasBundles()

    suspend fun createFriendAlias(name: String) {
        val alias = requireName(name, "Alias")
        db.withTransaction {
            val existing = db.friendDao().findByNormalizedName(alias)
            if (existing == null) {
                db.friendDao().insertFriend(
                    Friend(
                        name = alias,
                        avatarInitials = aliasInitials(alias),
                        addedEpoch = System.currentTimeMillis()
                    )
                )
            } else if (existing.name != alias) {
                db.friendDao().updateFriend(existing.copy(name = alias, avatarInitials = aliasInitials(alias)))
            }
        }
    }

    suspend fun createMerchantAlias(name: String) {
        val alias = requireName(name, "Alias")
        db.withTransaction {
            val existing = db.merchantDao().findByNormalizedName(alias)
            if (existing == null) {
                db.merchantDao().insertMerchant(Merchant(name = alias, addedEpoch = System.currentTimeMillis()))
            } else if (existing.name != alias) {
                db.merchantDao().updateMerchant(existing.copy(name = alias))
            }
        }
    }

    suspend fun renameFriendAlias(friendId: Long, newName: String) {
        val alias = requireName(newName, "Alias")
        db.withTransaction {
            val source = db.friendDao().getFriendById(friendId)
                ?: throw SettingsMutationException("Alias no longer exists.")
            val existing = db.friendDao().findByNormalizedName(alias)
            when {
                existing == null -> {
                    db.friendDao().updateFriend(source.copy(name = alias, avatarInitials = aliasInitials(alias)))
                }
                existing.id == source.id -> {
                    db.friendDao().updateFriend(source.copy(name = alias, avatarInitials = aliasInitials(alias)))
                }
                else -> {
                    mergeFriendInto(source, existing, alias)
                }
            }
        }
    }

    suspend fun renameMerchantAlias(merchantId: Long, newName: String) {
        val alias = requireName(newName, "Alias")
        db.withTransaction {
            val source = db.merchantDao().getMerchantById(merchantId)
                ?: throw SettingsMutationException("Alias no longer exists.")
            val existing = db.merchantDao().findByNormalizedName(alias)
            when {
                existing == null -> db.merchantDao().updateMerchant(source.copy(name = alias))
                existing.id == source.id -> db.merchantDao().updateMerchant(source.copy(name = alias))
                else -> mergeMerchantInto(source, existing, alias)
            }
        }
    }

    suspend fun deleteFriendAlias(friendId: Long) {
        db.withTransaction {
            val friend = db.friendDao().getFriendById(friendId)
                ?: throw SettingsMutationException("Alias no longer exists.")
            if (friendHasHistory(friendId)) {
                throw SettingsMutationException("This alias is already used in transaction history and cannot be deleted.")
            }
            db.friendDao().deleteFriend(friend)
        }
    }

    suspend fun deleteMerchantAlias(merchantId: Long) {
        db.withTransaction {
            val merchant = db.merchantDao().getMerchantById(merchantId)
                ?: throw SettingsMutationException("Alias no longer exists.")
            if (merchantHasHistory(merchantId)) {
                throw SettingsMutationException("This alias is already used in transaction history and cannot be deleted.")
            }
            db.merchantDao().deleteMerchant(merchant)
        }
    }

    suspend fun deleteFriendRawName(rawName: FriendRawName) {
        db.withTransaction { db.friendDao().deleteRawName(rawName) }
    }

    suspend fun deleteFriendUpiId(upiId: FriendUpiId) {
        db.withTransaction { db.friendDao().deleteUpiId(upiId) }
    }

    suspend fun deleteMerchantRawName(rawName: MerchantRawName) {
        db.withTransaction { db.merchantDao().deleteRawName(rawName) }
    }

    suspend fun deleteMerchantUpiId(upiId: MerchantUpiId) {
        db.withTransaction { db.merchantDao().deleteUpiId(upiId) }
    }

    suspend fun moveFriendRawName(mappingId: Long, destinationAlias: String) {
        db.withTransaction {
            val target = getOrCreateFriendAlias(destinationAlias)
            db.friendDao().reassignRawName(mappingId, target.id)
        }
    }

    suspend fun moveFriendUpiId(mappingId: Long, destinationAlias: String) {
        db.withTransaction {
            val target = getOrCreateFriendAlias(destinationAlias)
            db.friendDao().reassignUpiId(mappingId, target.id)
        }
    }

    suspend fun moveMerchantRawName(mappingId: Long, destinationAlias: String) {
        db.withTransaction {
            val target = getOrCreateMerchantAlias(destinationAlias)
            db.merchantDao().reassignRawName(mappingId, target.id)
        }
    }

    suspend fun moveMerchantUpiId(mappingId: Long, destinationAlias: String) {
        db.withTransaction {
            val target = getOrCreateMerchantAlias(destinationAlias)
            db.merchantDao().reassignUpiId(mappingId, target.id)
        }
    }

    private suspend fun getOrCreateFriendAlias(name: String): Friend {
        val alias = requireName(name, "Destination alias")
        val existing = db.friendDao().findByNormalizedName(alias)
        if (existing != null) {
            if (existing.name != alias) {
                db.friendDao().updateFriend(existing.copy(name = alias, avatarInitials = aliasInitials(alias)))
            }
            return existing.copy(name = alias, avatarInitials = aliasInitials(alias))
        }
        val id = db.friendDao().insertFriend(
            Friend(
                name = alias,
                avatarInitials = aliasInitials(alias),
                addedEpoch = System.currentTimeMillis()
            )
        )
        return db.friendDao().getFriendById(id) ?: throw SettingsMutationException("Could not create destination alias.")
    }

    private suspend fun getOrCreateMerchantAlias(name: String): Merchant {
        val alias = requireName(name, "Destination alias")
        val existing = db.merchantDao().findByNormalizedName(alias)
        if (existing != null) {
            if (existing.name != alias) {
                db.merchantDao().updateMerchant(existing.copy(name = alias))
            }
            return existing.copy(name = alias)
        }
        val id = db.merchantDao().insertMerchant(
            Merchant(
                name = alias,
                addedEpoch = System.currentTimeMillis()
            )
        )
        return db.merchantDao().getMerchantById(id) ?: throw SettingsMutationException("Could not create destination alias.")
    }

    private suspend fun mergeCategoryInto(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        db.categoryDao().getMerchantIdsForCategory(sourceId).forEach { merchantId ->
            db.categoryDao().linkMerchantCategory(MerchantCategory(merchantId = merchantId, categoryId = targetId))
            db.categoryDao().unlinkMerchantCategory(MerchantCategory(merchantId = merchantId, categoryId = sourceId))
        }
        db.categorySplitDao().getForCategory(sourceId).forEach { split ->
            val targetSplit = db.categorySplitDao().getByTransactionAndCategory(split.transactionId, targetId)
            if (targetSplit == null) {
                db.categorySplitDao().insert(
                    TransactionCategorySplit(
                        transactionId = split.transactionId,
                        categoryId = targetId,
                        myAmountPaise = split.myAmountPaise,
                        partyAmountPaise = split.partyAmountPaise
                    )
                )
            } else {
                db.categorySplitDao().update(
                    targetSplit.copy(
                        myAmountPaise = targetSplit.myAmountPaise + split.myAmountPaise,
                        partyAmountPaise = targetSplit.partyAmountPaise + split.partyAmountPaise
                    )
                )
            }
            db.categorySplitDao().delete(split)
        }
        db.categoryDao().getCategoryById(sourceId)?.let { db.categoryDao().deleteCategory(it) }
    }

    private suspend fun mergeFriendInto(source: Friend, target: Friend, targetName: String) {
        if (source.id == target.id) return
        db.transactionDao().reassignPayerFriend(source.id, target.id)
        db.transactionDao().reassignPayeeFriend(source.id, target.id)
        db.transactionShareDao().reassignFriend(source.id, target.id)
        db.iouDao().reassignFriend(source.id, target.id)
        db.friendDao().moveAllRawNames(source.id, target.id)
        db.friendDao().moveAllUpiIds(source.id, target.id)
        db.friendDao().updateFriend(target.copy(name = targetName, avatarInitials = aliasInitials(targetName)))
        db.friendDao().deleteFriend(source)
    }

    private suspend fun mergeMerchantInto(source: Merchant, target: Merchant, targetName: String) {
        if (source.id == target.id) return
        db.transactionDao().reassignPayerMerchant(source.id, target.id)
        db.transactionDao().reassignPayeeMerchant(source.id, target.id)
        db.merchantDao().moveAllRawNames(source.id, target.id)
        db.merchantDao().moveAllUpiIds(source.id, target.id)
        db.categoryDao().getCategoriesForMerchant(source.id).forEach { category ->
            db.categoryDao().linkMerchantCategory(MerchantCategory(target.id, category.id))
        }
        db.categoryDao().getCategoriesForMerchant(source.id).forEach { category ->
            db.categoryDao().unlinkMerchantCategory(MerchantCategory(source.id, category.id))
        }
        db.merchantDao().updateMerchant(target.copy(name = targetName))
        db.merchantDao().deleteMerchant(source)
    }

    private suspend fun friendHasHistory(friendId: Long): Boolean {
        return db.transactionDao().countReferencesForFriend(friendId) > 0 ||
            db.iouDao().countEntriesForFriend(friendId) > 0 ||
            db.transactionShareDao().countForFriend(friendId) > 0
    }

    private suspend fun merchantHasHistory(merchantId: Long): Boolean {
        return db.transactionDao().countReferencesForMerchant(merchantId) > 0
    }

    private fun aliasInitials(label: String): String {
        val initials = label.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
        return initials.ifBlank { "F" }
    }

    private fun requireName(value: String, label: String): String {
        val name = value.trim()
        if (name.isEmpty()) {
            throw SettingsMutationException("$label cannot be empty.")
        }
        return name
    }

    companion object {
        private const val LEGACY_BALANCE_KEY = "total_balance_paise"
    }
}

class SettingsMutationException(message: String) : IllegalStateException(message)
