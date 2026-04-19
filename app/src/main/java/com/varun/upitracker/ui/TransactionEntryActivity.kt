package com.varun.upitracker.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.Chip
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantCategory
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.receiver.TransactionNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"
    }

    private enum class ShareUiState { NONE, PAY, RECEIVE }

    private data class CategoryEntry(
        val category: Category,
        val chip: Chip,
        val expansionLayout: LinearLayout,
        val etMyAmount: EditText
    )

    private data class SharePerson(
        val key: String,
        val participantType: String,
        val friendId: Long? = null,
        val label: String,
        val initials: String
    )

    private data class ShareDraft(
        var state: ShareUiState = ShareUiState.NONE,
        var amountPaise: Long? = null
    )

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentTransaction: Transaction? = null
    private var isSmsSource = false
    private var allFriends = listOf<Friend>()
    private var allMerchants = listOf<Merchant>()
    private var allCategories = listOf<Category>()

    private var payerActorType = ActorType.ME
    private var payeeActorType = ActorType.MERCHANT
    private var payerFriendId: Long? = null
    private var payerMerchantId: Long? = null
    private var payeeFriendId: Long? = null
    private var payeeMerchantId: Long? = null

    private val categoryEntries = mutableListOf<CategoryEntry>()
    private val shareDrafts = mutableMapOf<String, ShareDraft>()
    private var sharePeople = listOf<SharePerson>()

    private lateinit var tvTopInfo: TextView
    private lateinit var rgPayerType: RadioGroup
    private lateinit var rgPayeeType: RadioGroup
    private lateinit var rbPayerMe: RadioButton
    private lateinit var rbPayerFriend: RadioButton
    private lateinit var rbPayerMerchant: RadioButton
    private lateinit var rbPayeeMe: RadioButton
    private lateinit var rbPayeeFriend: RadioButton
    private lateinit var rbPayeeMerchant: RadioButton
    private lateinit var etPayerAlias: AutoCompleteTextView
    private lateinit var etPayeeAlias: AutoCompleteTextView
    private lateinit var etAmount: EditText
    private lateinit var tvMeantPay: TextView
    private lateinit var tvMeantReceive: TextView
    private lateinit var tvBalance: TextView
    private lateinit var categoryContainer: LinearLayout
    private lateinit var shutterPanel: LinearLayout
    private lateinit var dragHandle: View
    private lateinit var friendsGrid: LinearLayout

    private var peekHeight = 0
    private var expandedHeight = 0
    private var shouldAutoloadMerchantCategories = true
    private var smsPayerAliasFallback = ""
    private var smsPayeeAliasFallback = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_transaction)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        peekHeight = dp(120)
        expandedHeight = dp(360)

        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it != -1L }
        activityScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            allFriends = withContext(Dispatchers.IO) { db.friendDao().getAllFriendsByFrequency() }
            allMerchants = withContext(Dispatchers.IO) { db.merchantDao().getAllMerchantsSync() }
            allCategories = withContext(Dispatchers.IO) { db.categoryDao().getAllCategoriesSync() }
            currentTransaction = transactionId?.let {
                withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(it) }
            }
            setupUi(db)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun bindViews() {
        tvTopInfo = findViewById(R.id.tvTopInfo)
        rgPayerType = findViewById(R.id.rgPayerType)
        rgPayeeType = findViewById(R.id.rgPayeeType)
        rbPayerMe = findViewById(R.id.rbPayerMe)
        rbPayerFriend = findViewById(R.id.rbPayerFriend)
        rbPayerMerchant = findViewById(R.id.rbPayerMerchant)
        rbPayeeMe = findViewById(R.id.rbPayeeMe)
        rbPayeeFriend = findViewById(R.id.rbPayeeFriend)
        rbPayeeMerchant = findViewById(R.id.rbPayeeMerchant)
        etPayerAlias = findViewById(R.id.etPayerAlias)
        etPayeeAlias = findViewById(R.id.etPayeeAlias)
        etAmount = findViewById(R.id.etAmount)
        tvMeantPay = findViewById(R.id.tvMeantPay)
        tvMeantReceive = findViewById(R.id.tvMeantReceive)
        tvBalance = findViewById(R.id.tvBalance)
        categoryContainer = findViewById(R.id.categoryContainer)
        shutterPanel = findViewById(R.id.shutterPanel)
        dragHandle = findViewById(R.id.dragHandle)
        friendsGrid = findViewById(R.id.friendsGrid)
    }

    private suspend fun setupUi(db: AppDatabase) {
        val tx = currentTransaction
        isSmsSource = tx?.source == "SMS"
        tvTopInfo.text = when {
            tx != null -> "${tx.payeeRaw.ifEmpty { "Manual Entry" }} • ${fmtDateTime(tx.dateEpoch)}"
            else -> "Manual Entry • ${fmtDateTime(System.currentTimeMillis())}"
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        setupEndpointControls()
        setupAmountField()
        setupCategories()
        setupShutter()

        if (tx != null) populateExistingTransaction(tx, db) else seedDefaultState()

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            activityScope.launch { handleDone() }
        }

        updateAutocomplete(etPayerAlias, payerActorType)
        updateAutocomplete(etPayeeAlias, payeeActorType)
        refreshEndpointInput(etPayerAlias, payerActorType)
        refreshEndpointInput(etPayeeAlias, payeeActorType)
        updateActorTileStyles()
        buildSharePeople()
        applyBlockedShareRules()
        buildFriendsGrid()
        updateLiveCalc()
    }

    private fun setupEndpointControls() {
        rgPayerType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPayerMe && payeeActorType == ActorType.ME) {
                rbPayerMe.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isSmsLockedMeEndpoint(true) && checkedId != R.id.rbPayerMe) {
                rbPayerMe.isChecked = true
                return@setOnCheckedChangeListener
            }
            payerActorType = when (checkedId) {
                R.id.rbPayerFriend -> ActorType.FRIEND
                R.id.rbPayerMerchant -> ActorType.MERCHANT
                else -> ActorType.ME
            }
            payerFriendId = null
            payerMerchantId = null
            shouldAutoloadMerchantCategories = true
            refreshEndpointInput(etPayerAlias, payerActorType)
            updateAutocomplete(etPayerAlias, payerActorType)
            updateActorTileStyles()
            applyBlockedShareRules()
            updateCategoryVisibility()
            updateLiveCalc()
        }

        rgPayeeType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPayeeMe && payerActorType == ActorType.ME) {
                rbPayeeMe.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isSmsLockedMeEndpoint(false) && checkedId != R.id.rbPayeeMe) {
                rbPayeeMe.isChecked = true
                return@setOnCheckedChangeListener
            }
            payeeActorType = when (checkedId) {
                R.id.rbPayeeFriend -> ActorType.FRIEND
                R.id.rbPayeeMerchant -> ActorType.MERCHANT
                else -> ActorType.ME
            }
            payeeFriendId = null
            payeeMerchantId = null
            shouldAutoloadMerchantCategories = true
            refreshEndpointInput(etPayeeAlias, payeeActorType)
            updateAutocomplete(etPayeeAlias, payeeActorType)
            updateActorTileStyles()
            applyBlockedShareRules()
            updateCategoryVisibility()
            updateLiveCalc()
        }

        wireAliasField(etPayerAlias, true)
        wireAliasField(etPayeeAlias, false)
    }

    private fun setupAmountField() {
        etAmount.isEnabled = !isSmsSource || currentTransaction?.amountPaise == 0L
        etAmount.addTextChangedListener(simpleWatcher { updateLiveCalc() })
    }

    private fun setupCategories() {
        categoryEntries.clear()
        categoryContainer.removeAllViews()
        allCategories.forEach { category ->
            val chip = Chip(this).apply {
                text = category.name
                isCheckable = true
                setTextColor(Color.WHITE)
                chipBackgroundColor =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#2A2A2A"))
            }
            val etMy = EditText(this).apply {
                hint = "My amount"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 13f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555"))
                background = null
            }
            val expansion = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(8)
                    marginStart = dp(8)
                }
                addView(etMy)
            }
            chip.setOnCheckedChangeListener { _, checked ->
                expansion.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked && etMy.text.isBlank()) etMy.setText(formatPlainAmount(getMyShareAmount()))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(chip)
                addView(expansion)
            }
            categoryContainer.addView(row)
            categoryEntries += CategoryEntry(category, chip, expansion, etMy)
        }
    }

    private fun setupShutter() {
        dragHandle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(Color.parseColor("#444444"))
        }
        shutterPanel.layoutParams.height = peekHeight
        var startY = 0f
        var startHeight = 0
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = shutterPanel.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    shutterPanel.layoutParams.height =
                        (startHeight - dy.toInt()).coerceIn(peekHeight, expandedHeight)
                    shutterPanel.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val target = if (shutterPanel.height >= (peekHeight + expandedHeight) / 2) expandedHeight else peekHeight
                    ValueAnimator.ofInt(shutterPanel.height, target).apply {
                        duration = 180
                        addUpdateListener {
                            shutterPanel.layoutParams.height = it.animatedValue as Int
                            shutterPanel.requestLayout()
                        }
                        start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private suspend fun populateExistingTransaction(tx: Transaction, db: AppDatabase) {
        if (tx.amountPaise > 0) etAmount.setText("%.2f".format(tx.amountPaise / 100.0))

        if (shouldDefaultSmsDebitToMerchant(tx)) {
            payerActorType = ActorType.ME
            payeeActorType = ActorType.MERCHANT
            payerFriendId = null
            payerMerchantId = null
            payeeFriendId = null
            payeeMerchantId = tx.resolvedMerchantId
        } else {
            payerActorType = tx.payerActorType
            payeeActorType = tx.payeeActorType
            payerFriendId = tx.payerFriendId
            payerMerchantId = tx.payerMerchantId
            payeeFriendId = tx.payeeFriendId
            payeeMerchantId = tx.payeeMerchantId
        }

        when (payerActorType) {
            ActorType.FRIEND -> rbPayerFriend.isChecked = true
            ActorType.MERCHANT -> rbPayerMerchant.isChecked = true
            else -> rbPayerMe.isChecked = true
        }
        when (payeeActorType) {
            ActorType.FRIEND -> rbPayeeFriend.isChecked = true
            ActorType.MERCHANT -> rbPayeeMerchant.isChecked = true
            else -> rbPayeeMe.isChecked = true
        }

        smsPayerAliasFallback = resolveInitialEndpointLabel(db, true, tx)
        smsPayeeAliasFallback = resolveInitialEndpointLabel(db, false, tx)
        etPayerAlias.setText(smsPayerAliasFallback, false)
        etPayeeAlias.setText(smsPayeeAliasFallback, false)
        refreshEndpointInput(etPayerAlias, payerActorType)
        refreshEndpointInput(etPayeeAlias, payeeActorType)
        updateActorTileStyles()

        val shares = withContext(Dispatchers.IO) {
            val stored = db.transactionShareDao().getSharesForTransaction(tx.id)
            if (stored.isNotEmpty()) stored else buildFallbackShares(tx, db)
        }
        seedShareDrafts(shares)

        val splits = withContext(Dispatchers.IO) { db.categorySplitDao().getForTransaction(tx.id) }
        if (splits.isNotEmpty()) {
            shouldAutoloadMerchantCategories = false
            categoryEntries.forEach { entry ->
                val split = splits.firstOrNull { it.categoryId == entry.category.id }
                if (split != null) {
                    entry.chip.isChecked = true
                    entry.etMyAmount.setText(formatPlainAmount(split.myAmountPaise))
                }
            }
        }
    }

    private fun seedDefaultState() {
        payerActorType = ActorType.ME
        payeeActorType = ActorType.MERCHANT
        smsPayerAliasFallback = ""
        smsPayeeAliasFallback = ""
        rbPayerMe.isChecked = true
        rbPayeeMerchant.isChecked = true
        shareDrafts.clear()
        shareDrafts["ME"] = ShareDraft(ShareUiState.PAY, null)
    }

    private fun wireAliasField(field: AutoCompleteTextView, isPayer: Boolean) {
        field.setOnItemClickListener { _, _, position, _ ->
            when (currentActorType(isPayer)) {
                ActorType.FRIEND -> {
                    val friend = allFriends.getOrNull(position)
                    if (isPayer) payerFriendId = friend?.id else payeeFriendId = friend?.id
                }
                ActorType.MERCHANT -> {
                    val merchant = allMerchants.getOrNull(position)
                    if (isPayer) payerMerchantId = merchant?.id else payeeMerchantId = merchant?.id
                    shouldAutoloadMerchantCategories = true
                    updateCategoryVisibility()
                }
            }
        }

        field.addTextChangedListener(simpleWatcher {
            when (currentActorType(isPayer)) {
                ActorType.FRIEND -> {
                    val match = allFriends.firstOrNull { it.name == field.text.toString().trim() }
                    if (isPayer) payerFriendId = match?.id else payeeFriendId = match?.id
                }
                ActorType.MERCHANT -> {
                    val match = allMerchants.firstOrNull { it.name == field.text.toString().trim() }
                    if (isPayer) payerMerchantId = match?.id else payeeMerchantId = match?.id
                }
            }
            restoreRequiredSmsAliasIfNeeded(field, isPayer)
        })
    }

    private fun currentActorType(isPayer: Boolean): String = if (isPayer) payerActorType else payeeActorType

    private fun refreshEndpointInput(field: AutoCompleteTextView, actorType: String) {
        if (actorType == ActorType.ME) {
            field.setText("Me", false)
            field.isEnabled = false
            field.alpha = 0.5f
        } else {
            if (field.text.toString() == "Me") {
                val refill = smsAliasFallback(isPayerField(field))
                field.setText(refill, false)
            }
            field.isEnabled = true
            field.alpha = 1f
            restoreRequiredSmsAliasIfNeeded(field, isPayerField(field))
        }
    }

    private fun isPayerField(field: AutoCompleteTextView): Boolean = field.id == R.id.etPayerAlias

    private fun isSmsLockedMeEndpoint(isPayer: Boolean): Boolean {
        if (!isSmsSource) return false
        val tx = currentTransaction ?: return false
        return if (isPayer) tx.payerActorType == ActorType.ME else tx.payeeActorType == ActorType.ME
    }

    private fun shouldDefaultSmsDebitToMerchant(tx: Transaction): Boolean {
        val observed = tx.observedDirection ?: tx.direction
        return tx.source == "SMS" && tx.isPending && observed == "DEBIT"
    }

    private fun smsAliasFallback(isPayer: Boolean): String {
        return if (isPayer) smsPayerAliasFallback else smsPayeeAliasFallback
    }

    private fun restoreRequiredSmsAliasIfNeeded(field: AutoCompleteTextView, isPayer: Boolean) {
        if (!isSmsSource) return
        if (currentActorType(isPayer) == ActorType.ME) return
        if (field.text.isNullOrBlank()) {
            val refill = smsAliasFallback(isPayer)
            if (refill.isNotBlank()) field.setText(refill, false)
        }
    }

    private fun updateActorTileStyles() {
        val payerCanBeMe = payeeActorType != ActorType.ME || rbPayerMe.isChecked
        val payeeCanBeMe = payerActorType != ActorType.ME || rbPayeeMe.isChecked

        rbPayerMe.isEnabled = payerCanBeMe
        if (isSmsLockedMeEndpoint(true)) {
            rbPayerFriend.isEnabled = false
            rbPayerMerchant.isEnabled = false
        } else {
            rbPayerFriend.isEnabled = true
            rbPayerMerchant.isEnabled = true
        }

        rbPayeeMe.isEnabled = payeeCanBeMe
        if (isSmsLockedMeEndpoint(false)) {
            rbPayeeFriend.isEnabled = false
            rbPayeeMerchant.isEnabled = false
        } else {
            rbPayeeFriend.isEnabled = true
            rbPayeeMerchant.isEnabled = true
        }

        styleTile(rbPayerMe, rbPayerMe.isChecked, isSmsLockedMeEndpoint(true))
        styleTile(rbPayerFriend, rbPayerFriend.isChecked, false)
        styleTile(rbPayerMerchant, rbPayerMerchant.isChecked, false)
        styleTile(rbPayeeMe, rbPayeeMe.isChecked, isSmsLockedMeEndpoint(false))
        styleTile(rbPayeeFriend, rbPayeeFriend.isChecked, false)
        styleTile(rbPayeeMerchant, rbPayeeMerchant.isChecked, false)
    }

    private fun styleTile(button: RadioButton, checked: Boolean, locked: Boolean) {
        val fill = when {
            checked -> Color.parseColor("#00BCD4")
            locked -> Color.parseColor("#1F3E45")
            else -> Color.parseColor("#262626")
        }
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(fill)
            setStroke(dp(1), if (checked) Color.parseColor("#62EFFF") else Color.parseColor("#3A3A3A"))
        }
        button.setTextColor(if (button.isEnabled) Color.WHITE else Color.parseColor("#777777"))
        button.alpha = if (button.isEnabled) 1f else 0.65f
    }

    private fun updateAutocomplete(field: AutoCompleteTextView, actorType: String) {
        val suggestions = when (actorType) {
            ActorType.FRIEND -> allFriends.map { it.name }
            ActorType.MERCHANT -> allMerchants.map { it.name }
            else -> emptyList()
        }
        field.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions))
    }

    private suspend fun resolveInitialEndpointLabel(db: AppDatabase, isPayer: Boolean, tx: Transaction): String {
        if (shouldDefaultSmsDebitToMerchant(tx)) {
            return if (isPayer) {
                "Me"
            } else {
                tx.resolvedMerchantId?.let { db.merchantDao().getMerchantById(it)?.name }
                    ?: tx.payeeRawLabel
                    ?: tx.payeeRaw
            }
        }
        val actor = if (isPayer) tx.payerActorRef() else tx.payeeActorRef()
        return resolveActorDisplayName(db, actor)
    }

    private suspend fun buildFallbackShares(tx: Transaction, db: AppDatabase): List<TransactionShare> {
        val shares = mutableListOf<TransactionShare>()
        val iouEntries = db.iouDao().getEntriesForTransaction(tx.id)
        val partyEntries = db.transactionPartyDao().getPartiesForTransaction(tx.id)
        val fallbackMyAmount = tx.mySharePaise ?: tx.amountPaise
        val fallbackMySide = if (tx.direction == "CREDIT") ShareSide.MEANT_TO_RECEIVE else ShareSide.MEANT_TO_PAY
        if (fallbackMyAmount > 0) {
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.ME,
                shareSide = fallbackMySide,
                amountPaise = fallbackMyAmount
            )
        }

        val positiveFriendAmounts = mutableMapOf<Long, Long>()
        iouEntries.filter { it.amountPaise > 0 }.forEach { entry ->
            positiveFriendAmounts[entry.friendId] = (positiveFriendAmounts[entry.friendId] ?: 0L) + entry.amountPaise
        }
        partyEntries.forEach { entry ->
            positiveFriendAmounts[entry.friendId] = (positiveFriendAmounts[entry.friendId] ?: 0L) + entry.spentOnThemPaise
        }
        positiveFriendAmounts.forEach { (friendId, amount) ->
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.FRIEND,
                friendId = friendId,
                shareSide = ShareSide.MEANT_TO_PAY,
                amountPaise = amount
            )
        }

        if (shares.none { it.participantType == ActorType.FRIEND } && tx.payeeType == "FRIEND" && tx.resolvedFriendId != null) {
            shares += TransactionShare(
                transactionId = tx.id,
                participantType = ActorType.FRIEND,
                friendId = tx.resolvedFriendId,
                shareSide = if (tx.direction == "CREDIT") ShareSide.MEANT_TO_PAY else ShareSide.MEANT_TO_RECEIVE,
                amountPaise = tx.amountPaise
            )
        }
        return shares
    }

    private fun seedShareDrafts(shares: List<TransactionShare>) {
        shareDrafts.clear()
        shares.forEach { share ->
            val key = if (share.participantType == ActorType.ME) "ME" else "F:${share.friendId}"
            shareDrafts[key] = ShareDraft(
                state = if (share.shareSide == ShareSide.MEANT_TO_RECEIVE) ShareUiState.RECEIVE else ShareUiState.PAY,
                amountPaise = share.amountPaise
            )
        }
    }

    private fun buildSharePeople() {
        val people = mutableListOf(SharePerson("ME", ActorType.ME, null, "Me", "ME"))
        allFriends.forEach { people += SharePerson("F:${it.id}", ActorType.FRIEND, it.id, it.name, it.avatarInitials) }
        sharePeople = people
    }

    private fun buildFriendsGrid() {
        friendsGrid.removeAllViews()
        sharePeople.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
            }
            chunk.forEach { row.addView(createShareAvatar(it)) }
            repeat(3 - chunk.size) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(92), 1f) })
            }
            friendsGrid.addView(row)
        }
    }

    private fun createShareAvatar(person: SharePerson): View {
        val state = shareDrafts[person.key]?.state ?: ShareUiState.NONE
        val amountPaise = shareDrafts[person.key]?.amountPaise
        val size = dp(56)
        val badgeSize = dp(18)
        val container = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(92), 1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val circle = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            text = person.initials
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = circleDrawable(
                if (person.participantType == ActorType.ME) Color.parseColor("#00897B") else Color.parseColor("#5C6BC0"),
                if (state != ShareUiState.NONE) Color.WHITE else Color.TRANSPARENT,
                if (state != ShareUiState.NONE) dp(2) else 0
            )
        }
        frame.addView(circle)
        if (state != ShareUiState.NONE) {
            frame.addView(TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply { gravity = Gravity.TOP or Gravity.END }
                gravity = Gravity.CENTER
                text = if (state == ShareUiState.PAY) "P" else "R"
                textSize = 8f
                setTextColor(Color.WHITE)
                background = circleDrawable(
                    if (state == ShareUiState.PAY) Color.parseColor("#EF6C00") else Color.parseColor("#2E7D32"),
                    Color.TRANSPARENT,
                    0
                )
            })
        }
        container.addView(frame)
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
            text = person.label.split(" ").first()
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.WHITE)
            maxLines = 1
        })
        if (amountPaise != null && state != ShareUiState.NONE) {
            container.addView(TextView(this).apply {
                text = "Rs${formatPlainAmount(amountPaise)}"
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(Color.parseColor("#AAAAAA"))
            })
        }
        container.setOnClickListener { cycleShareState(person) }
        container.setOnLongClickListener {
            if ((shareDrafts[person.key]?.state ?: ShareUiState.NONE) != ShareUiState.NONE) showSharePopup(person, frame)
            true
        }
        return container
    }

    private fun cycleShareState(person: SharePerson) {
        val draft = shareDrafts.getOrPut(person.key) { ShareDraft() }
        val payAllowed = isShareSideAllowed(ShareUiState.PAY)
        val receiveAllowed = isShareSideAllowed(ShareUiState.RECEIVE)
        draft.state = when (draft.state) {
            ShareUiState.NONE -> when {
                payAllowed -> ShareUiState.PAY
                receiveAllowed -> ShareUiState.RECEIVE
                else -> ShareUiState.NONE
            }
            ShareUiState.PAY -> if (receiveAllowed) ShareUiState.RECEIVE else ShareUiState.NONE
            ShareUiState.RECEIVE -> ShareUiState.NONE
        }
        if (draft.state == ShareUiState.NONE) {
            shareDrafts.remove(person.key)
        } else if (draft.amountPaise == null || draft.amountPaise == 0L) {
            draft.amountPaise = suggestShareAmount(draft.state)
        }
        buildFriendsGrid()
        updateCategoryVisibility()
        updateLiveCalc()
    }

    private fun showSharePopup(person: SharePerson, anchor: View) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_friend_share, null)
        val popup = PopupWindow(popupView, dp(200), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        popupView.findViewById<TextView>(R.id.tvPopupName).text = person.label
        val etShare = popupView.findViewById<EditText>(R.id.etPopupShare)
        val current = shareDrafts[person.key]?.amountPaise ?: suggestShareAmount(shareDrafts[person.key]?.state ?: ShareUiState.PAY)
        etShare.setText(formatPlainAmount(current))
        etShare.selectAll()
        etShare.requestFocus()
        etShare.addTextChangedListener(simpleWatcher {
            val value = ((etShare.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
            shareDrafts[person.key]?.amountPaise = value
            updateCategoryVisibility()
            updateLiveCalc()
        })
        popup.setOnDismissListener { buildFriendsGrid() }
        popup.showAsDropDown(anchor, 0, -anchor.height - dp(60))
    }

    private fun isShareSideAllowed(side: ShareUiState): Boolean {
        return when (blockedShareSide()) {
            ShareUiState.PAY -> side != ShareUiState.PAY
            ShareUiState.RECEIVE -> side != ShareUiState.RECEIVE
            else -> true
        }
    }

    private fun blockedShareSide(): ShareUiState? = when {
        payerActorType == ActorType.MERCHANT -> ShareUiState.PAY
        payeeActorType == ActorType.MERCHANT -> ShareUiState.RECEIVE
        else -> null
    }

    private fun applyBlockedShareRules() {
        val blocked = blockedShareSide() ?: run {
            buildFriendsGrid()
            return
        }
        val iterator = shareDrafts.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((blocked == ShareUiState.PAY && entry.value.state == ShareUiState.PAY) ||
                (blocked == ShareUiState.RECEIVE && entry.value.state == ShareUiState.RECEIVE)
            ) iterator.remove()
        }
        buildFriendsGrid()
    }

    private fun getCurrentAmountPaise(): Long = ((etAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()

    private fun sumShare(side: ShareUiState): Long = shareDrafts.values.filter { it.state == side }.sumOf { it.amountPaise ?: 0L }

    private fun suggestShareAmount(side: ShareUiState): Long {
        val amount = getCurrentAmountPaise()
        val remaining = (amount - sumShare(side)).coerceAtLeast(0L)
        return if (remaining > 0) remaining else amount
    }

    private fun getMyShareDraft(): ShareDraft? = shareDrafts["ME"]

    private fun getMyShareAmount(): Long = getMyShareDraft()?.amountPaise ?: 0L

    private fun updateLiveCalc() {
        val amount = getCurrentAmountPaise()
        val payTotal = sumShare(ShareUiState.PAY) + if (payerActorType == ActorType.MERCHANT) amount else 0L
        val receiveTotal = sumShare(ShareUiState.RECEIVE) + if (payeeActorType == ActorType.MERCHANT) amount else 0L
        val unallocated = maxOf(kotlin.math.abs(amount - payTotal), kotlin.math.abs(amount - receiveTotal))
        tvMeantPay.text = "Pay: Rs${formatPlainAmount(payTotal)}"
        tvMeantReceive.text = "Receive: Rs${formatPlainAmount(receiveTotal)}"
        tvBalance.text = if (amount > 0 && payTotal == amount && receiveTotal == amount) {
            "Balanced"
        } else {
            "Unallocated: Rs${formatPlainAmount(unallocated)}"
        }
        updateCategoryVisibility()
    }

    private fun updateCategoryVisibility() {
        val showCategories = isMerchantInvolved() && getMyShareAmount() > 0
        categoryContainer.visibility = if (showCategories) View.VISIBLE else View.GONE
        if (!showCategories) {
            categoryEntries.forEach {
                it.chip.isChecked = false
                it.etMyAmount.setText("")
            }
            return
        }
        if (shouldAutoloadMerchantCategories) {
            activityScope.launch {
                val merchantId = selectedMerchantId() ?: return@launch
                val db = AppDatabase.getInstance(applicationContext)
                val categories = withContext(Dispatchers.IO) { db.categoryDao().getCategoriesForMerchant(merchantId) }
                categoryEntries.forEach { entry ->
                    entry.chip.isChecked = categories.any { it.id == entry.category.id }
                    if (entry.chip.isChecked && entry.etMyAmount.text.isBlank()) {
                        entry.etMyAmount.setText(formatPlainAmount(getMyShareAmount()))
                    }
                }
                shouldAutoloadMerchantCategories = false
            }
        }
    }

    private fun selectedMerchantId(): Long? = when {
        payerActorType == ActorType.MERCHANT -> payerMerchantId
        payeeActorType == ActorType.MERCHANT -> payeeMerchantId
        else -> null
    }

    private fun isMerchantInvolved(): Boolean = payerActorType == ActorType.MERCHANT || payeeActorType == ActorType.MERCHANT

    private suspend fun handleDone() {
        val amountPaise = getCurrentAmountPaise()
        if (amountPaise <= 0) return toast("Enter an amount")
        val payerLabel = etPayerAlias.text.toString().trim()
        val payeeLabel = etPayeeAlias.text.toString().trim()
        if (!validateActorInputs(payerLabel, payeeLabel) || !validateShares(amountPaise)) return
        val db = AppDatabase.getInstance(applicationContext)
        withContext(Dispatchers.IO) { persistTransaction(db, amountPaise, payerLabel, payeeLabel) }
        currentTransaction?.let { TransactionNotificationHelper.cancel(applicationContext, it.id.toInt()) }
        finish()
    }

    private fun validateActorInputs(payerLabel: String, payeeLabel: String): Boolean {
        if (payerActorType != ActorType.ME && payerLabel.isBlank()) {
            toast("Select a payer")
            return false
        }
        if (payeeActorType != ActorType.ME && payeeLabel.isBlank()) {
            toast("Select a payee")
            return false
        }
        if (payerActorType == payeeActorType) {
            val sameActor = when (payerActorType) {
                ActorType.ME -> true
                else -> payerLabel.equals(payeeLabel, true)
            }
            if (sameActor) {
                toast("Payer and payee must be different")
                return false
            }
        }
        return true
    }

    private fun validateShares(amountPaise: Long): Boolean {
        val payTotal = sumShare(ShareUiState.PAY) + if (payerActorType == ActorType.MERCHANT) amountPaise else 0L
        val receiveTotal = sumShare(ShareUiState.RECEIVE) + if (payeeActorType == ActorType.MERCHANT) amountPaise else 0L
        if (payTotal != amountPaise || receiveTotal != amountPaise) {
            toast("Shares must balance to the total amount")
            return false
        }
        return true
    }

    private suspend fun persistTransaction(db: AppDatabase, amountPaise: Long, payerLabel: String, payeeLabel: String) {
        val tx = currentTransaction
        val observedDirection = tx?.observedDirection ?: tx?.direction?.takeIf { tx.source == "SMS" }
        val observedRaw = tx?.payeeRaw
        db.runInTransaction {
            runBlocking {
                val payer = resolveActor(db, true, payerActorType, payerLabel, observedDirection, observedRaw)
                val payee = resolveActor(db, false, payeeActorType, payeeLabel, observedDirection, observedRaw)
                val shares = buildSharesForPersistence(tx?.id ?: 0L)
                val meShare = myShareFromShares(shares)
                val derivedDirection = deriveLegacyDirection(payer.actorType, payee.actorType, meShare?.shareSide, meShare?.amountPaise ?: 0L)
                val source = buildSourceMetadata(tx, payer, payee, observedDirection, observedRaw)
                val base = (tx ?: Transaction(
                    amountPaise = amountPaise,
                    direction = derivedDirection,
                    observedDirection = observedDirection,
                    payeeRaw = source.rawLabel,
                    payeeType = source.payeeType,
                    mySharePaise = meShare?.amountPaise ?: 0L,
                    resolvedFriendId = source.resolvedFriendId,
                    resolvedMerchantId = source.resolvedMerchantId,
                    payerActorType = payer.actorType,
                    payerFriendId = payer.friendId,
                    payerMerchantId = payer.merchantId,
                    payerRawLabel = payer.rawLabel,
                    payeeActorType = payee.actorType,
                    payeeFriendId = payee.friendId,
                    payeeMerchantId = payee.merchantId,
                    payeeRawLabel = payee.rawLabel,
                    dateEpoch = System.currentTimeMillis(),
                    source = "MANUAL",
                    isPending = false
                )).copy(
                    amountPaise = amountPaise,
                    direction = derivedDirection,
                    observedDirection = observedDirection,
                    payeeRaw = source.rawLabel,
                    payeeType = source.payeeType,
                    mySharePaise = meShare?.amountPaise ?: 0L,
                    resolvedFriendId = source.resolvedFriendId,
                    resolvedMerchantId = source.resolvedMerchantId,
                    payerActorType = payer.actorType,
                    payerFriendId = payer.friendId,
                    payerMerchantId = payer.merchantId,
                    payerRawLabel = payer.rawLabel,
                    payeeActorType = payee.actorType,
                    payeeFriendId = payee.friendId,
                    payeeMerchantId = payee.merchantId,
                    payeeRawLabel = payee.rawLabel,
                    isPending = false
                )
                val transactionId = if (tx == null) db.transactionDao().insert(base) else {
                    db.transactionDao().update(base)
                    tx.id
                }

                db.iouDao().deleteForTransaction(transactionId)
                db.transactionPartyDao().deleteForTransaction(transactionId)
                db.categorySplitDao().deleteForTransaction(transactionId)
                db.transactionShareDao().deleteForTransaction(transactionId)

                val persistedShares = shares.map { it.copy(transactionId = transactionId) }
                if (persistedShares.isNotEmpty()) db.transactionShareDao().insertAll(persistedShares)

                persistCategories(db, transactionId, meShare?.amountPaise ?: 0L)
                postLedger(db, transactionId, payer, payee, persistedShares, amountPaise)
            }
        }
    }

    private fun buildSharesForPersistence(transactionId: Long): List<TransactionShare> {
        return shareDrafts.mapNotNull { (key, draft) ->
            val side = when (draft.state) {
                ShareUiState.NONE -> null
                ShareUiState.PAY -> ShareSide.MEANT_TO_PAY
                ShareUiState.RECEIVE -> ShareSide.MEANT_TO_RECEIVE
            } ?: return@mapNotNull null
            val amount = draft.amountPaise ?: 0L
            if (amount <= 0L) return@mapNotNull null
            if (key == "ME") {
                TransactionShare(transactionId = transactionId, participantType = ActorType.ME, shareSide = side, amountPaise = amount)
            } else {
                val friendId = key.substringAfter("F:").toLongOrNull() ?: return@mapNotNull null
                TransactionShare(transactionId = transactionId, participantType = ActorType.FRIEND, friendId = friendId, shareSide = side, amountPaise = amount)
            }
        }
    }

    private suspend fun resolveActor(db: AppDatabase, isPayer: Boolean, actorType: String, typedLabel: String, observedDirection: String?, observedRaw: String?): ActorRef {
        return when (actorType) {
            ActorType.ME -> ActorRef(ActorType.ME, rawLabel = "Me")
            ActorType.FRIEND -> {
                val friendId = resolveFriendId(db, typedLabel, isPayer)
                maybeLinkObservedAliasToFriend(db, friendId, isPayer, observedDirection, observedRaw)
                ActorRef(ActorType.FRIEND, friendId = friendId, rawLabel = typedLabel)
            }
            ActorType.MERCHANT -> {
                val merchantId = resolveMerchantId(db, typedLabel, isPayer)
                maybeLinkObservedAliasToMerchant(db, merchantId, isPayer, observedDirection, observedRaw)
                ActorRef(ActorType.MERCHANT, merchantId = merchantId, rawLabel = typedLabel)
            }
            else -> ActorRef(ActorType.UNKNOWN, rawLabel = typedLabel)
        }
    }

    private suspend fun resolveFriendId(db: AppDatabase, typedLabel: String, isPayer: Boolean): Long {
        (if (isPayer) payerFriendId else payeeFriendId)?.let { return it }
        db.friendDao().findByName(typedLabel)?.let { return it.id }
        val initials = typedLabel.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
        return db.friendDao().insertFriend(Friend(name = typedLabel, avatarInitials = initials.ifBlank { "F" }, addedEpoch = System.currentTimeMillis()))
    }

    private suspend fun resolveMerchantId(db: AppDatabase, typedLabel: String, isPayer: Boolean): Long {
        (if (isPayer) payerMerchantId else payeeMerchantId)?.let { return it }
        db.merchantDao().findByName(typedLabel)?.let { return it.id }
        return db.merchantDao().insertMerchant(
            Merchant(name = typedLabel, addedEpoch = System.currentTimeMillis())
        )
    }

    private suspend fun maybeLinkObservedAliasToFriend(db: AppDatabase, friendId: Long, isPayer: Boolean, observedDirection: String?, observedRaw: String?) {
        if (observedRaw.isNullOrBlank() || observedDirection.isNullOrBlank()) return
        val matchesObservedActor = (observedDirection == "CREDIT" && isPayer) || (observedDirection == "DEBIT" && !isPayer)
        if (!matchesObservedActor) return
        if (observedDirection == "CREDIT" || observedRaw.contains("@")) {
            db.friendDao().insertUpiId(FriendUpiId(friendId = friendId, upiId = observedRaw))
        } else {
            db.friendDao().insertRawName(FriendRawName(friendId = friendId, rawName = observedRaw))
        }
    }

    private suspend fun maybeLinkObservedAliasToMerchant(db: AppDatabase, merchantId: Long, isPayer: Boolean, observedDirection: String?, observedRaw: String?) {
        if (observedRaw.isNullOrBlank() || observedDirection.isNullOrBlank()) return
        val matchesObservedActor = (observedDirection == "CREDIT" && isPayer) || (observedDirection == "DEBIT" && !isPayer)
        if (!matchesObservedActor) return
        if (observedDirection == "CREDIT" || observedRaw.contains("@")) {
            db.merchantDao().insertUpiId(MerchantUpiId(merchantId = merchantId, upiId = observedRaw))
        } else {
            db.merchantDao().insertRawName(MerchantRawName(merchantId = merchantId, rawName = observedRaw))
        }
    }

    private data class SourceMetadata(val rawLabel: String, val payeeType: String, val resolvedFriendId: Long?, val resolvedMerchantId: Long?)

    private fun buildSourceMetadata(existing: Transaction?, payer: ActorRef, payee: ActorRef, observedDirection: String?, observedRaw: String?): SourceMetadata {
        val observedActor = when (observedDirection) {
            "CREDIT" -> payer
            "DEBIT" -> payee
            else -> if (payee.actorType != ActorType.ME) payee else payer
        }
        return SourceMetadata(
            rawLabel = observedRaw ?: observedActor.rawLabel ?: existing?.payeeRaw.orEmpty(),
            payeeType = when (observedActor.actorType) {
                ActorType.FRIEND -> "FRIEND"
                ActorType.MERCHANT -> "MERCHANT"
                else -> "UNKNOWN"
            },
            resolvedFriendId = observedActor.friendId,
            resolvedMerchantId = observedActor.merchantId
        )
    }

    private suspend fun persistCategories(db: AppDatabase, transactionId: Long, mySharePaise: Long) {
        if (!isMerchantInvolved() || mySharePaise <= 0L) return
        val merchantId = selectedMerchantId() ?: return
        categoryEntries.filter { it.chip.isChecked }.forEach { entry ->
            db.categoryDao().linkMerchantCategory(MerchantCategory(merchantId = merchantId, categoryId = entry.category.id))
            val myAmount = ((entry.etMyAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
            if (myAmount > 0L) {
                db.categorySplitDao().insert(TransactionCategorySplit(transactionId, entry.category.id, myAmount, 0L))
            }
        }
    }

    private suspend fun postLedger(db: AppDatabase, transactionId: Long, payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>, amountPaise: Long) {
        if (shares.none { it.participantType == ActorType.ME }) return
        val ledger = LedgerManager(db)
        if (payer.actorType == ActorType.FRIEND && payee.actorType == ActorType.ME && payer.friendId != null) {
            ledger.applyRepayment(transactionId, payer.friendId, amountPaise)
        }
        if (payer.actorType == ActorType.ME && payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            ledger.applyOutgoingSettlement(transactionId, payee.friendId, amountPaise)
        }
        val friendIds = mutableSetOf<Long>()
        payer.friendId?.let { friendIds += it }
        payee.friendId?.let { friendIds += it }
        shares.filter { it.participantType == ActorType.FRIEND }.mapNotNullTo(friendIds) { it.friendId }
        friendIds.forEach { friendId ->
            val actualNet = when {
                payee.actorType == ActorType.FRIEND && payee.friendId == friendId -> amountPaise
                payer.actorType == ActorType.FRIEND && payer.friendId == friendId -> -amountPaise
                else -> 0L
            }
            val intendedPay = shares.filter { it.participantType == ActorType.FRIEND && it.friendId == friendId && it.shareSide == ShareSide.MEANT_TO_PAY }.sumOf { it.amountPaise }
            val intendedReceive = shares.filter { it.participantType == ActorType.FRIEND && it.friendId == friendId && it.shareSide == ShareSide.MEANT_TO_RECEIVE }.sumOf { it.amountPaise }
            val delta = actualNet - (intendedReceive - intendedPay)
            if (delta != 0L) ledger.recordBalanceChange(transactionId, friendId, delta)
        }
    }

    private fun simpleWatcher(onChanged: (Editable) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) = onChanged(s)
    }

    private fun circleDrawable(fill: Int, border: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(stroke, border)
    }

    private fun fmtDateTime(epoch: Long): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(epoch))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatPlainAmount(paise: Long): String = "%.0f".format(paise / 100.0)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
