package com.varun.upitracker.ui.transactionentry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
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
import com.varun.upitracker.domain.transactionentry.actor.ActorSelectionService
import com.varun.upitracker.domain.transactionentry.category.CategorySplitManager
import com.varun.upitracker.domain.transactionentry.persistence.PersistTransactionRequest
import com.varun.upitracker.domain.transactionentry.persistence.TransactionPersistenceService
import com.varun.upitracker.domain.transactionentry.share.OverallAllocationState
import com.varun.upitracker.domain.transactionentry.share.SectionBalanceState
import com.varun.upitracker.domain.transactionentry.share.ShareCalculator
import com.varun.upitracker.domain.transactionentry.share.ShareManager
import com.varun.upitracker.domain.transactionentry.share.ShareRowModel
import com.varun.upitracker.domain.transactionentry.validation.ShareValidationRow
import com.varun.upitracker.domain.transactionentry.validation.TransactionValidator
import com.varun.upitracker.sms.receiver.TransactionNotificationHelper
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.ScreenViewModelFactory
import com.varun.upitracker.ui.TransactionEntryViewModel
import com.varun.upitracker.ui.meShareOnSide
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.resolveActorDisplayName
import com.varun.upitracker.ui.resolvePrimaryDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"
    }

    private data class CategoryEntry(
        val category: Category,
        var isChecked: Boolean,
        var myAmountPaise: Long
    )

    private data class ShareRow(
        val key: String,
        val participantType: String,
        val friendId: Long?,
        val label: String,
        val initials: String,
        var amountPaise: Long
    )

    private val shareCalculator = ShareCalculator()
    private val shareManager = ShareManager()
    private val actorSelectionService = ActorSelectionService()
    private val categorySplitManager = CategorySplitManager()
    private val transactionPersistenceService = TransactionPersistenceService()
    private val transactionValidator = TransactionValidator()
    private lateinit var viewModel: TransactionEntryViewModel

    private var currentTransaction: Transaction? = null
    private var isSmsSource = false
    private var allFriends = listOf<Friend>()
    private var allMerchants = listOf<Merchant>()
    private var allCategories = listOf<Category>()
    private var transactionAccounts = listOf<Account>()
    private var selectedAccountId: String? = null
    private var selectedDateEpoch = System.currentTimeMillis()

    private var payerActorType = ActorType.ME
    private var payeeActorType = ActorType.MERCHANT
    private var payerFriendId: Long? = null
    private var payerMerchantId: Long? = null
    private var payeeFriendId: Long? = null
    private var payeeMerchantId: Long? = null

    private val categoryEntries = mutableListOf<CategoryEntry>()
    private val payerShareRows = mutableListOf<ShareRow>()
    private val payeeShareRows = mutableListOf<ShareRow>()

    private lateinit var tvTopInfo: TextView
    private lateinit var tvPayerActorMe: TextView
    private lateinit var tvPayerActorFriend: TextView
    private lateinit var tvPayerActorMerchant: TextView
    private lateinit var tvPayeeActorMe: TextView
    private lateinit var tvPayeeActorFriend: TextView
    private lateinit var tvPayeeActorMerchant: TextView
    private lateinit var etAmount: EditText
    private lateinit var dateSection: View
    private lateinit var tvDateValue: TextView
    private lateinit var accountSection: View
    private lateinit var spMyAccount: Spinner
    private lateinit var tvBalance: TextView
    private lateinit var tvPayerBalance: TextView
    private lateinit var tvPayeeBalance: TextView
    private lateinit var payerSharesContainer: LinearLayout
    private lateinit var payeeSharesContainer: LinearLayout
    private lateinit var btnAddPayerPerson: Button
    private lateinit var btnAddPayeePerson: Button
    private lateinit var btnEqualize: Button
    private lateinit var categoryContainer: LinearLayout
    private lateinit var formScroll: ScrollView

    private var shouldAutoloadMerchantCategories = true
    private var smsPayerAliasFallback = ""
    private var smsPayeeAliasFallback = ""
    private var isCategorySectionVisible = false

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
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it != -1L }
        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[TransactionEntryViewModel::class.java]
        viewModel.referenceData.observe(this) { referenceData ->
            val db = AppDatabase.Companion.getInstance(applicationContext)
            allFriends = referenceData.friends
            allMerchants = referenceData.merchants
            allCategories = referenceData.categories
            transactionAccounts = referenceData.accounts
            currentTransaction = referenceData.transaction
            viewModel.launchTask {
                setupUi(db)
            }
        }
        viewModel.effects.observe(this) { effect ->
            when (effect) {
                is TransactionEntryEffect.ShowMessage -> toast(effect.message)
                TransactionEntryEffect.HideKeyboard -> dismissKeyboardAndFocus()
                TransactionEntryEffect.NavigateBack -> finish()
                is TransactionEntryEffect.RunLegacyAction -> handleLegacyAction(effect.action)
            }
        }
        viewModel.load(transactionId)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText || focused is AutoCompleteTextView) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    hideKeyboard(focused)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        formScroll = findViewById(R.id.formScroll)
        tvTopInfo = findViewById(R.id.tvTopInfo)
        tvPayerActorMe = findViewById(R.id.tvPayerActorMe)
        tvPayerActorFriend = findViewById(R.id.tvPayerActorFriend)
        tvPayerActorMerchant = findViewById(R.id.tvPayerActorMerchant)
        tvPayeeActorMe = findViewById(R.id.tvPayeeActorMe)
        tvPayeeActorFriend = findViewById(R.id.tvPayeeActorFriend)
        tvPayeeActorMerchant = findViewById(R.id.tvPayeeActorMerchant)
        etAmount = findViewById(R.id.etAmount)
        dateSection = findViewById(R.id.dateSection)
        tvDateValue = findViewById(R.id.tvDateValue)
        accountSection = findViewById(R.id.accountSection)
        spMyAccount = findViewById(R.id.spMyAccount)
        tvBalance = findViewById(R.id.tvBalance)
        tvPayerBalance = findViewById(R.id.tvPayerBalance)
        tvPayeeBalance = findViewById(R.id.tvPayeeBalance)
        payerSharesContainer = findViewById(R.id.payerSharesContainer)
        payeeSharesContainer = findViewById(R.id.payeeSharesContainer)
        btnAddPayerPerson = findViewById(R.id.btnAddPayerPerson)
        btnAddPayeePerson = findViewById(R.id.btnAddPayeePerson)
        btnEqualize = findViewById(R.id.btnEqualize)
        categoryContainer = findViewById(R.id.categoryContainer)
    }

    private suspend fun setupUi(db: AppDatabase) {
        val tx = currentTransaction
        isSmsSource = tx?.source == "SMS"
        selectedDateEpoch = tx?.dateEpoch ?: System.currentTimeMillis()
        tvTopInfo.text = when {
            tx != null -> "${resolveHeaderLabel(db, tx)} - ${fmtDateTime(selectedDateEpoch)}"
            else -> "Manual Entry - ${fmtDateTime(selectedDateEpoch)}"
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener {
            viewModel.onAction(TransactionEntryAction.CloseClicked)
        }
        setupEndpointControls()
        setupAmountField()
        setupDateSection()
        setupAccountPicker(tx)
        setupCategories()

        if (tx != null) populateExistingTransaction(tx, db) else seedDefaultState()
        ensureBaseShareRows()
        updatePrimaryRowLabel(true)
        updatePrimaryRowLabel(false)

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            viewModel.onAction(TransactionEntryAction.SaveClicked)
        }

        updateActorTileStyles()
        buildShareSection(true)
        buildShareSection(false)
        updateAccountSectionVisibility()
        updateLiveCalc()
        renderCategories()
    }

    private fun setupDateSection() {
        tvDateValue.text = fmtDateTime(selectedDateEpoch)
        if (isSmsSource) {
            dateSection.isClickable = false
            dateSection.alpha = 0.7f
            return
        }
        dateSection.isClickable = true
        dateSection.setOnClickListener { showDateTimePicker() }
    }

    private fun showDateTimePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateEpoch }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        selectedDateEpoch = cal.timeInMillis
                        tvDateValue.text = fmtDateTime(selectedDateEpoch)
                        viewModel.onAction(TransactionEntryAction.DateChanged(selectedDateEpoch))
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupAccountPicker(tx: Transaction?) {
        if (transactionAccounts.isEmpty()) {
            selectedAccountId = tx?.myAccountId
            spMyAccount.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                emptyList<String>()
            )
            return
        }
        selectedAccountId = tx?.myAccountId ?: transactionAccounts.firstOrNull()?.id
        val labels = transactionAccounts.map { it.label }
        spMyAccount.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = transactionAccounts.indexOfFirst { it.id == selectedAccountId }.takeIf { it >= 0 } ?: 0
        spMyAccount.setSelection(selectedIndex)
        spMyAccount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedAccountId = transactionAccounts.getOrNull(position)?.id
                viewModel.onAction(TransactionEntryAction.AccountSelected(selectedAccountId.orEmpty()))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateAccountSectionVisibility() {
        accountSection.visibility = if (isMePrimaryActor()) View.VISIBLE else View.GONE
    }

    private fun isMePrimaryActor(): Boolean =
        payerActorType == ActorType.ME || payeeActorType == ActorType.ME

    private fun accountIdForPersistence(): String? {
        if (!isMePrimaryActor()) return null
        return selectedAccountId?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveHeaderLabel(db: AppDatabase, tx: Transaction): String {
        return tx.resolvePrimaryDisplay(db).ifBlank { "Transaction" }
    }

    private fun setupEndpointControls() {
        wireActorTile(tvPayerActorMe, true, ActorType.ME)
        wireActorTile(tvPayerActorFriend, true, ActorType.FRIEND)
        wireActorTile(tvPayerActorMerchant, true, ActorType.MERCHANT)
        wireActorTile(tvPayeeActorMe, false, ActorType.ME)
        wireActorTile(tvPayeeActorFriend, false, ActorType.FRIEND)
        wireActorTile(tvPayeeActorMerchant, false, ActorType.MERCHANT)

        btnAddPayerPerson.setOnClickListener {
            viewModel.onAction(TransactionEntryAction.AddShare(EntrySide.PAYER))
        }
        btnAddPayeePerson.setOnClickListener {
            viewModel.onAction(TransactionEntryAction.AddShare(EntrySide.PAYEE))
        }
    }

    private fun wireActorTile(tile: TextView, isPayer: Boolean, actorType: String) {
        tile.setOnClickListener {
            if (isSmsLockedMeEndpoint(isPayer) && actorType != ActorType.ME) return@setOnClickListener
            val side = if (isPayer) EntrySide.PAYER else EntrySide.PAYEE
            viewModel.onAction(TransactionEntryAction.ActorTypeSelected(side, actorType))
        }
    }

    private fun onActorTypeSelected(isPayer: Boolean, selectedType: String) {
        if (selectedType == ActorType.ME && otherSideActorType(isPayer) == ActorType.ME) {
            toast("Payer and payee cannot both be Me")
            return
        }
        if (isSmsLockedMeEndpoint(isPayer) && selectedType != ActorType.ME) return

        val transition = actorSelectionService.onActorTypeSelected(
            selectedType = selectedType,
            currentActorType = actorTypeFor(isPayer),
            otherSideActorType = otherSideActorType(isPayer),
            currentFriendId = if (isPayer) payerFriendId else payeeFriendId,
            currentMerchantId = if (isPayer) payerMerchantId else payeeMerchantId
        )

        setActorType(isPayer, transition.actorType)
        if (isPayer) {
            payerFriendId = transition.friendId
            payerMerchantId = transition.merchantId
        } else {
            payeeFriendId = transition.friendId
            payeeMerchantId = transition.merchantId
        }

        if (transition.shouldClearShares) {
            rowsFor(isPayer).clear()
        }
        if (transition.shouldSeedBaseShare) {
            rowsFor(isPayer).add(buildPrimaryRow(isPayer))
        }

        shouldAutoloadMerchantCategories = true
        updateActorTileStyles()
        buildShareSection(isPayer)
        updateAccountSectionVisibility()
        updateCategoryVisibility()
        updateLiveCalc()
    }

    private fun setupAmountField() {
        etAmount.isEnabled = !isSmsSource || currentTransaction?.amountPaise == 0L
        etAmount.addTextChangedListener(simpleWatcher {
            viewModel.onAction(TransactionEntryAction.AmountChanged(etAmount.text.toString()))
        })
        wireImeDismiss(etAmount)
        btnEqualize.setOnClickListener { equalizeShares() }
    }

    private fun equalizeShares() {
        val amount = getCurrentAmountPaise()
        if (amount <= 0L) {
            toast("Enter an amount first")
            return
        }

        val allPayerShares = mutableListOf<ShareRow>()
        allPayerShares.addAll(payerShareRows)

        val amountPerPayerShare = amount / allPayerShares.size
        val payerRemainder = amount % allPayerShares.size

        allPayerShares.forEachIndexed { index, row ->
            row.amountPaise = amountPerPayerShare + (if (index < payerRemainder) 1 else 0)
        }

        val allPayeeShares = mutableListOf<ShareRow>()
        allPayeeShares.addAll(payeeShareRows)

        val amountPerPayeeShare = amount / allPayeeShares.size
        val PayeeRemainder = amount % allPayeeShares.size

        allPayeeShares.forEachIndexed { index, row ->
            row.amountPaise = amountPerPayeeShare + (if (index < PayeeRemainder) 1 else 0)
        }

        buildShareSection(true)
        buildShareSection(false)
        updateLiveCalc()
    }

    private fun setupCategories() {
        categoryEntries.clear()
        categoryEntries.addAll(allCategories.map { category ->
            CategoryEntry(category = category, isChecked = false, myAmountPaise = 0L)
        })
    }

    private suspend fun populateExistingTransaction(tx: Transaction, db: AppDatabase) {
        if (tx.amountPaise > 0) etAmount.setText("%.2f".format(tx.amountPaise / 100.0))

        payerActorType = tx.payerActorType
        payeeActorType = tx.payeeActorType
        payerFriendId = tx.payerFriendId
        payerMerchantId = tx.payerMerchantId
        payeeFriendId = tx.payeeFriendId
        payeeMerchantId = tx.payeeMerchantId

        smsPayerAliasFallback = resolveInitialEndpointLabel(db, true, tx)
        smsPayeeAliasFallback = resolveInitialEndpointLabel(db, false, tx)

        val shares =
            withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        seedShareRows(shares)

        val splits = withContext(Dispatchers.IO) { db.categorySplitDao().getForTransaction(tx.id) }
        if (splits.isNotEmpty()) {
            shouldAutoloadMerchantCategories = false
            categoryEntries.forEach { entry ->
                val split = splits.firstOrNull { it.categoryId == entry.category.id }
                if (split != null) {
                    entry.isChecked = true
                    entry.myAmountPaise = split.myAmountPaise
                }
            }
        }
    }

    private fun seedDefaultState() {
        payerActorType = ActorType.ME
        payeeActorType = ActorType.MERCHANT
        smsPayerAliasFallback = ""
        smsPayeeAliasFallback = ""
        payerShareRows.clear()
        payeeShareRows.clear()
        payerShareRows.add(buildMeShareRow())
        payeeShareRows.add(buildMerchantShareRow(false))
    }

    private fun actorTypeFor(isPayer: Boolean) = if (isPayer) payerActorType else payeeActorType

    private fun setActorType(isPayer: Boolean, type: String) {
        if (isPayer) payerActorType = type else payeeActorType = type
    }

    private fun otherSideActorType(isPayer: Boolean) = actorTypeFor(!isPayer)

    private fun rowsFor(isPayer: Boolean): MutableList<ShareRow> = if (isPayer) payerShareRows else payeeShareRows

    private fun primaryLabel(isPayer: Boolean): String =
        rowsFor(isPayer).firstOrNull()?.label?.trim().orEmpty()

    private fun primarySuggestions(isPayer: Boolean): List<String> = when (actorTypeFor(isPayer)) {
        ActorType.MERCHANT -> allMerchants.map { it.name }
        ActorType.ME -> listOf("Me")
        else -> allFriends.map { it.name }
    }

    private fun isSmsLockedMeEndpoint(isPayer: Boolean): Boolean {
        if (!isSmsSource) return false
        val tx = currentTransaction ?: return false
        return if (isPayer) tx.payerActorType == ActorType.ME else tx.payeeActorType == ActorType.ME
    }

    private fun smsAliasFallback(isPayer: Boolean): String {
        return if (isPayer) smsPayerAliasFallback else smsPayeeAliasFallback
    }

    private fun restoreRequiredSmsAliasIfNeeded(field: AutoCompleteTextView, isPayer: Boolean) {
        if (!isSmsSource) return
        if (actorTypeFor(isPayer) == ActorType.ME) return
        if (field.text.isNullOrBlank()) {
            val refill = smsAliasFallback(isPayer)
            if (refill.isNotBlank()) field.setText(refill, false)
        }
    }

    private fun updateActorTileStyles() {
        styleActorTiles(true, payerActorType, isSmsLockedMeEndpoint(true))
        styleActorTiles(false, payeeActorType, isSmsLockedMeEndpoint(false))
    }

    private fun styleActorTiles(isPayer: Boolean, selectedType: String, lockedToMe: Boolean) {
        val tiles = actorTilesFor(isPayer)
        tiles.forEach { (type, view) ->
            val selected = type == selectedType
            val enabled = !lockedToMe || type == ActorType.ME
            val fill = when {
                selected -> Color.parseColor("#00BCD4")
                lockedToMe && type != ActorType.ME -> Color.parseColor("#1F3E45")
                else -> Color.parseColor("#262626")
            }
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(fill)
                setStroke(dp(1), if (selected) Color.parseColor("#62EFFF") else Color.parseColor("#3A3A3A"))
            }
            view.setTextColor(if (enabled) Color.WHITE else Color.parseColor("#777777"))
            view.alpha = if (enabled) 1f else 0.65f
            view.isEnabled = enabled
        }
    }

    private fun actorTilesFor(isPayer: Boolean): List<Pair<String, TextView>> {
        return if (isPayer) {
            listOf(
                ActorType.ME to tvPayerActorMe,
                ActorType.FRIEND to tvPayerActorFriend,
                ActorType.MERCHANT to tvPayerActorMerchant
            )
        } else {
            listOf(
                ActorType.ME to tvPayeeActorMe,
                ActorType.FRIEND to tvPayeeActorFriend,
                ActorType.MERCHANT to tvPayeeActorMerchant
            )
        }
    }

    private fun stylePrimaryShareRow(card: View) {
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#252015"))
            setStroke(dp(1), Color.parseColor("#8B7355"))
        }
    }

    private suspend fun resolveInitialEndpointLabel(db: AppDatabase, isPayer: Boolean, tx: Transaction): String {
        val actor = if (isPayer) tx.payerActorRef() else tx.payeeActorRef()
        return resolveActorDisplayName(db, actor)
    }

    private fun buildMeShareRow(): ShareRow = ShareRow(
        key = "ME",
        participantType = ActorType.ME,
        friendId = null,
        label = "Me",
        initials = "ME",
        amountPaise = getCurrentAmountPaise()
    )

    private fun buildFriendShareRow(friend: Friend): ShareRow = ShareRow(
        key = "F:${friend.id}",
        participantType = ActorType.FRIEND,
        friendId = friend.id,
        label = friend.name,
        initials = friend.avatarInitials,
        amountPaise = getCurrentAmountPaise()
    )

    private fun buildMerchantShareRow(isPayer: Boolean): ShareRow {
        val merchantId = if (isPayer) payerMerchantId else payeeMerchantId
        val merchant = allMerchants.firstOrNull { it.id == merchantId }
        val label = merchant?.name ?: smsAliasFallback(isPayer)
        return ShareRow(
            key = merchantId?.let { "M:$it" } ?: "",
            participantType = ActorType.MERCHANT,
            friendId = null,
            label = label,
            initials = "M",
            amountPaise = getCurrentAmountPaise()
        )
    }

    private fun buildPrimaryRow(isPayer: Boolean): ShareRow = when (actorTypeFor(isPayer)) {
        ActorType.ME -> buildMeShareRow()
        ActorType.MERCHANT -> buildMerchantShareRow(isPayer)
        else -> buildBaseShareRow(isPayer)
    }

    private fun updatePrimaryRowLabel(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        if (rows.isEmpty()) return
        val existingAmount = rows[0].amountPaise
        when (actorTypeFor(isPayer)) {
            ActorType.ME -> rows[0] = buildMeShareRow().copy(amountPaise = existingAmount)
            ActorType.MERCHANT -> rows[0] = buildMerchantShareRow(isPayer).copy(amountPaise = existingAmount)
            ActorType.FRIEND -> {
                val friendId = if (isPayer) payerFriendId else payeeFriendId
                val friend = allFriends.firstOrNull { it.id == friendId }
                rows[0] = if (friend != null) {
                    buildFriendShareRow(friend).copy(amountPaise = existingAmount)
                } else {
                    rows[0].copy(label = smsAliasFallback(isPayer).ifBlank { rows[0].label })
                }
            }
        }
    }

    private fun buildBaseShareRow(isPayer: Boolean): ShareRow {
        return when (actorTypeFor(isPayer)) {
            ActorType.ME -> buildMeShareRow()
            ActorType.FRIEND -> {
                val friendId = if (isPayer) payerFriendId else payeeFriendId
                val friend = allFriends.firstOrNull { it.id == friendId }
                    ?: return ShareRow("F:0", ActorType.FRIEND, null, "", "?", 0L)
                buildFriendShareRow(friend)
            }
            else -> buildMeShareRow()
        }
    }

    private fun buildShareRowFromShare(share: TransactionShare): ShareRow {
        return if (share.participantType == ActorType.ME) {
            ShareRow("ME", ActorType.ME, null, "Me", "ME", share.amountPaise)
        } else {
            val friend = allFriends.firstOrNull { it.id == share.friendId }
            ShareRow(
                key = "F:${share.friendId}",
                participantType = ActorType.FRIEND,
                friendId = share.friendId,
                label = friend?.name ?: "Friend",
                initials = friend?.avatarInitials ?: "F",
                amountPaise = share.amountPaise
            )
        }
    }

    private fun seedShareRows(shares: List<TransactionShare>) {
        payerShareRows.clear()
        payeeShareRows.clear()
        if (shares.all { it.side == null }) return
        shares.forEach { share ->
            val row = buildShareRowFromShare(share)
            if (share.side == "PAYER") payerShareRows.add(row) else payeeShareRows.add(row)
        }
    }

    private fun ensureBaseShareRows() {
        ensurePrimaryRow(true)
        ensurePrimaryRow(false)
    }

    private fun ensurePrimaryRow(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        if (rows.isEmpty()) {
            rows.add(buildPrimaryRow(isPayer))
        }
    }

    private fun buildShareSection(isPayer: Boolean) {
        val container = if (isPayer) payerSharesContainer else payeeSharesContainer
        val rows = rowsFor(isPayer)
        val side = if (isPayer) EntrySide.PAYER else EntrySide.PAYEE
        container.removeAllViews()

        rows.forEachIndexed { index, row ->
            val rowView = LayoutInflater.from(this).inflate(R.layout.item_share_row, container, false)
            val shareRowCard = rowView.findViewById<View>(R.id.shareRowCard)
            val tvAvatar = rowView.findViewById<TextView>(R.id.tvShareAvatar)
            val etName = rowView.findViewById<AutoCompleteTextView>(R.id.etShareName)
            val etShareAmount = rowView.findViewById<EditText>(R.id.etShareAmount)
            val btnRemove = rowView.findViewById<TextView>(R.id.btnRemoveShare)

            if (index == 0) {
                stylePrimaryShareRow(shareRowCard)
                btnRemove.visibility = View.GONE
            }

            val avatarSize = dp(36)
            tvAvatar.layoutParams.width = avatarSize
            tvAvatar.layoutParams.height = avatarSize
            tvAvatar.text = row.initials
            tvAvatar.background = circleDrawable(
                when (row.participantType) {
                    ActorType.ME -> Color.parseColor("#00897B")
                    ActorType.MERCHANT -> Color.parseColor("#00BCD4")
                    else -> Color.parseColor("#5C6BC0")
                },
                Color.TRANSPARENT,
                0
            )

            val isPrimary = index == 0
            val isLockedPrimaryMe = isPrimary && isSmsLockedMeEndpoint(isPayer) && actorTypeFor(isPayer) == ActorType.ME

            if (isPrimary && actorTypeFor(isPayer) == ActorType.ME) {
                etName.setText("Me", false)
                etName.isEnabled = false
                etName.alpha = 0.7f
            } else {
                val suggestions = if (isPrimary) {
                    primarySuggestions(isPayer)
                } else {
                    val excludedKeys = rows.map { it.key }.toSet()
                    val hasMeAlready = rows.any { it.participantType == ActorType.ME }
                    val showMeOption = !hasMeAlready || row.participantType == ActorType.ME
                    val availableFriends = allFriends.filter { "F:${it.id}" !in excludedKeys || it.id == row.friendId }
                    buildList {
                        if (showMeOption) add("Me")
                        addAll(availableFriends.map { it.name })
                    }
                }

                etName.setAdapter(
                    ArrayAdapter(
                        this,
                        android.R.layout.simple_dropdown_item_1line,
                        suggestions
                    )
                )
                etName.setText(row.label, false)
                etName.isEnabled = !isLockedPrimaryMe
                etName.alpha = if (isLockedPrimaryMe) 0.5f else 1f
                etName.setOnItemClickListener { _, _, position, _ ->
                    val selected = etName.adapter.getItem(position) as String
                    viewModel.onAction(TransactionEntryAction.ShareNameChanged(side, index, selected))
                    viewModel.onAction(TransactionEntryAction.ShareNameCommitted(side, index))
                }
                etName.addTextChangedListener(simpleWatcher {
                    viewModel.onAction(
                        TransactionEntryAction.ShareNameChanged(
                            side = side,
                            rowIndex = index,
                            text = etName.text.toString()
                        )
                    )
                    if (isPrimary) {
                        restoreRequiredSmsAliasIfNeeded(etName, isPayer)
                    }
                })
                etName.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !isPrimary && rows[index].key.isBlank()) {
                        viewModel.onAction(TransactionEntryAction.ShareNameCommitted(side, index))
                    }
                }
                wireImeDismiss(etName) {
                    viewModel.onAction(TransactionEntryAction.ShareNameCommitted(side, index))
                }
            }

            if (!isPrimary) {
                btnRemove.setOnClickListener {
                    viewModel.onAction(TransactionEntryAction.RemoveShare(side, index))
                }
            }

            if (row.amountPaise > 0) {
                etShareAmount.setText(formatPlainAmount(row.amountPaise))
            }
            etShareAmount.addTextChangedListener(simpleWatcher {
                viewModel.onAction(
                    TransactionEntryAction.ShareAmountChanged(
                        side = side,
                        rowIndex = index,
                        rawAmount = etShareAmount.text.toString()
                    )
                )
            })
            wireImeDismiss(etShareAmount)

            container.addView(rowView)
        }
        updateSectionBalance(isPayer)
    }

    private fun updateShareRowParticipant(
        rows: MutableList<ShareRow>,
        index: Int,
        participantType: String,
        friendId: Long?,
        label: String,
        initials: String
    ) {
        val updated = shareManager.updateParticipant(
            rows = rows.toShareRowModels(),
            index = index,
            participantType = participantType,
            friendId = friendId,
            label = label,
            initials = initials
        )
        rows.replaceFromShareRowModels(updated)
    }

    private fun addShareRow(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        val amount = suggestShareAmount(rows)
        val updated = shareManager.addDraftRow(rows.toShareRowModels(), amount)
        rows.replaceFromShareRowModels(updated)
        buildShareSection(isPayer)
        updateLiveCalc()
    }

    private fun handleLegacyAction(action: TransactionEntryAction) {
        when (action) {
            is TransactionEntryAction.AmountChanged -> updateLiveCalc()
            is TransactionEntryAction.AccountSelected -> {
                selectedAccountId = action.accountId.takeIf { it.isNotBlank() }
            }

            is TransactionEntryAction.ActorTypeSelected -> {
                onActorTypeSelected(action.side == EntrySide.PAYER, action.actorType)
            }

            is TransactionEntryAction.DateChanged -> {
                selectedDateEpoch = action.dateEpoch
            }

            is TransactionEntryAction.ToggleMerchant -> {
                val isPayer = action.side == EntrySide.PAYER
                onActorTypeSelected(isPayer, if (action.isMerchant) ActorType.MERCHANT else ActorType.ME)
            }

            is TransactionEntryAction.AliasChanged -> {
                applyPrimaryShareTypedText(action.side, action.text)
            }

            is TransactionEntryAction.AliasSelected -> {
                applyPrimaryShareSelection(action.side, action.selection)
            }

            is TransactionEntryAction.AddShare -> {
                addShareRow(action.side == EntrySide.PAYER)
            }

            is TransactionEntryAction.RemoveShare -> {
                val isPayer = action.side == EntrySide.PAYER
                val rows = rowsFor(isPayer)
                val updated = shareManager.removeRow(rows.toShareRowModels(), action.rowIndex)
                rows.replaceFromShareRowModels(updated)
                buildShareSection(isPayer)
                updateLiveCalc()
            }

            is TransactionEntryAction.ShareNameChanged -> {
                applyShareNameTyped(action.side, action.rowIndex, action.text)
            }

            is TransactionEntryAction.ShareNameCommitted -> {
                val isPayer = action.side == EntrySide.PAYER
                val rows = rowsFor(isPayer)
                val name = rows.getOrNull(action.rowIndex)?.label?.trim().orEmpty()
                if (name.isNotBlank()) {
                    viewModel.launchTask {
                        commitShareRowName(isPayer, action.rowIndex, name)
                    }
                }
            }

            is TransactionEntryAction.ShareAmountChanged -> {
                val isPayer = action.side == EntrySide.PAYER
                val rows = rowsFor(isPayer)
                if (action.rowIndex in rows.indices) {
                    rows[action.rowIndex].amountPaise = ((action.rawAmount.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    updateSectionBalance(isPayer)
                    updateLiveCalc()
                }
            }

            is TransactionEntryAction.CategoryToggled -> updateCategoryVisibility()
            is TransactionEntryAction.CategoryAmountChanged -> updateCategoryVisibility()
            TransactionEntryAction.SaveClicked -> {
                viewModel.launchTask { handleDone() }
            }

            TransactionEntryAction.CloseClicked -> finish()
            is TransactionEntryAction.ScreenLoaded -> Unit
        }
    }

    private fun applyPrimaryShareTypedText(side: EntrySide, text: String) {
        val isPayer = side == EntrySide.PAYER
        when (actorTypeFor(isPayer)) {
            ActorType.FRIEND -> {
                val match = allFriends.firstOrNull { it.name == text.trim() }
                if (isPayer) payerFriendId = match?.id else payeeFriendId = match?.id
            }

            ActorType.MERCHANT -> {
                val match = allMerchants.firstOrNull { it.name == text.trim() }
                if (isPayer) payerMerchantId = match?.id else payeeMerchantId = match?.id
            }
        }
    }

    private fun applyPrimaryShareSelection(side: EntrySide, selected: String) {
        val isPayer = side == EntrySide.PAYER
        when (actorTypeFor(isPayer)) {
            ActorType.MERCHANT -> {
                val merchant = allMerchants.find { it.name == selected }
                if (isPayer) payerMerchantId = merchant?.id else payeeMerchantId = merchant?.id
                shouldAutoloadMerchantCategories = true
                updateCategoryVisibility()
            }
            else -> {
                if (selected == "Me") {
                    if (otherSideActorType(isPayer) == ActorType.ME) {
                        toast("Payer and payee cannot both be Me")
                        return
                    }
                    setActorType(isPayer, ActorType.ME)
                    if (isPayer) payerFriendId = null else payeeFriendId = null
                } else {
                    val friend = allFriends.find { it.name == selected }
                    setActorType(isPayer, ActorType.FRIEND)
                    if (isPayer) payerFriendId = friend?.id else payeeFriendId = friend?.id
                }
                updateCategoryVisibility()
                updateLiveCalc()
            }
        }
        hideKeyboard()
    }

    private fun applyShareNameTyped(side: EntrySide, rowIndex: Int, rawText: String) {
        val isPayer = side == EntrySide.PAYER
        val rows = rowsFor(isPayer)
        if (rowIndex !in rows.indices) return
        val trimmed = rawText.trim()
        val old = rows[rowIndex]
        rows[rowIndex] = ShareRow(old.key, old.participantType, old.friendId, trimmed, old.initials, old.amountPaise)
        val match = allFriends.firstOrNull { it.name == trimmed }
        if (match != null) {
            updateShareRowParticipant(rows, rowIndex, ActorType.FRIEND, match.id, match.name, match.avatarInitials)
        }
    }

    private fun List<ShareRow>.toShareRowModels(): List<ShareRowModel> {
        return map {
            ShareRowModel(
                key = it.key,
                participantType = it.participantType,
                friendId = it.friendId,
                label = it.label,
                initials = it.initials,
                amountPaise = it.amountPaise
            )
        }
    }

    private fun MutableList<ShareRow>.replaceFromShareRowModels(models: List<ShareRowModel>) {
        clear()
        addAll(models.map {
            ShareRow(
                key = it.key,
                participantType = it.participantType,
                friendId = it.friendId,
                label = it.label,
                initials = it.initials,
                amountPaise = it.amountPaise
            )
        })
    }

    private fun getCurrentAmountPaise(): Long = ((etAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()

    private fun suggestShareAmount(rows: MutableList<ShareRow>): Long {
        val total = getCurrentAmountPaise()
        return shareCalculator.suggestShareAmount(total, rows.map { it.amountPaise })
    }

    private fun getMyShareAmount(side: String): Long {
        val rows = if (side == "PAYER") payerShareRows else payeeShareRows
        return rows.firstOrNull { it.participantType == ActorType.ME }?.amountPaise ?: 0L
    }

    private fun myShareForCategories(): Long {
        val payerMeShare = getMyShareAmount("PAYER")
        val payeeMeShare = getMyShareAmount("PAYEE")
        return shareCalculator.myShareForCategories(
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            payerMeSharePaise = payerMeShare,
            payeeMeSharePaise = payeeMeShare
        )
    }

    private fun updateSectionBalance(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        val total = getCurrentAmountPaise()
        val summed = rows.sumOf { it.amountPaise }
        val tv = if (isPayer) tvPayerBalance else tvPayeeBalance
        val result = shareCalculator.computeSectionBalance(total, summed)
        tv.text = when (result.state) {
            SectionBalanceState.BALANCED -> "✓ Balanced"
            SectionBalanceState.OVER -> "Over by Rs${formatPlainAmount(result.deltaPaise)}"
            SectionBalanceState.REMAINING -> "Remaining: Rs${formatPlainAmount(result.deltaPaise)}"
        }
    }

    private fun updateLiveCalc() {
        updateSectionBalance(true)
        updateSectionBalance(false)

        val amount = getCurrentAmountPaise()
        val payerSummed = if (payerActorType != ActorType.MERCHANT) payerShareRows.sumOf { it.amountPaise } else 0L
        val payeeSummed = if (payeeActorType != ActorType.MERCHANT) payeeShareRows.sumOf { it.amountPaise } else 0L
        val allocation = shareCalculator.computeOverallAllocation(
            amountPaise = amount,
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            payerSummedPaise = payerSummed,
            payeeSummedPaise = payeeSummed
        )

        tvBalance.text = when (allocation.state) {
            OverallAllocationState.OVER_ALLOCATED -> "Over-allocated: Rs${formatPlainAmount(allocation.deltaPaise)}"
            OverallAllocationState.PAYER_UNALLOCATED -> "Payer unallocated: Rs${formatPlainAmount(allocation.deltaPaise)}"
            OverallAllocationState.PAYEE_UNALLOCATED -> "Payee unallocated: Rs${formatPlainAmount(allocation.deltaPaise)}"
            OverallAllocationState.UNALLOCATED -> "Unallocated"
            OverallAllocationState.BALANCED -> ""
        }
        updateCategoryVisibility()
    }

    private fun updateCategoryVisibility() {
        val myShare = myShareForCategories()
        val visibilityDecision = categorySplitManager.visibilityDecision(
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            mySharePaise = myShare
        )

        val wasVisible = isCategorySectionVisible
        isCategorySectionVisible = visibilityDecision.showCategories
        categoryContainer.visibility = if (visibilityDecision.showCategories) View.VISIBLE else View.GONE
        if (visibilityDecision.shouldClearSelections) {
            categoryEntries.forEach {
                it.isChecked = false
                it.myAmountPaise = 0L
            }
            renderCategories()
            return
        }

        if (!wasVisible && visibilityDecision.showCategories) {
            formScroll.post {
                formScroll.smoothScrollTo(0, categoryContainer.top)
            }
        }

        val autoloadDecision = categorySplitManager.autoloadDecision(
            shouldAutoloadMerchantCategories = shouldAutoloadMerchantCategories,
            showCategories = visibilityDecision.showCategories,
            merchantId = selectedMerchantId()
        )

        if (autoloadDecision.shouldLoad) {
            viewModel.launchTask launch@{
                val merchantId = autoloadDecision.merchantId ?: return@launch
                val db = AppDatabase.Companion.getInstance(applicationContext)
                val categories = withContext(Dispatchers.IO) {
                    db.categoryDao().getCategoriesForMerchant(merchantId)
                }
                categoryEntries.forEach { entry ->
                    entry.isChecked = categories.any { it.id == entry.category.id }
                    if (entry.isChecked && entry.myAmountPaise <= 0L) {
                        entry.myAmountPaise = myShare
                    }
                }
                renderCategories()
                shouldAutoloadMerchantCategories = false
            }
        }
    }

    private fun selectedMerchantId(): Long? = categorySplitManager.selectedMerchantId(
        payerActorType = payerActorType,
        payeeActorType = payeeActorType,
        payerMerchantId = payerMerchantId,
        payeeMerchantId = payeeMerchantId
    )

    private suspend fun handleDone() {
        val amountPaise = getCurrentAmountPaise()
        if (amountPaise <= 0) return toast("Enter an amount")
        val payerLabel = primaryLabel(true)
        val payeeLabel = primaryLabel(false)

        val actorValidation = transactionValidator.validateActors(
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            payerLabel = payerLabel,
            payeeLabel = payeeLabel
        )
        if (!actorValidation.isValid) {
            return toast(actorValidation.message ?: "Invalid payer/payee")
        }

        val shareValidation = transactionValidator.validateShares(
            amountPaise = amountPaise,
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            payerRows = payerShareRows.mapIndexed { index, it ->
                ShareValidationRow(key = it.key, label = it.label, amountPaise = it.amountPaise, isPrimary = index == 0)
            },
            payeeRows = payeeShareRows.mapIndexed { index, it ->
                ShareValidationRow(key = it.key, label = it.label, amountPaise = it.amountPaise, isPrimary = index == 0)
            }
        )
        if (!shareValidation.isValid) {
            return toast(shareValidation.message ?: "Invalid share allocation")
        }

        val db = AppDatabase.Companion.getInstance(applicationContext)
        withContext(Dispatchers.IO) { persistTransaction(db, amountPaise, payerLabel, payeeLabel) }
        currentTransaction?.let { TransactionNotificationHelper.cancel(applicationContext, it.id.toInt()) }
        finish()
    }

    private suspend fun persistTransaction(db: AppDatabase, amountPaise: Long, payerLabel: String, payeeLabel: String) {
        transactionPersistenceService.persist(
            db = db,
            request = PersistTransactionRequest(
                existingTransaction = currentTransaction,
                amountPaise = amountPaise,
                selectedAccountId = accountIdForPersistence(),
                dateEpoch = selectedDateEpoch
            ),
            resolveActors = {
                val payer = resolveActor(db, true, payerActorType, payerLabel)
                val payee = resolveActor(db, false, payeeActorType, payeeLabel)
                payer to payee
            },
            resolveUnresolvedShareRows = { resolveUnresolvedShareRows(db) },
            buildSharesForPersistence = { txId -> buildSharesForPersistence(txId) },
            mySharePaiseFromShares = { shares -> mySharePaiseFromShares(shares) },
            persistCategories = { transactionId, meSharePaise ->
                persistCategories(db, transactionId, meSharePaise)
            }
        )
    }

    private fun mySharePaiseFromShares(shares: List<TransactionShare>): Long {
        return shares.filter { it.participantType == ActorType.ME }.sumOf { it.amountPaise }
    }

    private fun buildSharesForPersistence(txId: Long): List<TransactionShare> {
        val result = mutableListOf<TransactionShare>()
        payerShareRows.forEach { row ->
            if (row.participantType != ActorType.MERCHANT && row.key.isNotBlank() && row.amountPaise > 0) {
                result.add(
                    TransactionShare(
                        transactionId = txId,
                        side = "PAYER",
                        participantType = row.participantType,
                        friendId = row.friendId,
                        amountPaise = row.amountPaise
                    )
                )
            }
        }
        payeeShareRows.forEach { row ->
            if (row.participantType != ActorType.MERCHANT && row.key.isNotBlank() && row.amountPaise > 0) {
                result.add(
                    TransactionShare(
                        transactionId = txId,
                        side = "PAYEE",
                        participantType = row.participantType,
                        friendId = row.friendId,
                        amountPaise = row.amountPaise
                    )
                )
            }
        }
        return result
    }

    private suspend fun resolveActor(db: AppDatabase, isPayer: Boolean, actorType: String, typedLabel: String): ActorRef {
        return when (actorType) {
            ActorType.ME -> ActorRef(ActorType.ME, rawLabel = "Me")
            ActorType.FRIEND -> {
                val friendId = resolveFriendId(db, typedLabel, isPayer)
                currentSmsRawLabel(isPayer)?.let { maybeLinkRawAliasToFriend(db, friendId, it) }
                ActorRef(ActorType.FRIEND, friendId = friendId, rawLabel = typedLabel)
            }
            ActorType.MERCHANT -> {
                val merchantId = resolveMerchantId(db, typedLabel, isPayer)
                currentSmsRawLabel(isPayer)?.let { maybeLinkRawAliasToMerchant(db, merchantId, it) }
                ActorRef(ActorType.MERCHANT, merchantId = merchantId, rawLabel = typedLabel)
            }
            else -> ActorRef(ActorType.UNKNOWN, rawLabel = typedLabel)
        }
    }

    private suspend fun resolveFriendId(db: AppDatabase, typedLabel: String, isPayer: Boolean): Long {
        (if (isPayer) payerFriendId else payeeFriendId)?.let { return it }
        return resolveOrCreateFriend(db, typedLabel).id
    }

    private fun computeFriendInitials(name: String): String {
        return name.split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "F" }
    }

    private suspend fun resolveOrCreateFriend(db: AppDatabase, name: String): Friend {
        db.friendDao().findByName(name)?.let { return it }
        val initials = computeFriendInitials(name)
        val id = db.friendDao().insertFriend(
            Friend(name = name, avatarInitials = initials, addedEpoch = System.currentTimeMillis())
        )
        return db.friendDao().getFriendById(id)
            ?: Friend(
                id = id,
                name = name,
                avatarInitials = initials,
                addedEpoch = System.currentTimeMillis()
            )
    }

    private fun rememberFriend(friend: Friend) {
        if (allFriends.none { it.id == friend.id }) {
            allFriends = allFriends + friend
        }
    }

    private suspend fun commitShareRowName(isPayer: Boolean, index: Int, rawName: String): Boolean {
        if (index <= 0) return true
        val rows = rowsFor(isPayer)
        if (index >= rows.size) return false

        val name = rawName.trim()
        if (name.isBlank()) {
            toast("Enter a name")
            return false
        }

        val db = AppDatabase.Companion.getInstance(applicationContext)
        val newKey = when {
            name.equals("Me", ignoreCase = true) -> {
                if (rows.withIndex().any { (i, r) -> i != index && r.participantType == ActorType.ME }) {
                    toast("Person already added")
                    return false
                }
                updateShareRowParticipant(rows, index, ActorType.ME, null, "Me", "ME")
                "ME"
            }
            else -> {
                val existing = allFriends.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: withContext(Dispatchers.IO) { db.friendDao().findByName(name) }
                val friend = existing ?: withContext(Dispatchers.IO) {
                    resolveOrCreateFriend(
                        db,
                        name
                    )
                }.also { rememberFriend(it) }
                val key = "F:${friend.id}"
                if (rows.withIndex().any { (i, r) -> i != index && r.key == key }) {
                    toast("Person already added")
                    return false
                }
                updateShareRowParticipant(rows, index, ActorType.FRIEND, friend.id, friend.name, friend.avatarInitials)
                key
            }
        }

        if (rows[index].key != newKey) return false
        buildShareSection(isPayer)
        updateLiveCalc()
        return true
    }

    private suspend fun resolveUnresolvedShareRows(db: AppDatabase) {
        resolveUnresolvedShareRowsForSide(db, true)
        resolveUnresolvedShareRowsForSide(db, false)
    }

    private suspend fun resolveUnresolvedShareRowsForSide(db: AppDatabase, isPayer: Boolean) {
        if (actorTypeFor(isPayer) == ActorType.MERCHANT) return
        val rows = rowsFor(isPayer)
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.key.isBlank() && row.label.isNotBlank()) {
                applyShareRowName(db, isPayer, i, row.label)
            }
        }
    }

    private suspend fun applyShareRowName(db: AppDatabase, isPayer: Boolean, index: Int, rawName: String) {
        val rows = rowsFor(isPayer)
        if (index <= 0 || index >= rows.size) return

        val name = rawName.trim()
        if (name.isBlank()) return

        when {
            name.equals("Me", ignoreCase = true) -> {
                if (rows.withIndex().any { (i, r) -> i != index && r.participantType == ActorType.ME }) return
                updateShareRowParticipant(rows, index, ActorType.ME, null, "Me", "ME")
            }
            else -> {
                val existing = allFriends.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: db.friendDao().findByName(name)
                val friend = existing ?: resolveOrCreateFriend(db, name).also { rememberFriend(it) }
                val key = "F:${friend.id}"
                if (rows.withIndex().any { (i, r) -> i != index && r.key == key }) return
                updateShareRowParticipant(rows, index, ActorType.FRIEND, friend.id, friend.name, friend.avatarInitials)
            }
        }
    }

    private suspend fun resolveMerchantId(db: AppDatabase, typedLabel: String, isPayer: Boolean): Long {
        (if (isPayer) payerMerchantId else payeeMerchantId)?.let { return it }
        db.merchantDao().findByName(typedLabel)?.let { return it.id }
        return db.merchantDao().insertMerchant(
            Merchant(
                name = typedLabel,
                addedEpoch = System.currentTimeMillis()
            )
        )
    }

    private fun currentSmsRawLabel(isPayer: Boolean): String? {
        if (!isSmsSource) return null
        val tx = currentTransaction ?: return null
        return if (isPayer) tx.payerRawLabel else tx.payeeRawLabel
    }

    private suspend fun maybeLinkRawAliasToFriend(db: AppDatabase, friendId: Long, rawLabel: String) {
        if (rawLabel.isBlank()) return
        if (rawLabel.contains("@")) {
            db.friendDao().insertUpiId(FriendUpiId(friendId = friendId, upiId = rawLabel))
        } else {
            db.friendDao().insertRawName(FriendRawName(friendId = friendId, rawName = rawLabel))
        }
    }

    private suspend fun maybeLinkRawAliasToMerchant(db: AppDatabase, merchantId: Long, rawLabel: String) {
        if (rawLabel.isBlank()) return
        if (rawLabel.contains("@")) {
            db.merchantDao().insertUpiId(MerchantUpiId(merchantId = merchantId, upiId = rawLabel))
        } else {
            db.merchantDao().insertRawName(
                MerchantRawName(
                    merchantId = merchantId,
                    rawName = rawLabel
                )
            )
        }
    }

    private suspend fun persistCategories(db: AppDatabase, transactionId: Long, meSharePaise: Long) {
        val merchantInvolved = payerActorType == ActorType.MERCHANT || payeeActorType == ActorType.MERCHANT
        if (!merchantInvolved || meSharePaise <= 0L) return
        val merchantId = selectedMerchantId() ?: return
        categoryEntries.filter { it.isChecked }.forEach { entry ->
            db.categoryDao().linkMerchantCategory(
                MerchantCategory(
                    merchantId = merchantId,
                    categoryId = entry.category.id
                )
            )
            val myAmount = entry.myAmountPaise
            if (myAmount > 0L) {
                db.categorySplitDao().insert(
                    TransactionCategorySplit(
                        transactionId,
                        entry.category.id,
                        myAmount,
                        0L
                    )
                )
            }
        }
    }

    private fun renderCategories() {
        categoryContainer.removeAllViews()
        categoryEntries.forEachIndexed { index, entry ->
            val rowView = LayoutInflater.from(this).inflate(R.layout.item_transaction_category_split, categoryContainer, false)
            val chip = rowView.findViewById<Chip>(R.id.chipCategory)
            val expansion = rowView.findViewById<LinearLayout>(R.id.categoryExpansion)
            val etMyAmount = rowView.findViewById<EditText>(R.id.etCategoryMyAmount)

            chip.setOnCheckedChangeListener(null)
            chip.text = entry.category.name
            chip.isCheckable = true
            chip.setTextColor(Color.WHITE)
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#2A2A2A"))
            chip.isChecked = entry.isChecked
            expansion.visibility = if (entry.isChecked) View.VISIBLE else View.GONE

            (etMyAmount.tag as? TextWatcher)?.let { etMyAmount.removeTextChangedListener(it) }
            etMyAmount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            etMyAmount.imeOptions = EditorInfo.IME_ACTION_DONE
            etMyAmount.setTextColor(Color.WHITE)
            etMyAmount.setHintTextColor(Color.parseColor("#555555"))
            etMyAmount.background = null
            etMyAmount.setText(if (entry.myAmountPaise > 0L) formatPlainAmount(entry.myAmountPaise) else "")

            val watcher = simpleWatcher {
                entry.myAmountPaise = ((etMyAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
                viewModel.onAction(
                    TransactionEntryAction.CategoryAmountChanged(
                        categoryId = entry.category.id,
                        rawAmount = etMyAmount.text.toString()
                    )
                )
            }
            etMyAmount.addTextChangedListener(watcher)
            etMyAmount.tag = watcher
            wireImeDismiss(etMyAmount)

            chip.setOnCheckedChangeListener { _, checked ->
                entry.isChecked = checked
                if (checked && entry.myAmountPaise <= 0L) {
                    entry.myAmountPaise = myShareForCategories()
                }
                renderCategories()
                viewModel.onAction(TransactionEntryAction.CategoryToggled(entry.category.id, checked))
            }

            categoryContainer.addView(rowView)
        }
    }

    private fun hideKeyboard(view: View? = currentFocus) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val target = view ?: window.decorView
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun dismissKeyboardAndFocus() {
        currentFocus?.clearFocus()
        hideKeyboard()
    }

    private fun wireImeDismiss(field: TextView, onDone: (() -> Unit)? = null) {
        field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT
            ) {
                onDone?.invoke()
                v.clearFocus()
                hideKeyboard(v)
                true
            } else {
                false
            }
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

    private fun fmtDateTime(epoch: Long): String = SimpleDateFormat(
        "dd MMM yyyy, hh:mm a",
        Locale.getDefault()
    ).format(Date(epoch))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatPlainAmount(paise: Long): String = "%.0f".format(paise / 100.0)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}