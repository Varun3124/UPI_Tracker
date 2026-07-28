package com.varun.upitracker.ui

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.DefaultAccounts
import com.varun.upitracker.data.repository.AccountRepository
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
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.sms.receiver.TransactionNotificationHelper
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

    private data class CategoryEntry(
        val category: Category,
        val chip: Chip,
        val expansionLayout: LinearLayout,
        val etMyAmount: EditText
    )

    private data class ShareRow(
        val key: String,
        val participantType: String,
        val friendId: Long?,
        val label: String,
        val initials: String,
        var amountPaise: Long
    )

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var viewModel: TransactionEntryViewModel

    private var currentTransaction: Transaction? = null
    private var isSmsSource = false
    private var allFriends = listOf<Friend>()
    private var allMerchants = listOf<Merchant>()
    private var allCategories = listOf<Category>()
    private var transactionAccounts = listOf<Account>()
    private var selectedAccountId = DefaultAccounts.SAVINGS_ID

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
    private lateinit var tbPayerMerchant: ToggleButton
    private lateinit var tbPayeeMerchant: ToggleButton
    private lateinit var etPayerAlias: AutoCompleteTextView
    private lateinit var etPayeeAlias: AutoCompleteTextView
    private lateinit var etAmount: EditText
    private lateinit var spMyAccount: Spinner
    private lateinit var tvBalance: TextView
    private lateinit var tvPayerBalance: TextView
    private lateinit var tvPayeeBalance: TextView
    private lateinit var payerSharesContainer: LinearLayout
    private lateinit var payeeSharesContainer: LinearLayout
    private lateinit var btnAddPayerPerson: Button
    private lateinit var btnAddPayeePerson: Button
    private lateinit var categoryContainer: LinearLayout

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
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it != -1L }
        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[TransactionEntryViewModel::class.java]
        viewModel.referenceData.observe(this) { referenceData ->
            val db = AppDatabase.getInstance(applicationContext)
            allFriends = referenceData.friends
            allMerchants = referenceData.merchants
            allCategories = referenceData.categories
            transactionAccounts = referenceData.accounts
            currentTransaction = referenceData.transaction
            activityScope.launch {
                setupUi(db)
            }
        }
        viewModel.load(transactionId)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
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
        tvTopInfo = findViewById(R.id.tvTopInfo)
        tbPayerMerchant = findViewById(R.id.tbPayerMerchant)
        tbPayeeMerchant = findViewById(R.id.tbPayeeMerchant)
        etPayerAlias = findViewById(R.id.etPayerAlias)
        etPayeeAlias = findViewById(R.id.etPayeeAlias)
        etAmount = findViewById(R.id.etAmount)
        spMyAccount = findViewById(R.id.spMyAccount)
        tvBalance = findViewById(R.id.tvBalance)
        tvPayerBalance = findViewById(R.id.tvPayerBalance)
        tvPayeeBalance = findViewById(R.id.tvPayeeBalance)
        payerSharesContainer = findViewById(R.id.payerSharesContainer)
        payeeSharesContainer = findViewById(R.id.payeeSharesContainer)
        btnAddPayerPerson = findViewById(R.id.btnAddPayerPerson)
        btnAddPayeePerson = findViewById(R.id.btnAddPayeePerson)
        categoryContainer = findViewById(R.id.categoryContainer)
    }

    private suspend fun setupUi(db: AppDatabase) {
        val tx = currentTransaction
        isSmsSource = tx?.source == "SMS"
        tvTopInfo.text = when {
            tx != null -> "${resolveHeaderLabel(db, tx)} - ${fmtDateTime(tx.dateEpoch)}"
            else -> "Manual Entry - ${fmtDateTime(System.currentTimeMillis())}"
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        setupEndpointControls()
        setupAmountField()
        setupAccountPicker(tx)
        setupCategories()

        if (tx != null) populateExistingTransaction(tx, db) else seedDefaultState()
        ensureBaseShareRows()

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            activityScope.launch { handleDone() }
        }

        updateAutocomplete(etPayerAlias, payerActorType)
        updateAutocomplete(etPayeeAlias, payeeActorType)
        refreshEndpointInput(etPayerAlias, payerActorType)
        refreshEndpointInput(etPayeeAlias, payeeActorType)
        updateMerchantToggleStyles()
        updateShareSectionVisibility(true)
        updateShareSectionVisibility(false)
        buildShareSection(true)
        buildShareSection(false)
        updateLiveCalc()
    }

    private fun setupAccountPicker(tx: Transaction?) {
        if (transactionAccounts.isEmpty()) {
            transactionAccounts = listOf(
                Account(
                    id = DefaultAccounts.SAVINGS_ID,
                    type = com.varun.upitracker.database.entity.AccountType.SAVINGS,
                    label = "Savings",
                    addedEpoch = System.currentTimeMillis()
                )
            )
        }
        selectedAccountId = tx?.myAccountId ?: DefaultAccounts.SAVINGS_ID
        val labels = transactionAccounts.map { it.label }
        spMyAccount.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = transactionAccounts.indexOfFirst { it.id == selectedAccountId }.takeIf { it >= 0 } ?: 0
        spMyAccount.setSelection(selectedIndex)
        spMyAccount.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedAccountId = transactionAccounts.getOrNull(position)?.id ?: DefaultAccounts.SAVINGS_ID
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private suspend fun resolveHeaderLabel(db: AppDatabase, tx: Transaction): String {
        return tx.resolvePrimaryDisplay(db).ifBlank { "Transaction" }
    }

    private fun setupEndpointControls() {
        //Payer toggle button listener
        tbPayerMerchant.setOnCheckedChangeListener { _, isMerchant ->
            if (isSmsLockedMeEndpoint(true) && isMerchant) {
                tbPayerMerchant.isChecked = false
                return@setOnCheckedChangeListener
            }
            onMerchantToggleChanged(true, isMerchant)
        }

        //Payee toggle button listener
        tbPayeeMerchant.setOnCheckedChangeListener { _, isMerchant ->
            if (isSmsLockedMeEndpoint(false) && isMerchant) {
                tbPayeeMerchant.isChecked = false
                return@setOnCheckedChangeListener
            }
            onMerchantToggleChanged(false, isMerchant)
        }

        //Alias->Dropdown listeners
        wireAliasField(etPayerAlias, true)
        wireAliasField(etPayeeAlias, false)

        btnAddPayerPerson.setOnClickListener { addShareRow(true) }
        btnAddPayeePerson.setOnClickListener { addShareRow(false) }
    }

    private fun onMerchantToggleChanged(isPayer: Boolean, isMerchant: Boolean) {
        if (isMerchant) {
            setActorType(isPayer, ActorType.MERCHANT)
            if (isPayer) {
                payerFriendId = null
            } else {
                payeeFriendId = null
            }
            rowsFor(isPayer).clear()
        } else {
            if (isPayer) payerMerchantId = null else payeeMerchantId = null
            if (actorTypeFor(isPayer) == ActorType.MERCHANT) {
                val newType = if (otherSideActorType(isPayer) == ActorType.ME) ActorType.FRIEND else ActorType.ME
                setActorType(isPayer, newType)
            }
            rowsFor(isPayer).clear()
            rowsFor(isPayer).add(buildBaseShareRow(isPayer))
        }
        shouldAutoloadMerchantCategories = true
        val field = if (isPayer) etPayerAlias else etPayeeAlias
        refreshEndpointInput(field, actorTypeFor(isPayer))
        updateAutocomplete(field, actorTypeFor(isPayer))
        updateMerchantToggleStyles()
        updateShareSectionVisibility(isPayer)
        buildShareSection(isPayer)
        updateCategoryVisibility()
        updateLiveCalc()
    }

    private fun setupAmountField() {
        etAmount.isEnabled = !isSmsSource || currentTransaction?.amountPaise == 0L
        etAmount.addTextChangedListener(simpleWatcher { updateLiveCalc() })
        wireImeDismiss(etAmount)
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
                imeOptions = EditorInfo.IME_ACTION_DONE
                textSize = 13f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555"))
                background = null
            }
            wireImeDismiss(etMy)
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
                if (checked && etMy.text.isBlank()) {
                    etMy.setText(formatPlainAmount(myShareForCategories()))
                }
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

    private suspend fun populateExistingTransaction(tx: Transaction, db: AppDatabase) {
        if (tx.amountPaise > 0) etAmount.setText("%.2f".format(tx.amountPaise / 100.0))

        payerActorType = tx.payerActorType
        payeeActorType = tx.payeeActorType
        payerFriendId = tx.payerFriendId
        payerMerchantId = tx.payerMerchantId
        payeeFriendId = tx.payeeFriendId
        payeeMerchantId = tx.payeeMerchantId

        tbPayerMerchant.isChecked = payerActorType == ActorType.MERCHANT
        tbPayeeMerchant.isChecked = payeeActorType == ActorType.MERCHANT

        smsPayerAliasFallback = resolveInitialEndpointLabel(db, true, tx)
        smsPayeeAliasFallback = resolveInitialEndpointLabel(db, false, tx)
        etPayerAlias.setText(smsPayerAliasFallback, false)
        etPayeeAlias.setText(smsPayeeAliasFallback, false)

        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        seedShareRows(shares)

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
        tbPayerMerchant.isChecked = false
        tbPayeeMerchant.isChecked = true
        payerShareRows.clear()
        payeeShareRows.clear()
        payerShareRows.add(buildMeShareRow())
    }

    private fun wireAliasField(field: AutoCompleteTextView, isPayer: Boolean) {

        field.setOnItemClickListener { _, _, position, _ ->
            when (actorTypeFor(isPayer)) {
                ActorType.MERCHANT -> {
                    val selectedName = field.adapter.getItem(position) as String
                    val merchant = allMerchants.find { it.name == selectedName }
                    if (isPayer) payerMerchantId = merchant?.id else payeeMerchantId = merchant?.id
                    shouldAutoloadMerchantCategories = true
                    updateCategoryVisibility()
                }
                else -> {
                    val selected = field.adapter.getItem(position) as String
                    if (selected == "Me") {
                        if (otherSideActorType(isPayer) == ActorType.ME) {
                            toast("Payer and payee cannot both be Me")
                            return@setOnItemClickListener
                        }
                        setActorType(isPayer, ActorType.ME)
                        if (isPayer) payerFriendId = null else payeeFriendId = null
                        refreshEndpointInput(field, ActorType.ME)
                        reseedBaseShareRow(isPayer)
                    } else {
                        val friend = allFriends.find { it.name == selected }
                        setActorType(isPayer, ActorType.FRIEND)
                        if (isPayer) payerFriendId = friend?.id else payeeFriendId = friend?.id
                        reseedBaseShareRow(isPayer)
                    }
                    updateMerchantToggleStyles()
                    updateCategoryVisibility()
                    updateLiveCalc()
                }
            }
            hideKeyboard(field)
        }

        // Dropdown based on text change LIMIT 1
        field.addTextChangedListener(simpleWatcher {
            when (actorTypeFor(isPayer)) {
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

        field.setOnClickListener {
            if (actorTypeFor(isPayer) == ActorType.ME && !isSmsLockedMeEndpoint(isPayer)) {
                field.showDropDown()
            }
        }
        wireImeDismiss(field)
    }

    private fun reseedBaseShareRow(isPayer: Boolean) {
        if (actorTypeFor(isPayer) == ActorType.MERCHANT) return
        rowsFor(isPayer).clear()
        rowsFor(isPayer).add(buildBaseShareRow(isPayer))
        buildShareSection(isPayer)
    }

    private fun actorTypeFor(isPayer: Boolean) = if (isPayer) payerActorType else payeeActorType

    private fun setActorType(isPayer: Boolean, type: String) {
        if (isPayer) payerActorType = type else payeeActorType = type
    }

    private fun otherSideActorType(isPayer: Boolean) = actorTypeFor(!isPayer)

    private fun rowsFor(isPayer: Boolean): MutableList<ShareRow> = if (isPayer) payerShareRows else payeeShareRows

    private fun refreshEndpointInput(field: AutoCompleteTextView, actorType: String) {
        val isPayer = isPayerField(field)
        val isLocked = isSmsLockedMeEndpoint(isPayer)

        if (actorType == ActorType.ME) {
            field.setText("Me", false)
        } else {
            if (field.text.toString() == "Me") {
                field.setText(smsAliasFallback(isPayer), false)
            }
            restoreRequiredSmsAliasIfNeeded(field, isPayer)
        }

        field.isEnabled = !isLocked
        field.alpha = if (isLocked) 0.5f else 1f
    }

    private fun isPayerField(field: AutoCompleteTextView): Boolean = field.id == R.id.etPayerAlias

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

    private fun updateMerchantToggleStyles() {
        val payerLocked = isSmsLockedMeEndpoint(true)
        val payeeLocked = isSmsLockedMeEndpoint(false)

        tbPayerMerchant.isEnabled = !payerLocked
        tbPayeeMerchant.isEnabled = !payeeLocked

        styleMerchantToggle(tbPayerMerchant, tbPayerMerchant.isChecked, payerLocked)
        styleMerchantToggle(tbPayeeMerchant, tbPayeeMerchant.isChecked, payeeLocked)
    }

    private fun styleMerchantToggle(button: ToggleButton, checked: Boolean, locked: Boolean) {
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
            ActorType.MERCHANT -> allMerchants.map { it.name }
            else -> listOf("Me") + allFriends.map { it.name }
        }
        field.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions))
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
        if (payerActorType != ActorType.MERCHANT && payerShareRows.isEmpty()) {
            payerShareRows.add(buildBaseShareRow(true))
        }
        if (payeeActorType != ActorType.MERCHANT && payeeShareRows.isEmpty()) {
            payeeShareRows.add(buildBaseShareRow(false))
        }
    }

    private fun updateShareSectionVisibility(isPayer: Boolean) {
        val isMerchant = actorTypeFor(isPayer) == ActorType.MERCHANT
        val container = if (isPayer) payerSharesContainer else payeeSharesContainer
        val addBtn = if (isPayer) btnAddPayerPerson else btnAddPayeePerson
        val balanceTv = if (isPayer) tvPayerBalance else tvPayeeBalance
        val visibility = if (isMerchant) View.GONE else View.VISIBLE
        container.visibility = visibility
        addBtn.visibility = visibility
        balanceTv.visibility = visibility
    }

    private fun buildShareSection(isPayer: Boolean) {
        val container = if (isPayer) payerSharesContainer else payeeSharesContainer
        val rows = rowsFor(isPayer)
        container.removeAllViews()

        if (actorTypeFor(isPayer) == ActorType.MERCHANT) return

        rows.forEachIndexed { index, row ->
            val rowView = LayoutInflater.from(this).inflate(R.layout.item_share_row, container, false)
            val tvAvatar = rowView.findViewById<TextView>(R.id.tvShareAvatar)
            val etName = rowView.findViewById<AutoCompleteTextView>(R.id.etShareName)
            val etAmount = rowView.findViewById<EditText>(R.id.etShareAmount)
            val btnRemove = rowView.findViewById<TextView>(R.id.btnRemoveShare)

            val avatarSize = dp(36)
            tvAvatar.layoutParams.width = avatarSize
            tvAvatar.layoutParams.height = avatarSize
            tvAvatar.text = row.initials
            tvAvatar.background = circleDrawable(
                if (row.participantType == ActorType.ME) Color.parseColor("#00897B") else Color.parseColor("#5C6BC0"),
                Color.TRANSPARENT,
                0
            )

            if (index == 0) {
                btnRemove.visibility = View.GONE
                etName.setText(row.label, false)
                etName.isEnabled = false
                etName.alpha = 0.7f
            } else {
                val excludedKeys = rows.map { it.key }.toSet()
                val hasMeAlready = rows.any { it.participantType == ActorType.ME }
                val showMeOption = !hasMeAlready || row.participantType == ActorType.ME
                val availableFriends = allFriends.filter { "F:${it.id}" !in excludedKeys || it.id == row.friendId }

                val suggestions = mutableListOf<String>()
                if (showMeOption) suggestions.add("Me")
                suggestions.addAll(availableFriends.map { it.name })

                etName.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions))
                etName.setText(row.label, false)
                etName.setOnItemClickListener { _, _, position, _ ->
                    val selected = etName.adapter.getItem(position) as String
                    if (selected == "Me") {
                        updateShareRowParticipant(rows, index, ActorType.ME, null, "Me", "ME")
                    } else {
                        val friend = availableFriends.find { it.name == selected } ?: return@setOnItemClickListener
                        updateShareRowParticipant(rows, index, ActorType.FRIEND, friend.id, friend.name, friend.avatarInitials)
                    }
                    hideKeyboard(etName)
                    buildShareSection(isPayer)
                    updateLiveCalc()
                }
                etName.addTextChangedListener(simpleWatcher {
                    val trimmed = etName.text.toString().trim()
                    val old = rows[index]
                    rows[index] = ShareRow(old.key, old.participantType, old.friendId, trimmed, old.initials, old.amountPaise)
                    val match = allFriends.firstOrNull { it.name == trimmed }
                    if (match != null) {
                        updateShareRowParticipant(rows, index, ActorType.FRIEND, match.id, match.name, match.avatarInitials)
                    }
                })
                etName.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && rows[index].key.isBlank()) {
                        val name = etName.text.toString().trim()
                        if (name.isNotBlank()) {
                            activityScope.launch {
                                commitShareRowName(isPayer, index, name)
                            }
                        }
                    }
                }
                wireImeDismiss(etName) {
                    val name = etName.text.toString().trim()
                    if (name.isNotBlank()) {
                        activityScope.launch {
                            commitShareRowName(isPayer, index, name)
                        }
                    }
                }
                btnRemove.setOnClickListener {
                    rows.removeAt(index)
                    buildShareSection(isPayer)
                    updateLiveCalc()
                }
            }

            if (row.amountPaise > 0) {
                etAmount.setText(formatPlainAmount(row.amountPaise))
            }
            etAmount.addTextChangedListener(simpleWatcher {
                row.amountPaise = ((etAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
                updateSectionBalance(isPayer)
                updateLiveCalc()
            })
            wireImeDismiss(etAmount)

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
        val old = rows[index]
        val key = if (participantType == ActorType.ME) "ME" else "F:$friendId"
        rows[index] = ShareRow(key, participantType, friendId, label, initials, old.amountPaise)
    }

    private fun addShareRow(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        val amount = suggestShareAmount(rows)
        rows.add(ShareRow("", ActorType.FRIEND, null, "", "?", amount))
        buildShareSection(isPayer)
        updateLiveCalc()
    }

    private fun getCurrentAmountPaise(): Long = ((etAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()

    private fun suggestShareAmount(rows: MutableList<ShareRow>): Long {
        val total = getCurrentAmountPaise()
        val remaining = (total - rows.sumOf { it.amountPaise }).coerceAtLeast(0L)
        return if (remaining > 0) remaining else total
    }

    private fun getMyShareAmount(side: String): Long {
        val rows = if (side == "PAYER") payerShareRows else payeeShareRows
        return rows.firstOrNull { it.participantType == ActorType.ME }?.amountPaise ?: 0L
    }

    private fun myShareForCategories(): Long {
        val meSide = when {
            payerActorType == ActorType.MERCHANT -> "PAYEE"
            payeeActorType == ActorType.MERCHANT -> "PAYER"
            else -> null
        }
        return meSide?.let { getMyShareAmount(it) } ?: 0L
    }

    private fun updateSectionBalance(isPayer: Boolean) {
        val rows = rowsFor(isPayer)
        val total = getCurrentAmountPaise()
        val summed = rows.sumOf { it.amountPaise }
        val tv = if (isPayer) tvPayerBalance else tvPayeeBalance
        tv.text = when {
            summed == total -> "✓ Balanced"
            summed > total -> "Over by Rs${formatPlainAmount(summed - total)}"
            else -> "Remaining: Rs${formatPlainAmount(total - summed)}"
        }
    }

    private fun updateLiveCalc() {
        updateSectionBalance(true)
        updateSectionBalance(false)

        val amount = getCurrentAmountPaise()
        val payerSummed = if (payerActorType != ActorType.MERCHANT) payerShareRows.sumOf { it.amountPaise } else 0L
        val payeeSummed = if (payeeActorType != ActorType.MERCHANT) payeeShareRows.sumOf { it.amountPaise } else 0L
        val totalShared = payerSummed + payeeSummed
        val maxOver = maxOf(payerSummed - amount, payeeSummed - amount, 0L)

        tvBalance.text = when {
            maxOver > 0 -> "Over-allocated: Rs${formatPlainAmount(maxOver)}"
            payerActorType != ActorType.MERCHANT && payerSummed < amount -> "Payer unallocated: Rs${formatPlainAmount(amount - payerSummed)}"
            payeeActorType != ActorType.MERCHANT && payeeSummed < amount -> "Payee unallocated: Rs${formatPlainAmount(amount - payeeSummed)}"
            totalShared == 0L && amount > 0 -> "Unallocated"
            else -> ""
        }
        updateCategoryVisibility()
    }

    private fun updateCategoryVisibility() {
        val myShare = myShareForCategories()
        val showCategories = isMerchantInvolved() && myShare > 0
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
                        entry.etMyAmount.setText(formatPlainAmount(myShare))
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
        if (payerActorType != ActorType.MERCHANT) {
            for (i in 1 until payerShareRows.size) {
                val row = payerShareRows[i]
                if (row.key.isBlank() || row.label.isBlank()) {
                    toast("Select a person for all share rows")
                    return false
                }
            }
            val summed = payerShareRows.sumOf { it.amountPaise }
            if (summed != amountPaise) {
                toast("Payer shares don't match total")
                return false
            }
        }
        if (payeeActorType != ActorType.MERCHANT) {
            for (i in 1 until payeeShareRows.size) {
                val row = payeeShareRows[i]
                if (row.key.isBlank() || row.label.isBlank()) {
                    toast("Select a person for all share rows")
                    return false
                }
            }
            val summed = payeeShareRows.sumOf { it.amountPaise }
            if (summed != amountPaise) {
                toast("Payee shares don't match total")
                return false
            }
        }
        return true
    }

    private suspend fun persistTransaction(db: AppDatabase, amountPaise: Long, payerLabel: String, payeeLabel: String) {
        val tx = currentTransaction
        db.runInTransaction {
            runBlocking {
                val payer = resolveActor(db, true, payerActorType, payerLabel)
                val payee = resolveActor(db, false, payeeActorType, payeeLabel)
                resolveUnresolvedShareRows(db)
                val shares = buildSharesForPersistence(tx?.id ?: 0L)
                val meSharePaise = mySharePaiseFromShares(shares)
                val base = (tx ?: Transaction(
                    amountPaise = amountPaise,
                    payerActorType = payer.actorType,
                    payerFriendId = payer.friendId,
                    payerMerchantId = payer.merchantId,
                    payerRawLabel = payer.rawLabel,
                    payeeActorType = payee.actorType,
                    payeeFriendId = payee.friendId,
                    payeeMerchantId = payee.merchantId,
                    payeeRawLabel = payee.rawLabel,
                    myAccountId = selectedAccountId,
                    dateEpoch = System.currentTimeMillis(),
                    source = "MANUAL",
                    isPending = false
                )).copy(
                    amountPaise = amountPaise,
                    payerActorType = payer.actorType,
                    payerFriendId = payer.friendId,
                    payerMerchantId = payer.merchantId,
                    payerRawLabel = payer.rawLabel,
                    payeeActorType = payee.actorType,
                    payeeFriendId = payee.friendId,
                    payeeMerchantId = payee.merchantId,
                    payeeRawLabel = payee.rawLabel,
                    myAccountId = selectedAccountId,
                    isPending = false
                )
                val transactionId = if (tx == null) db.transactionDao().insert(base) else {
                    db.transactionDao().update(base)
                    tx.id
                }

                db.iouDao().deleteForTransaction(transactionId)
                db.categorySplitDao().deleteForTransaction(transactionId)
                db.transactionShareDao().deleteForTransaction(transactionId)

                val persistedShares = shares.map { it.copy(transactionId = transactionId) }
                if (persistedShares.isNotEmpty()) db.transactionShareDao().insertAll(persistedShares)

                persistCategories(db, transactionId, meSharePaise)
                Log.d("TAG", "persistTransaction: $transactionId")
                postLedger(db, transactionId, payer, payee, persistedShares, amountPaise)
            }
        }
    }

    private fun mySharePaiseFromShares(shares: List<TransactionShare>): Long {
        val meSide = when {
            payerActorType == ActorType.MERCHANT -> "PAYEE"
            payeeActorType == ActorType.MERCHANT -> "PAYER"
            else -> null
        }
        return meSide?.let { meShareOnSide(shares, it) } ?: 0L
    }

    private fun buildSharesForPersistence(txId: Long): List<TransactionShare> {
        val result = mutableListOf<TransactionShare>()
        payerShareRows.forEach { row ->
            if (row.key.isNotBlank() && row.amountPaise > 0) {
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
            if (row.key.isNotBlank() && row.amountPaise > 0) {
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
            ?: Friend(id = id, name = name, avatarInitials = initials, addedEpoch = System.currentTimeMillis())
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

        val db = AppDatabase.getInstance(applicationContext)
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
                val friend = existing ?: withContext(Dispatchers.IO) { resolveOrCreateFriend(db, name) }.also { rememberFriend(it) }
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
        return db.merchantDao().insertMerchant(Merchant(name = typedLabel, addedEpoch = System.currentTimeMillis()))
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
            db.merchantDao().insertRawName(MerchantRawName(merchantId = merchantId, rawName = rawLabel))
        }
    }

    private suspend fun persistCategories(db: AppDatabase, transactionId: Long, meSharePaise: Long) {
        if (!isMerchantInvolved() || meSharePaise <= 0L) return
        val merchantId = selectedMerchantId() ?: return
        categoryEntries.filter { it.chip.isChecked }.forEach { entry ->
            db.categoryDao().linkMerchantCategory(MerchantCategory(merchantId = merchantId, categoryId = entry.category.id))
            val myAmount = ((entry.etMyAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
            if (myAmount > 0L) {
                db.categorySplitDao().insert(TransactionCategorySplit(transactionId, entry.category.id, myAmount, 0L))
            }
        }
    }

    private suspend fun postLedger(
        db: AppDatabase,
        transactionId: Long,
        payer: ActorRef,
        payee: ActorRef,
        shares: List<TransactionShare>,
        amountPaise: Long
    ) {
        val ledger = LedgerManager(db)

        // Pure settlement shortcuts — no share splits involved
        // Friend pays me directly: they're repaying a debt they owed me
        if (payer.actorType == ActorType.FRIEND
            && payee.actorType == ActorType.ME
            && shares.isEmpty()
            && payer.friendId != null
        ) {
            ledger.applyRepayment(transactionId, payer.friendId, amountPaise)
            return
        }
        // I pay friend directly: I'm settling a debt I owed them
        if (payer.actorType == ActorType.ME
            && payee.actorType == ActorType.FRIEND
            && shares.isEmpty()
            && payee.friendId != null
        ) {
            ledger.applyOutgoingSettlement(transactionId, payee.friendId, amountPaise)
            return
        }

        // ── Case 1: ME is primary PAYER ──────────────────────────────────────────
        // Secondary PAYER friends owe me their payer-share
        if (payer.actorType == ActorType.ME) {
            shares
                .filter {
                    it.side == "PAYER"
                            && it.participantType == ActorType.FRIEND
                            && it.friendId != null
                }
                .forEach { share ->
                    ledger.recordBalanceChange(transactionId, share.friendId!!, share.amountPaise)
                }

            // Primary PAYEE is a friend → they owe me my own payer-share
            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (payee.actorType == ActorType.FRIEND && payee.friendId != null && mePayerShare > 0L) {
                ledger.recordBalanceChange(transactionId, payee.friendId, mePayerShare)
            }
        }

        // ── Case 2: ME is secondary PAYER (friend is primary) ───────────────────
        // I owe the primary payer friend my own payer-share
        if (payer.actorType == ActorType.FRIEND && payer.friendId != null) {
            val mePayerShare = meShareOnSide(shares, "PAYER")
            if (mePayerShare > 0L) {
                ledger.recordBalanceChange(transactionId, payer.friendId, -mePayerShare)
            }
        }

        // ── Case 3: ME is primary PAYEE ─────────────────────────────────────────
        // I owe the primary payer friend my own payee-share
        // I also owe each secondary PAYEE friend their payee-share (I'm distributing on their behalf)
        if (payee.actorType == ActorType.ME) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (payer.actorType == ActorType.FRIEND && payer.friendId != null && mePayeeShare > 0L) {
                ledger.recordBalanceChange(transactionId, payer.friendId, -mePayeeShare)
            }

            shares
                .filter {
                    it.side == "PAYEE"
                            && it.participantType == ActorType.FRIEND
                            && it.friendId != null
                }
                .forEach { share ->
                    ledger.recordBalanceChange(transactionId, share.friendId!!, -share.amountPaise)
                }
        }

        // ── Case 4: ME is secondary PAYEE (friend is primary PAYEE) ─────────────
        // Primary PAYEE friend owes me my own payee-share
        if (payee.actorType == ActorType.FRIEND && payee.friendId != null) {
            val mePayeeShare = meShareOnSide(shares, "PAYEE")
            if (mePayeeShare > 0L) {
                ledger.recordBalanceChange(transactionId, payee.friendId, mePayeeShare)
            }
        }
    }

    private fun hideKeyboard(view: View? = currentFocus) {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
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

    private fun fmtDateTime(epoch: Long): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(epoch))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatPlainAmount(paise: Long): String = "%.0f".format(paise / 100.0)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
