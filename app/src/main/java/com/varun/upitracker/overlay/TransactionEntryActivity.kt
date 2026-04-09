package com.varun.upitracker.overlay

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.*
import kotlinx.coroutines.*

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    enum class FriendState { NONE, IOU, PARTY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_transaction)

        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L)
        if (transactionId == -1L) {
            finish()
            return
        }

        loadData(transactionId)
    }

    private fun loadData(transactionId: Long) {
        activityScope.launch {
            val db = AppDatabase.getInstance(applicationContext)

            val transaction = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionById(transactionId)
            } ?: run { finish(); return@launch }

            val friends = withContext(Dispatchers.IO) { db.friendDao().getAllFriendsSync() }
            val categories = withContext(Dispatchers.IO) { db.categoryDao().getAllCategoriesSync() }

            val preSelectedCategories = withContext(Dispatchers.IO) {
                transaction.resolvedMerchantId?.let {
                    db.categoryDao().getCategoriesForMerchant(it)
                } ?: emptyList()
            }

            val preFilledAlias = withContext(Dispatchers.IO) {
                when (transaction.payeeType) {
                    "FRIEND" -> transaction.resolvedFriendId?.let {
                        db.friendDao().getFriendById(it)?.name
                    }
                    "MERCHANT" -> transaction.resolvedMerchantId?.let {
                        db.merchantDao().getMerchantById(it)?.name
                    }
                    else -> null
                } ?: transaction.payeeRaw
            }

            setupUI(transaction, friends, categories, preSelectedCategories, preFilledAlias)
        }
    }

    private fun setupUI(
        transaction: Transaction,
        friends: List<Friend>,
        categories: List<Category>,
        preSelectedCategories: List<Category>,
        preFilledAlias: String
    ) {
        val btnFriend = findViewById<ToggleButton>(R.id.btnFriendToggle)
        val btnClose = findViewById<TextView>(R.id.btnClose)
        val categorySection = findViewById<View>(R.id.categorySection)
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupCategories)
        val tvDirection = findViewById<TextView>(R.id.tvDirection)
        val etAlias = findViewById<EditText>(R.id.etAlias)
        val etAmount = findViewById<EditText>(R.id.etAmount)
        val friendsRow = findViewById<LinearLayout>(R.id.friendsRow)
        val btnDone = findViewById<Button>(R.id.btnDone)
        val btnCustomSplit = findViewById<Button>(R.id.btnCustomSplit)

        // --- State ---
        val selectedFriends = mutableMapOf<Long, FriendState>()
        var isFriendMode = transaction.payeeType == "FRIEND"

        // Pre-select resolved friend into IOU state
        transaction.resolvedFriendId?.let { selectedFriends[it] = FriendState.IOU }

        // --- Pre-fill ---
        tvDirection.text = if (transaction.direction == "DEBIT") "⬆ DEBIT" else "⬇ CREDIT"
        etAmount.setText("%.2f".format(transaction.amountPaise / 100.0))
        etAlias.setText(preFilledAlias)
        btnFriend.isChecked = isFriendMode

        // --- Categories ---
        categories.forEach { cat ->
            val chip = Chip(this).apply {
                text = cat.name
                isCheckable = true
                tag = cat.id
                isChecked = preSelectedCategories.any { it.id == cat.id }
            }
            chipGroup.addView(chip)
        }

        fun updateCategoryVisibility() {
            categorySection.visibility = if (isFriendMode) View.GONE else View.VISIBLE
        }
        updateCategoryVisibility()

        btnFriend.setOnCheckedChangeListener { _, checked ->
            isFriendMode = checked
            updateCategoryVisibility()
        }

        // --- Friends row ---
        fun rebuildFriendsRow() {
            friendsRow.removeAllViews()
            friends.forEach { friend ->
                val state = selectedFriends[friend.id] ?: FriendState.NONE
                friendsRow.addView(createFriendAvatar(friend, state) {
                    val next = when (state) {
                        FriendState.NONE -> FriendState.IOU
                        FriendState.IOU -> FriendState.PARTY
                        FriendState.PARTY -> FriendState.NONE
                    }
                    if (next == FriendState.NONE) selectedFriends.remove(friend.id)
                    else selectedFriends[friend.id] = next
                    rebuildFriendsRow()
                })
            }
            friendsRow.addView(createAddButton {
                Toast.makeText(this, "Friend search — coming soon", Toast.LENGTH_SHORT).show()
            })
        }
        rebuildFriendsRow()

        // --- Buttons ---
        btnClose.setOnClickListener { finish() }

        btnCustomSplit.setOnClickListener {
            Toast.makeText(this, "Custom split — coming soon", Toast.LENGTH_SHORT).show()
        }

        btnDone.setOnClickListener {
            val alias = etAlias.text.toString().trim()
            val amountText = etAmount.text.toString().trim()
            val amountPaise = (amountText.toDoubleOrNull()?.let { it * 100 }?.toLong()) ?: transaction.amountPaise
            val selectedCategoryIds = chipGroup.checkedChipIds.mapNotNull { id ->
                chipGroup.findViewById<Chip>(id)?.tag as? Long
            }
            activityScope.launch {
                saveDone(transaction.copy(amountPaise = amountPaise), alias, isFriendMode, selectedFriends, selectedCategoryIds)
                finish()
            }
        }
    }

    private suspend fun saveDone(
        transaction: Transaction,
        aliasText: String,
        isFriendMode: Boolean,
        selectedFriends: Map<Long, FriendState>,
        selectedCategoryIds: List<Long>
    ) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(applicationContext)

        if (isFriendMode) {
            val iouEntries = selectedFriends.filter { it.value == FriendState.IOU }
            val partyEntries = selectedFriends.filter { it.value == FriendState.PARTY }

            val payeeFriendId = if (iouEntries.isNotEmpty()) {
                val fid = iouEntries.keys.first()
                val existing = db.friendDao().getFriendById(fid)
                if (existing != null && existing.name != aliasText && aliasText.isNotEmpty()) {
                    db.friendDao().updateFriend(existing.copy(name = aliasText))
                }
                db.friendDao().insertRawName(FriendRawName(friendId = fid, rawName = transaction.payeeRaw))
                fid
            } else {
                val initials = aliasText.split(" ").filter { it.isNotEmpty() }.take(2)
                    .joinToString("") { it.first().uppercaseChar().toString() }
                val newFriendId = db.friendDao().insertFriend(
                    Friend(name = aliasText, avatarInitials = initials, addedEpoch = System.currentTimeMillis())
                )
                db.friendDao().insertRawName(FriendRawName(friendId = newFriendId, rawName = transaction.payeeRaw))
                newFriendId
            }

            db.transactionDao().update(
                transaction.copy(payeeType = "FRIEND", resolvedFriendId = payeeFriendId, isPending = false)
            )

            val splitPaise = if (iouEntries.isNotEmpty()) transaction.amountPaise / (iouEntries.size + 1) else 0L
            iouEntries.keys.forEach { fid ->
                db.iouDao().insert(IouEntry(transactionId = transaction.id, friendId = fid, amountPaise = splitPaise))
            }

            val headCount = iouEntries.size + partyEntries.size + 1
            partyEntries.keys.forEach { fid ->
                db.transactionPartyDao().insert(TransactionParty(
                    transactionId = transaction.id,
                    friendId = fid,
                    spentOnThemPaise = transaction.amountPaise / headCount
                ))
            }
        } else {
            val merchantId = if (transaction.resolvedMerchantId != null) {
                val existing = db.merchantDao().getMerchantById(transaction.resolvedMerchantId)
                if (existing != null && existing.name != aliasText && aliasText.isNotEmpty()) {
                    db.merchantDao().updateMerchant(existing.copy(name = aliasText))
                }
                db.merchantDao().insertRawName(MerchantRawName(merchantId = transaction.resolvedMerchantId, rawName = transaction.payeeRaw))
                transaction.resolvedMerchantId
            } else {
                val newId = db.merchantDao().insertMerchant(Merchant(name = aliasText, addedEpoch = System.currentTimeMillis()))
                db.merchantDao().insertRawName(MerchantRawName(merchantId = newId, rawName = transaction.payeeRaw))
                newId
            }

            selectedCategoryIds.forEach { catId ->
                db.categoryDao().linkMerchantCategory(MerchantCategory(merchantId = merchantId!!, categoryId = catId))
            }

            db.transactionDao().update(
                transaction.copy(payeeType = "MERCHANT", resolvedMerchantId = merchantId, isPending = false)
            )

            val iouEntries = selectedFriends.filter { it.value == FriendState.IOU }
            val partyEntries = selectedFriends.filter { it.value == FriendState.PARTY }
            val headCount = iouEntries.size + partyEntries.size + 1
            val splitPaise = transaction.amountPaise / headCount

            iouEntries.keys.forEach { fid ->
                db.iouDao().insert(IouEntry(transactionId = transaction.id, friendId = fid, amountPaise = splitPaise))
            }
            partyEntries.keys.forEach { fid ->
                db.transactionPartyDao().insert(TransactionParty(
                    transactionId = transaction.id,
                    friendId = fid,
                    spentOnThemPaise = splitPaise
                ))
            }
        }
    }

    private fun createFriendAvatar(friend: Friend, state: FriendState, onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        val size64 = (64 * dp).toInt()
        val size20 = (20 * dp).toInt()
        val size100 = (100 * dp).toInt()
        val margin8 = (8 * dp).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size64, size100).apply { marginEnd = margin8 }
        }

        val circle = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size64, size64).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            text = friend.avatarInitials
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            background = circleDrawable(
                fill = Color.parseColor("#5C6BC0"),
                border = if (state != FriendState.NONE) Color.BLACK else Color.TRANSPARENT,
                stroke = if (state != FriendState.NONE) (3 * dp).toInt() else 0
            )
        }
        container.addView(circle)

        if (state == FriendState.PARTY) {
            val badge = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size20, size20).apply { gravity = Gravity.TOP or Gravity.END }
                text = "P"
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(Color.WHITE)
                background = circleDrawable(Color.parseColor("#43A047"), Color.TRANSPARENT, 0)
            }
            container.addView(badge)
        }

        val label = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            text = friend.name.split(" ").first()
            gravity = Gravity.CENTER
            textSize = 10f
            maxLines = 1
        }
        container.addView(label)
        container.setOnClickListener { onClick() }
        return container
    }

    private fun createAddButton(onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        val size64 = (64 * dp).toInt()
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size64, size64)
            text = "+"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(Color.DKGRAY)
            background = circleDrawable(Color.LTGRAY, Color.TRANSPARENT, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun circleDrawable(fill: Int, border: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke > 0) setStroke(stroke, border)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
