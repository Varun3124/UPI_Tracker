package com.varun.upitracker.ui.transactionentry

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import com.varun.upitracker.R
import com.varun.upitracker.data.declaration.DeclarationRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.data.repository.AccountMutationException
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.MerchantCategory
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.data.prefs.SelfIdentity
import com.varun.upitracker.data.repository.ChapterException
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.data.repository.EntryDraftRepository
import com.varun.upitracker.data.repository.EntryDraftSnapshot
import com.varun.upitracker.data.repository.PendException
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntryDraft
import com.varun.upitracker.database.entity.EntryDraftCategory
import com.varun.upitracker.database.entity.EntryDraftRow
import com.varun.upitracker.domain.entrySource
import com.varun.upitracker.domain.transactionSource
import com.varun.upitracker.domain.transactionentry.category.MerchantCategoryHistory
import com.varun.upitracker.data.repository.ReceivedOriginalRepository
import com.varun.upitracker.data.repository.ReceivedOriginalView
import com.varun.upitracker.data.repository.RevertException
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.maintenance.ChapterPublishing
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.FriendLinkState
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterOption
import com.varun.upitracker.domain.chapter.ChapterPrompt
import kotlinx.coroutines.suspendCancellableCoroutine
import com.varun.upitracker.database.entity.TransactionCategorySplit
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.database.entity.EntrySource
import com.varun.upitracker.domain.AccountTransferTypeResolver
import com.varun.upitracker.domain.BalanceDeltaCalculator
import com.varun.upitracker.domain.TransferDeltaInput
import com.varun.upitracker.domain.TransferTypeResolution
import com.varun.upitracker.domain.iou.IouLine
import com.varun.upitracker.domain.iou.IouLegs
import com.varun.upitracker.domain.iou.IouParty
import com.varun.upitracker.domain.iou.IouLegSource
import com.varun.upitracker.domain.transactionentry.actor.ActorSelectionService
import com.varun.upitracker.domain.transactionentry.category.CategorySplitManager
import com.varun.upitracker.domain.transactionentry.persistence.PersistTransactionRequest
import com.varun.upitracker.domain.transactionentry.persistence.TransactionPersistenceService
import com.varun.upitracker.domain.transactionentry.share.CategoryTargeting
import com.varun.upitracker.domain.transactionentry.share.OverallAllocationState
import com.varun.upitracker.domain.transactionentry.share.SectionBalanceState
import com.varun.upitracker.domain.transactionentry.share.ShareCalculator
import com.varun.upitracker.domain.transactionentry.share.ShareManager
import com.varun.upitracker.domain.transactionentry.share.ShareRowModel
import com.varun.upitracker.domain.transactionentry.validation.AccountTransferValidator
import com.varun.upitracker.domain.transactionentry.validation.ShareValidationRow
import com.varun.upitracker.domain.transactionentry.validation.ValidationResult
import com.varun.upitracker.domain.transactionentry.validation.TransactionValidator
import com.varun.upitracker.ui.declaration.AgreementDialogs
import com.varun.upitracker.ui.declaration.AgreementText
import com.varun.upitracker.domain.transactionentry.validation.TransferValidationInput
import com.varun.upitracker.sms.receiver.TransactionNotificationHelper
import com.varun.upitracker.ui.ActorRef
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.displayName
import com.varun.upitracker.ui.ScreenViewModelFactory
import com.varun.upitracker.ui.TransactionEntryViewModel
import com.varun.upitracker.ui.meShareOnSide
import com.varun.upitracker.ui.openDashboardIfLeavingRoot
import com.varun.upitracker.ui.payeeActorRef
import com.varun.upitracker.ui.payerActorRef
import com.varun.upitracker.ui.resolveActorDisplayName
import com.varun.upitracker.ui.resolvePrimaryDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.ui.theme.Avatars
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.theme.padRootForSystemBars
import android.widget.ImageButton
import com.varun.upitracker.util.initialsOf
import com.varun.upitracker.ui.theme.dp

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"

        /** Separate from [EXTRA_TRANSACTION_ID] because `AccountTransfer.id` is a UUID string. */
        const val EXTRA_TRANSFER_ID = "transfer_id"

        /**
         * With [EXTRA_TRANSACTION_ID] on a received row: show it as the friend sent it, read-only,
         * with Revert in place of Done.
         */
        const val EXTRA_SHOW_ORIGINAL = "show_original"

        /** What the original's page answers with once it has put the row back. */
        const val RESULT_REVERTED = RESULT_FIRST_USER + 1

        private const val NOT_A_REFUND_LABEL = "Not a refund - counts as income"

        /** The picker is a flat dialog list, so keep it to a scannable number of recent purchases. */
        private const val REFUND_CANDIDATE_LIMIT = 60

        /**
         * A primary friend row whose name matches nobody yet. Not blank, so the row still counts in
         * the live split; saving replaces it with the friend the name resolves to.
         */
        private const val UNRESOLVED_PRIMARY_KEY = "F:?"
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
        var amountPaise: Long,
        /** Which of this row's IOUs are kept. See [TransactionShare.keepPayeeLeg]. */
        var keepPayeeLeg: Boolean = true,
        var keepPayerLeg: Boolean = true,
        /** Who each of them runs through, or null for straight. See [TransactionShare.payeeLegVia]. */
        var payeeVia: IouParty? = null,
        var payerVia: IouParty? = null
    ) {
        /**
         * The name to persist alongside a share with no friend behind it, so opening and saving an
         * imported transaction does not quietly erase who the sender said this was. Null once the
         * row names a real friend -- their name lives on the friend, not on every share.
         */
        fun carriedLabel(): String? =
            label.takeIf { participantType == ActorType.FRIEND && friendId == null && it.isNotBlank() && it != "Friend" }
    }

    private val shareCalculator = ShareCalculator()
    private val shareManager = ShareManager()
    private val actorSelectionService = ActorSelectionService()
    private val categorySplitManager = CategorySplitManager()
    private val transactionPersistenceService = TransactionPersistenceService()
    private val transactionValidator = TransactionValidator()
    private val accountTransferValidator = AccountTransferValidator()
    private lateinit var viewModel: TransactionEntryViewModel

    private var currentTransaction: Transaction? = null

    /** The alias friends see on what this person sends, or null if they never gave one. */
    private val selfAlias: String? by lazy { SelfIdentity(this).alias() }

    /**
     * How a share row names the user: "Asha(Me)" once they have an alias, so the dropdown reads as
     * them rather than as a word. Display only -- what is saved for ME stays "Me".
     */
    private val meLabel: String by lazy { selfAlias?.let { "$it(Me)" } ?: "Me" }

    /** This page is a received row's original: read-only, and its one action is Revert. */
    private val isOriginalView: Boolean by lazy { intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, false) }
    /** What a friend sent, when the row came from one after originals were kept. */
    private var originalView: ReceivedOriginalView? = null
    /** Set on the original's page only: the shares as sent, shown in place of the row's own. */
    private var originalShares: List<TransactionShare>? = null

    /** The original's page closes this one too when it reverts: what was being edited is gone. */
    private val originalLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_REVERTED) finish()
    }
    /**
     * Every chapter, open or not, a friend's included: a transaction may already sit in a closed one,
     * or be claimed into a friend's. Only the user's own open ones are ever offered (S10).
     */
    private var chapterOptions: List<ChapterOption> = emptyList()
    /** Who each friend's chapter here belongs to, by chapter id, for saying so on the field. */
    private var chapterOwnerNames: Map<Long, String> = emptyMap()
    private var selectedChapterId: Long? = null
    /** R8: the row is inside a closed chapter, so nothing here may be saved at all. */
    private var chapterLocked = false
    private var currentTransfer: AccountTransfer? = null
    private var isSmsSource = false
    private var allFriends = listOf<Friend>()
    private var allMerchants = listOf<Merchant>()
    private var allCategories = listOf<Category>()
    private var transactionAccounts = listOf<Account>()
    private var transferAccounts = listOf<Account>()
    private var payerAccountId: String? = null
    private var payeeAccountId: String? = null
    private var selectedDateEpoch = System.currentTimeMillis()

    private var payerActorType = ActorType.ME
    private var payeeActorType = ActorType.MERCHANT
    private var payerFriendId: Long? = null
    private var payerMerchantId: Long? = null
    private var payeeFriendId: Long? = null
    private var payeeMerchantId: Long? = null
    /**
     * The side picked on the pay-back pills, or null to follow [IouLegs.shapeDefault] as the split
     * changes. Set on load only when the choice already mattered for the saved row.
     */
    private var iouRecoveryOverride: IouRecovery? = null
    /** Kind the pill list is currently offering; a change clears what was checked. */
    private var categoryKind = CategoryKind.EXPENSE
    private var refundsTransactionId: Long? = null
    /** Purchases this could be a refund for, with display labels, resolved off the UI thread. */
    private var refundCandidates = listOf<Pair<Transaction, String>>()
    /**
     * When this is a linked refund, the categories the original used. The pill list narrows to
     * these so a refund can only give money back to where the purchase actually put it.
     */
    private var refundAllowedCategoryIds: Set<Long>? = null
    /** Merchant [refundCandidates] was built for, so the reload can be skipped when unchanged. */
    private var loadedRefundMerchantId: Long? = null

    private val categoryEntries = mutableListOf<CategoryEntry>()
    /** The category whose split amount tracks ME's remaining share as it changes; null once cleared or unchecked. */
    private var trackedCategoryId: Long? = null
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
    private lateinit var amountRow: View
    private lateinit var dateSection: View
    private lateinit var tvDateValue: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvPayerBalance: TextView
    private lateinit var tvPayeeBalance: TextView
    private lateinit var payerSharesContainer: LinearLayout
    private lateinit var payeeSharesContainer: LinearLayout
    private lateinit var btnAddPayerPerson: Button
    private lateinit var btnAddPayeePerson: Button
    private lateinit var btnEqualize: Button
    private lateinit var categoryContainer: FlexboxLayout
    private lateinit var categoryScrollView: View
    private lateinit var formScroll: ScrollView
    private lateinit var etDescription: EditText
    private lateinit var chapterCard: View
    private lateinit var agreementNotice: View
    private lateinit var tvAgreementNotice: TextView
    private lateinit var btnAgreementAdd: TextView

    /** Each friend's agreed checkpoint, loaded once: what this row's date is measured against (D8). */
    private var checkpoints: Map<Long, IouDeclaration> = emptyMap()

    /** Friends it is possible to propose anything to right now. */
    private var linkedFriendIds: Set<Long> = emptySet()

    private val declarations by lazy {
        DeclarationRepository(applicationContext, AppDatabase.getInstance(applicationContext))
    }
    private val agreementDialogs by lazy { AgreementDialogs(this, declarations) {} }
    private lateinit var tvChapterValue: TextView
    private lateinit var tvChapterHint: TextView
    private lateinit var ledgerOptionsCard: View
    private lateinit var iouRecoverySection: LinearLayout
    private lateinit var iouRecoveryPills: View
    private lateinit var tvRecoverFromPayers: TextView
    private lateinit var tvRecoverFromPayees: TextView
    private lateinit var iouLinesContainer: LinearLayout
    /** What the line pills last showed, so a keystroke that changes no line does not rebuild them. */
    private var renderedIouLines: List<List<Any?>>? = null
    private lateinit var refundSection: LinearLayout
    private lateinit var tvRefundTarget: TextView

    private var smsPayerAliasFallback = ""
    private var smsPayeeAliasFallback = ""
    private var isCategorySectionVisible = false

    /** What each merchant has only ever been filed under, by merchant and direction. Read once per screen. */
    private var merchantCategories: Map<Pair<Long, CategoryKind>, Long> = emptyMap()
    /** Set once the user checks, unchecks or types into a pill: from then on the categories are theirs. */
    private var userTouchedCategories = false
    /** The pill [maybePrefillCategory] checked by itself, so a different merchant can swap it out. */
    private var prefilledCategoryId: Long? = null

    /** The form pended for this row, which it reopens as. Pending rows only. */
    private var draftSnapshot: EntryDraftSnapshot? = null
    /** The form was put back from [draftSnapshot]: nothing may relabel, reseed or clear what it holds. */
    private var restoredFromDraft = false

    /**
     * Each side's rows as the chapter template last left them, for a new entry. A side still equal to
     * its snapshot is untouched, and follows the chapter picker; once edited, it is the user's.
     */
    private val templateSnapshot = mutableMapOf<Boolean, List<ShareRow>>()

    private lateinit var btnPend: Button

    /**
     * Who the user chose, on this save, to keep outside the selected chapter: friends by id, and anyone
     * typed in fresh by name, who only gets an id as the save resolves them. See [decideOutsiders].
     */
    private var keptOutsideIds: Set<Long> = emptySet()
    private var keptOutsideNames: Set<String> = emptySet()
    /** Who the saved row already keeps outside its chapter: saving again does not ask about them again. */
    private var storedOutsiders: Set<Long> = emptySet()
    private lateinit var formContent: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_transaction)

        padRootForSystemBars(R.id.main)

        bindViews()
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it != -1L }
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)?.takeIf { it.isNotBlank() }
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
            transferAccounts = referenceData.transferAccounts
            currentTransaction = referenceData.transaction
            currentTransfer = referenceData.transfer
            originalView = referenceData.original
            originalShares = referenceData.originalShares
            draftSnapshot = referenceData.draft
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
        viewModel.load(transactionId, transferId, showOriginal = isOriginalView)
    }

    /** Opened from a notification or the overlay, this can be the task's root: see [openDashboardIfLeavingRoot]. */
    override fun finish() {
        openDashboardIfLeavingRoot()
        super.finish()
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
        amountRow = findViewById(R.id.amountRow)
        dateSection = findViewById(R.id.dateSection)
        tvDateValue = findViewById(R.id.tvDateValue)
        tvBalance = findViewById(R.id.tvBalance)
        tvPayerBalance = findViewById(R.id.tvPayerBalance)
        tvPayeeBalance = findViewById(R.id.tvPayeeBalance)
        payerSharesContainer = findViewById(R.id.payerSharesContainer)
        payeeSharesContainer = findViewById(R.id.payeeSharesContainer)
        btnAddPayerPerson = findViewById(R.id.btnAddPayerPerson)
        btnAddPayeePerson = findViewById(R.id.btnAddPayeePerson)
        btnEqualize = findViewById(R.id.btnEqualize)
        categoryContainer = findViewById(R.id.categoryContainer)
        categoryScrollView = findViewById(R.id.categoryScrollView)
        etDescription = findViewById(R.id.etDescription)
        ledgerOptionsCard = findViewById(R.id.ledgerOptionsCard)
        refundSection = findViewById(R.id.refundSection)
        tvRefundTarget = findViewById(R.id.tvRefundTarget)
        iouRecoverySection = findViewById(R.id.iouRecoverySection)
        iouRecoveryPills = findViewById(R.id.iouRecoveryPills)
        tvRecoverFromPayers = findViewById(R.id.tvRecoverFromPayers)
        tvRecoverFromPayees = findViewById(R.id.tvRecoverFromPayees)
        iouLinesContainer = findViewById(R.id.iouLinesContainer)
        tvRecoverFromPayers.setOnClickListener {
            viewModel.onAction(TransactionEntryAction.IouRecoverySelected(IouRecovery.FROM_SECONDARY_PAYERS))
        }
        tvRecoverFromPayees.setOnClickListener {
            viewModel.onAction(TransactionEntryAction.IouRecoverySelected(IouRecovery.FROM_SECONDARY_PAYEES))
        }
        refundSection.setOnClickListener { showRefundPicker() }
        chapterCard = findViewById(R.id.chapterCard)
        tvChapterValue = findViewById(R.id.tvChapterValue)
        tvChapterHint = findViewById(R.id.tvChapterHint)
        chapterCard.setOnClickListener { showChapterPicker() }
        agreementNotice = findViewById(R.id.agreementNotice)
        tvAgreementNotice = findViewById(R.id.tvAgreementNotice)
        btnAgreementAdd = findViewById(R.id.btnAgreementAdd)
        btnAgreementAdd.setOnClickListener { askToAddSavedRow() }
        btnPend = findViewById(R.id.btnPend)
        formContent = findViewById(R.id.formContent)
        // The keyboard going away by itself leaves the field focused; leaving it is what finishes an
        // edit (a blank alias refilled, a typed name committed), so let go of it here too.
        KeyboardAwareScroll(formScroll, formContent) {
            (currentFocus as? EditText)?.clearFocus()
        }.attach()
    }

    private suspend fun setupUi(db: AppDatabase) {
        val tx = currentTransaction
        val transfer = currentTransfer
        isSmsSource = transfer == null && (tx?.source == "SMS" || tx?.source == "NOTIFICATION")
        selectedDateEpoch = tx?.dateEpoch ?: transfer?.dateEpoch ?: System.currentTimeMillis()
        val asSent = originalView?.takeIf { isOriginalView }
        tvTopInfo.text = when {
            transfer != null -> "${transfer.type.displayName()} - ${fmtDateTime(selectedDateEpoch)}"
            asSent != null -> "As ${asSent.original.senderName} sent it - ${fmtDateTime(selectedDateEpoch)}"
            tx != null -> "${resolveHeaderLabel(db, tx)} - ${fmtDateTime(selectedDateEpoch)}"
            else -> "Manual Entry - ${fmtDateTime(selectedDateEpoch)}"
        }

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            viewModel.onAction(TransactionEntryAction.CloseClicked)
        }
        setupEndpointControls()
        setupAmountField()
        etDescription.addTextChangedListener(simpleWatcher { viewModel.onAction(TransactionEntryAction.DescriptionChanged(it.toString())) })
        setupDateSection()
        initializeSelectedAccounts(tx)
        setupCategories()
        loadRefundCandidates(db)
        merchantCategories = withContext(Dispatchers.IO) {
            MerchantCategoryHistory.singleCategories(db.categorySplitDao().getMerchantCategoryUses(null))
        }

        val draft = draftSnapshot
        when {
            transfer != null -> populateExistingTransfer(transfer)
            tx != null && draft != null -> restoreDraft(tx, draft, db)
            tx != null -> populateExistingTransaction(tx, db)
            else -> seedDefaultState()
        }
        setupChapterField(db)
        // An original is only read: nothing it shows can be added to an agreement from here.
        if (!isOriginalView) loadAgreements(db)
        ensureBaseShareRows()
        enforceTransferModeRows(seedLegs = !restoredFromDraft)
        if (!restoredFromDraft) {
            updatePrimaryRowLabel(true)
            updatePrimaryRowLabel(false)
        }
        applyChapterTemplate(true)
        applyChapterTemplate(false)

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            viewModel.onAction(TransactionEntryAction.SaveClicked)
        }
        btnPend.setOnClickListener {
            viewModel.onAction(TransactionEntryAction.PendClicked)
        }

        updateActorTileStyles()
        applyTransferModeUi()
        buildShareSection(true)
        buildShareSection(false)
        updateLiveCalc()
        renderCategories()
        renderOriginalControls()
    }

    /**
     * A received row is the receiver's to change in every way. When it has been changed and saved,
     * "Go to original" opens what the friend sent; on that page the form is shielded from edits and
     * Done becomes Revert.
     */
    private fun renderOriginalControls() {
        val goToOriginal = findViewById<Button>(R.id.btnGoToOriginal)
        val original = originalView
        goToOriginal.visibility =
            if (!isOriginalView && original != null && original.differs) View.VISIBLE else View.GONE
        goToOriginal.setOnClickListener {
            val tx = currentTransaction ?: return@setOnClickListener
            originalLauncher.launch(
                Intent(this, TransactionEntryActivity::class.java)
                    .putExtra(EXTRA_TRANSACTION_ID, tx.id)
                    .putExtra(EXTRA_SHOW_ORIGINAL, true)
            )
        }
        if (!isOriginalView) return

        // An original is only read, never kept as anything.
        btnPend.visibility = View.GONE
        findViewById<Button>(R.id.btnDone).apply {
            text = "Revert"
            backgroundTintList = ColorStateList.valueOf(themeColor(ThemeAttr.revert))
            setTextColor(themeColor(ThemeAttr.onRevert))
        }
        val close = findViewById<View>(R.id.btnClose)
        findViewById<View>(R.id.originalShield).apply {
            visibility = View.VISIBLE
            // Every tap is kept from the form except one on Close. Declining the touch is what
            // lets it fall through to the view underneath.
            setOnTouchListener { _, event -> !isOver(close, event) }
        }
    }

    private fun isOver(view: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] && x <= location[0] + view.width && y >= location[1] && y <= location[1] + view.height
    }

    private fun confirmRevert() {
        val original = originalView ?: return
        AlertDialog.Builder(this)
            .setTitle("Put this back as ${original.original.senderName} sent it?")
            .setMessage(
                "Your changes to it are undone. It becomes pending again, and leaves your balances until " +
                    "you confirm it. The account, categories and chapter you gave it are cleared."
            )
            .setPositiveButton("Revert") { _, _ -> viewModel.launchTask { revertToOriginal() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun revertToOriginal() {
        val tx = currentTransaction ?: return
        val db = AppDatabase.Companion.getInstance(applicationContext)
        val chapterId = try {
            withContext(Dispatchers.IO) { ReceivedOriginalRepository(db).revert(tx.id) }
        } catch (error: RevertException) {
            return toast(error.message ?: "Could not put it back.")
        }
        publishIfShared(db, setOfNotNull(chapterId))
        toast("Put back as it was sent. It is pending again.")
        setResult(RESULT_REVERTED)
        finish()
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

    private fun initializeSelectedAccounts(tx: Transaction?) {
        val primary = tx?.myAccountId ?: defaultAccountId()
        // Seed the other side with a different account so transfer mode starts in a valid state.
        val other = availableAccounts().firstOrNull { it.id != primary }?.id ?: primary
        // `myAccountId` belongs to whichever side is ME, which is the side
        // [accountIdForPersistence] reads back. On a credit that is the payee.
        val meIsPayee = tx != null &&
            tx.payeeActorType == ActorType.ME &&
            tx.payerActorType != ActorType.ME
        payerAccountId = if (meIsPayee) other else primary
        payeeAccountId = if (meIsPayee) primary else other
    }

    private fun defaultAccountId(): String? {
        val cashAccount = transactionAccounts.firstOrNull { it.type == AccountType.CASH && it.isDefault }
            ?: transactionAccounts.firstOrNull { it.type == AccountType.CASH }
        return cashAccount?.id ?: transactionAccounts.firstOrNull()?.id
    }

    /** Any active account is a valid transfer endpoint; a normal transaction sticks to cash/bank. */
    private fun availableAccounts(): List<Account> =
        if (isTransferMode()) transferAccounts else transactionAccounts

    private fun accountIdFor(isPayer: Boolean): String? = if (isPayer) payerAccountId else payeeAccountId

    private fun setAccountId(isPayer: Boolean, id: String?) {
        if (isPayer) payerAccountId = id else payeeAccountId = id
    }

    private fun accountLabelForDisplay(isPayer: Boolean): String {
        val accounts = availableAccounts()
        if (accounts.isEmpty()) return meLabel
        // Falling back also rewrites the id, so we never display one account and persist another.
        val account = accounts.firstOrNull { it.id == accountIdFor(isPayer) }
            ?: accounts.first().also { setAccountId(isPayer, it.id) }
        return account.label
    }

    private fun setupPrimaryAccountField(etName: AutoCompleteTextView, isPayer: Boolean) {
        val accounts = availableAccounts()
        val hasAccounts = accounts.isNotEmpty()
        etName.setText(accountLabelForDisplay(isPayer), false)
        etName.inputType = InputType.TYPE_NULL
        etName.keyListener = null
        etName.isCursorVisible = false
        etName.isEnabled = hasAccounts
        etName.alpha = if (hasAccounts) 1f else 0.7f
        etName.setOnClickListener(null)
        etName.setOnItemClickListener(null)
        if (!hasAccounts) return

        etName.threshold = 0
        etName.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                accounts.map { it.label }
            )
        )
        etName.setOnClickListener { etName.showDropDown() }
        etName.setOnItemClickListener { _, _, position, _ ->
            val account = accounts.getOrNull(position) ?: return@setOnItemClickListener
            etName.setText(account.label, false)
            viewModel.onAction(
                TransactionEntryAction.AccountSelected(
                    side = if (isPayer) EntrySide.PAYER else EntrySide.PAYEE,
                    accountId = account.id
                )
            )
            hideKeyboard()
            etName.clearFocus()
        }
    }

    private fun accountIdForPersistence(): String? = when {
        payerActorType == ActorType.ME -> payerAccountId
        payeeActorType == ActorType.ME -> payeeAccountId
        else -> null
    }?.takeIf { it.isNotBlank() }

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
        if (isSmsLockedMeEndpoint(isPayer) && selectedType != ActorType.ME) return
        if (!canSwitchTransferMode(isPayer, selectedType)) return
        val wasTransfer = isTransferMode()

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
            applyChapterTemplate(isPayer)
        }
        if (!wasTransfer && isTransferMode()) enterTransferMode()
        if (wasTransfer && !isTransferMode()) leaveTransferMode(isPayer)

        enforceTransferModeRows()
        updateActorTileStyles()
        applyTransferModeUi()
        // A transfer is never in a chapter; a transaction it turns into may be.
        renderChapterField()
        buildShareSection(true)
        buildShareSection(false)
        updateCategoryVisibility()
        updateLiveCalc()
    }

    /**
     * Into a transfer from a transaction: both legs start at the amount it moved -- not at whatever
     * share of a split ME's row happened to hold -- before the amount field is cleared for transfer mode.
     */
    private fun enterTransferMode() {
        val amount = getCurrentAmountPaise()
        if (amount <= 0L) return
        listOf(payerShareRows, payeeShareRows).forEach { rows -> rows.firstOrNull()?.amountPaise = amount }
    }

    /**
     * Back from a transfer to a transaction: the amount comes from the leg that stayed ME, and that
     * side, cut to one row while it was a transfer, gets its chapter template back.
     */
    private fun leaveTransferMode(switchedIsPayer: Boolean) {
        val kept = rowsFor(!switchedIsPayer).firstOrNull()?.amountPaise ?: 0L
        if (etAmount.text.isNullOrBlank() && kept > 0L) {
            etAmount.setText(AmountFormat.forInput(kept))
            rowsFor(switchedIsPayer).firstOrNull()?.takeIf { it.amountPaise == 0L }?.amountPaise = kept
        }
        if (rowsFor(!switchedIsPayer).size <= 1) applyChapterTemplate(!switchedIsPayer)
    }

    private fun isTransferMode(): Boolean =
        payerActorType == ActorType.ME && payeeActorType == ActorType.ME

    /**
     * Any record can change kind, saved or not. [handleDoneTransfer] turns a transaction into a
     * transfer the way a delete removes it -- its friends rebuilt, its chapter worked out again -- and
     * [handleDone] writes a transfer turned transaction in the same database transaction that deletes
     * the transfer, carrying the references the importers dedupe on.
     *
     * What still cannot: a row a chapter holds on to (closed, R8; or a friend's, S6), since becoming a
     * transfer takes it out of the chapter; and an FD return, which only the Accounts screen undoes.
     */
    private fun canSwitchTransferMode(isPayer: Boolean, selectedType: String): Boolean {
        val wouldBeTransfer = selectedType == ActorType.ME && otherSideActorType(isPayer) == ActorType.ME
        if (wouldBeTransfer == isTransferMode()) return true
        if (wouldBeTransfer) {
            if (chapterLocked) {
                toast("This is in a closed chapter. Reopen it to make changes.")
                return false
            }
            chapterOptions.firstOrNull { it.chapter.id == currentTransaction?.chapterId && !it.chapter.isOwn }?.let { copy ->
                val who = chapterOwnerNames[copy.chapter.id] ?: "its owner"
                toast("In $who's ${copy.chapter.name}. It stays there until $who takes it out.")
                return false
            }
        } else if (currentTransfer?.type == AccountTransferType.FD_RETURN) {
            toast("Edit FD returns from the Accounts screen.")
            return false
        }
        return true
    }

    /**
     * A transfer has exactly one endpoint per side: the source account and the destination.
     *
     * [seedLegs] false keeps an empty leg empty: a pended form comes back exactly as it was left.
     */
    private fun enforceTransferModeRows(seedLegs: Boolean = true) {
        if (!isTransferMode()) return
        // Seed an empty leg from the transaction being turned into a transfer, rather than making
        // the user retype the amount it already has.
        val seed = if (seedLegs) currentTransaction?.amountPaise ?: 0L else 0L
        listOf(payerShareRows, payeeShareRows).forEach { rows ->
            while (rows.size > 1) rows.removeAt(rows.lastIndex)
            rows.firstOrNull()?.let { row ->
                if (row.amountPaise == 0L && seed > 0L) row.amountPaise = seed
            }
        }
    }

    private fun applyTransferModeUi() {
        val transfer = isTransferMode()

        btnAddPayerPerson.visibility =
            if (transfer || payerActorType == ActorType.MERCHANT) View.GONE else View.VISIBLE
        btnAddPayeePerson.visibility =
            if (transfer || payeeActorType == ActorType.MERCHANT) View.GONE else View.VISIBLE
        // The whole amount row (Rs field + Equalize) is irrelevant in transfer mode: the
        // from/to amounts live on the share rows instead, previewed via tvBalance.
        amountRow.visibility = if (transfer) View.GONE else View.VISIBLE

        if (transfer) {
            // Guarded: etAmount has a watcher that would re-enter updateLiveCalc.
            if (etAmount.text.isNotEmpty()) etAmount.setText("")
            etAmount.isEnabled = false
        } else {
            etAmount.isEnabled = !isSmsSource || currentTransaction?.amountPaise == 0L
        }
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

    private fun setupCategories() = rebuildCategoryEntries()

    private suspend fun populateExistingTransaction(tx: Transaction, db: AppDatabase) {
        if (tx.amountPaise > 0) etAmount.setText(AmountFormat.forInput(tx.amountPaise))

        payerActorType = tx.payerActorType
        payeeActorType = tx.payeeActorType
        payerFriendId = tx.payerFriendId
        payerMerchantId = tx.payerMerchantId
        payeeFriendId = tx.payeeFriendId
        payeeMerchantId = tx.payeeMerchantId
        refundsTransactionId = tx.refundsTransactionId
        refundAllowedCategoryIds = tx.refundsTransactionId?.let { originalId ->
            withContext(Dispatchers.IO) {
                db.categorySplitDao().getForTransaction(originalId).map { it.categoryId }.toSet()
            }
        }

        smsPayerAliasFallback = resolveInitialEndpointLabel(db, true, tx)
        smsPayeeAliasFallback = resolveInitialEndpointLabel(db, false, tx)

        etDescription.setText(tx.reason ?: "")

        val shares = originalShares
            ?: withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
        seedShareRows(shares)
        // Saved as moving nobody's balance -- the old "doesn't affect what we owe" switch -- reads as
        // every IOU line left out, which is exactly what saving it again derives.
        if (tx.ledgerEffect == LedgerEffect.NONE) {
            (payerShareRows + payeeShareRows).forEach { row ->
                row.keepPayeeLeg = false
                row.keepPayerLeg = false
            }
        }

        // A row keeps its side only if it was already split, so a split added now starts from what its
        // shape suggests. A friend's transaction is no different: what it means here is the receiver's
        // to decide, and what was sent is kept apart, untouched, as its original.
        val savedIouRecovery = IouLegs.resolve(tx, shares)
        iouRecoveryOverride = savedIouRecovery.takeIf {
            IouLegs.choiceMatters(tx.payerActorRef(), tx.payeeActorRef(), shares, LedgerEffect.DEBT)
        }

        // Shares are seeded above, so targeting can now resolve the direction this transaction
        // categorises in. Rebuild the pill list for that kind before matching stored splits,
        // otherwise an income transaction's splits find no pill to attach to.
        applyCategoryKind(categoryTargeting().kind)

        // What a friend sent never carries categories: those are the receiver's own.
        val splits = if (isOriginalView) emptyList() else withContext(Dispatchers.IO) { db.categorySplitDao().getForTransaction(tx.id) }
        if (splits.isNotEmpty()) {
            categoryEntries.forEach { entry ->
                val split = splits.firstOrNull { it.categoryId == entry.category.id }
                if (split != null) {
                    entry.isChecked = true
                    entry.myAmountPaise = split.myAmountPaise
                }
            }
        }
    }

    private fun populateExistingTransfer(transfer: AccountTransfer) {
        payerActorType = ActorType.ME
        payeeActorType = ActorType.ME
        payerFriendId = null
        payerMerchantId = null
        payeeFriendId = null
        payeeMerchantId = null
        clearRefundTarget()
        payerAccountId = transfer.fromAccountId
        payeeAccountId = transfer.toAccountId
        etDescription.setText(transfer.notes ?: "")
        payerShareRows.clear()
        payeeShareRows.clear()
        payerShareRows.add(buildMeShareRow().copy(amountPaise = transfer.amountFromPaise))
        payeeShareRows.add(buildMeShareRow().copy(amountPaise = transfer.amountToPaise))
    }

    private fun seedDefaultState() {
        payerActorType = ActorType.ME
        payeeActorType = ActorType.MERCHANT
        clearRefundTarget()
        smsPayerAliasFallback = ""
        smsPayeeAliasFallback = ""
        payerShareRows.clear()
        payeeShareRows.clear()
        payerShareRows.add(buildMeShareRow())
        payeeShareRows.add(buildMerchantShareRow(false))
        etDescription.setText("")
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
        ActorType.ME -> listOf(meLabel)
        else -> allFriends.map { it.name }
    }

    private fun isSmsLockedMeEndpoint(isPayer: Boolean): Boolean {
        if (!isSmsSource) return false
        val tx = currentTransaction ?: return false
        // Stored as ME -> ME -- pended while it was a transfer -- nothing says which end the bank's
        // message was about, so neither is pinned.
        if (tx.payerActorType == ActorType.ME && tx.payeeActorType == ActorType.ME) return false
        return if (isPayer) tx.payerActorType == ActorType.ME else tx.payeeActorType == ActorType.ME
    }

    private fun smsAliasFallback(isPayer: Boolean): String {
        return if (isPayer) smsPayerAliasFallback else smsPayeeAliasFallback
    }

    private fun updateActorTileStyles() {
        styleActorTiles(true, payerActorType, isSmsLockedMeEndpoint(true))
        styleActorTiles(false, payeeActorType, isSmsLockedMeEndpoint(false))
        updateLedgerOptionsVisibility()
    }

    private fun styleActorTiles(isPayer: Boolean, selectedType: String, lockedToMe: Boolean) {
        actorTilesFor(isPayer).forEach { (type, view) ->
            view.isSelected = type == selectedType
            // An SMS-derived transaction pins the "Me" endpoint; the other two tiles are genuinely
            // disabled, which is a state the pill selector already draws.
            view.isEnabled = !lockedToMe || type == ActorType.ME
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

    /** The first row is the one that must balance, so it is tinted rather than left plain. */
    private fun stylePrimaryShareRow(card: View) {
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(themeColor(ThemeAttr.primaryContainer))
            setStroke(dp(1), themeColor(ThemeAttr.primary))
        }
    }

    /**
     * Not a real Chip: the pill hosts an inline EditText for the split amount, which a Chip cannot
     * contain. The look still comes from the theme.
     */
    private fun styleCategoryPill(pillRoot: View, checked: Boolean) {
        pillRoot.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            if (checked) {
                setColor(themeColor(ThemeAttr.primaryContainer))
                setStroke(dp(1), themeColor(ThemeAttr.primary))
            } else {
                setColor(themeColor(ThemeAttr.surfaceVariant))
            }
        }
    }

    /**
     * How an endpoint reads in its field: its name, or blank when nobody was ever named. Never the
     * "Friend" / "Merchant" a list shows for one, nor "Me" -- switched to a friend or merchant, the
     * field is pre-filled with this, and saving would take any of those for a name.
     */
    private suspend fun resolveInitialEndpointLabel(db: AppDatabase, isPayer: Boolean, tx: Transaction): String {
        val actor = if (isPayer) tx.payerActorRef() else tx.payeeActorRef()
        if (actor.actorType == ActorType.ME) return ""
        val unnamed = actor.rawLabel.isNullOrBlank() && (
            (actor.actorType == ActorType.FRIEND && actor.friendId == null) ||
                (actor.actorType == ActorType.MERCHANT && actor.merchantId == null)
            )
        return if (unnamed) "" else resolveActorDisplayName(db, actor)
    }

    private fun buildMeShareRow(): ShareRow = ShareRow(
        key = "ME",
        participantType = ActorType.ME,
        friendId = null,
        label = meLabel,
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
        val existing = rows[0]
        // Relabelled, not replaced: the amount and which IOUs are kept both belong to the row.
        fun ShareRow.keepingExisting() = copy(
            amountPaise = existing.amountPaise,
            keepPayeeLeg = existing.keepPayeeLeg,
            keepPayerLeg = existing.keepPayerLeg,
            payeeVia = existing.payeeVia,
            payerVia = existing.payerVia
        )
        when (actorTypeFor(isPayer)) {
            ActorType.ME -> rows[0] = buildMeShareRow().keepingExisting()
            ActorType.MERCHANT -> rows[0] = buildMerchantShareRow(isPayer).keepingExisting()
            ActorType.FRIEND -> {
                val friendId = if (isPayer) payerFriendId else payeeFriendId
                val friend = allFriends.firstOrNull { it.id == friendId }
                rows[0] = if (friend != null) {
                    buildFriendShareRow(friend).keepingExisting()
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
                if (friend != null) return buildFriendShareRow(friend)
                // Nobody picked yet: start from the name this endpoint loaded with (its alias, or the
                // raw name the bank gave), which saving resolves to a friend or creates one from.
                val label = smsAliasFallback(isPayer)
                ShareRow("F:0", ActorType.FRIEND, null, label, if (label.isBlank()) "?" else computeFriendInitials(label), 0L)
            }
            else -> buildMeShareRow()
        }
    }

    private fun buildShareRowFromShare(share: TransactionShare): ShareRow {
        val row = if (share.participantType == ActorType.ME) {
            ShareRow("ME", ActorType.ME, null, meLabel, "ME", share.amountPaise)
        } else {
            val friend = allFriends.firstOrNull { it.id == share.friendId }
            // rawLabel is how a shared parcel names someone this database cannot identify. Without
            // it the row reads "Friend", and the user has no way to tell three of them apart.
            ShareRow(
                key = "F:${share.friendId}",
                participantType = ActorType.FRIEND,
                friendId = share.friendId,
                label = friend?.name ?: share.rawLabel ?: "Friend",
                initials = friend?.avatarInitials ?: share.rawLabel?.let { initialsOf(it, "F") } ?: "F",
                amountPaise = share.amountPaise
            )
        }
        return row.copy(
            keepPayeeLeg = share.keepPayeeLeg,
            keepPayerLeg = share.keepPayerLeg,
            payeeVia = knownVia(IouLegs.viaOf(share, owesPayer = false)),
            payerVia = knownVia(IouLegs.viaOf(share, owesPayer = true))
        )
    }

    /**
     * A go-between this database still holds, or null. A friend deleted since leaves the debt
     * running straight: nothing could be posted against someone who is not here.
     */
    private fun knownVia(via: IouParty?): IouParty? =
        via?.takeIf { it !is IouParty.Friend || allFriends.any { friend -> friend.id == it.friendId } }

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
            val btnRemove = rowView.findViewById<ImageButton>(R.id.btnRemoveShare)

            if (index == 0) {
                stylePrimaryShareRow(shareRowCard)
                btnRemove.visibility = View.GONE
            }

            tvAvatar.text = row.initials
            Avatars.paint(tvAvatar, row.participantType)

            val isPrimary = index == 0
            val isLockedPrimaryMe = isPrimary && isSmsLockedMeEndpoint(isPayer) && actorTypeFor(isPayer) == ActorType.ME

            if (isPrimary && actorTypeFor(isPayer) == ActorType.ME) {
                setupPrimaryAccountField(etName, isPayer)
            } else {
                val suggestions = if (isPrimary) {
                    primarySuggestions(isPayer)
                } else {
                    val excludedKeys = rows.map { it.key }.toSet()
                    val hasMeAlready = rows.any { it.participantType == ActorType.ME }
                    val showMeOption = !hasMeAlready || row.participantType == ActorType.ME
                    val availableFriends = allFriends.filter { "F:${it.id}" !in excludedKeys || it.id == row.friendId }
                    buildList {
                        if (showMeOption) add(meLabel)
                        addAll(availableFriends.map { it.name })
                    }
                }

                etName.setAdapter(NameSuggestionAdapter(this, suggestions, meLabel))
                etName.setText(row.label, false)
                // A tap selects the name, so typing replaces it rather than adding to it.
                etName.setSelectAllOnFocus(true)
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
                })
                // Clearing the field to retype it leaves it blank for as long as it is being edited.
                // Left blank, it gets back what it had: the name it loaded with on an endpoint (the
                // alias, or the raw name the bank gave), else whatever it showed when the edit began.
                var labelOnFocus = row.label
                etName.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        labelOnFocus = etName.text.toString()
                        return@setOnFocusChangeListener
                    }
                    if (etName.text.isNullOrBlank()) {
                        val refill = if (isPrimary) smsAliasFallback(isPayer).ifBlank { labelOnFocus } else labelOnFocus
                        if (refill.isNotBlank()) etName.setText(refill, false)
                    }
                    // Rebuilt rows lose focus after the list has changed under them.
                    if (!isPrimary && rows.getOrNull(index)?.key?.isBlank() == true) {
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
                etShareAmount.setText(AmountFormat.forInput(row.amountPaise))
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
            is TransactionEntryAction.AmountChanged -> {
                updateLiveCalc()
                updateTrackedCategoryAmount()
            }
            is TransactionEntryAction.AccountSelected -> {
                setAccountId(action.side == EntrySide.PAYER, action.accountId.takeIf { it.isNotBlank() })
                if (isTransferMode()) updateLiveCalc()
            }

            is TransactionEntryAction.ActorTypeSelected -> {
                onActorTypeSelected(action.side == EntrySide.PAYER, action.actorType)
            }

            is TransactionEntryAction.DateChanged -> {
                selectedDateEpoch = action.dateEpoch
                renderAgreementNotice()
            }

            is TransactionEntryAction.ToggleMerchant -> {
                val isPayer = action.side == EntrySide.PAYER
                onActorTypeSelected(isPayer, if (action.isMerchant) ActorType.MERCHANT else ActorType.ME)
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
                    val row = rows[action.rowIndex]
                    row.amountPaise = ((action.rawAmount.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    updateSectionBalance(isPayer)
                    updateLiveCalc()
                    // Anyone's share moves what ME is left with once the IOUs are counted, not only ME's.
                    updateTrackedCategoryAmount()
                }
            }

            is TransactionEntryAction.DescriptionChanged -> {
                // No-op here; UI element (`etDescription`) is the source of truth and
                // ViewModel already receives changes. Keep branch to exhaust the when expression.
            }

            is TransactionEntryAction.IouRecoverySelected -> {
                iouRecoveryOverride = action.recovery
                updateIouRecoveryUi()
                updateCategoryVisibility()
                updateTrackedCategoryAmount()
            }
            is TransactionEntryAction.IouLineToggled -> toggleIouLine(action.debtor, action.creditor, action.passedOn)
            is TransactionEntryAction.IouLineRouted ->
                routeIouLine(action.debtor, action.creditor, action.passedOn, action.throughMe)
            is TransactionEntryAction.CategoryToggled -> updateCategoryVisibility()
            is TransactionEntryAction.CategoryAmountChanged -> updateCategoryVisibility()
            TransactionEntryAction.SaveClicked -> {
                if (isOriginalView) confirmRevert() else viewModel.launchTask { handleDone() }
            }
            TransactionEntryAction.PendClicked -> {
                if (!isOriginalView) viewModel.launchTask { handlePend() }
            }

            TransactionEntryAction.CloseClicked -> finish()
            is TransactionEntryAction.ScreenLoaded -> Unit
        }
    }

    private fun applyShareNameTyped(side: EntrySide, rowIndex: Int, rawText: String) {
        val isPayer = side == EntrySide.PAYER
        val rows = rowsFor(isPayer)
        if (rowIndex !in rows.indices) return
        val trimmed = rawText.trim()
        rows[rowIndex] = rows[rowIndex].copy(label = trimmed)

        // The primary row is where a merchant is named. Resolving it here rather than only at
        // save time is what lets the refund picker populate on the first pass: until this ran,
        // payerMerchantId stayed null through the whole entry, so the picker saw a merchant with
        // no history and you had to save and reopen before it listed anything.
        //
        // Cleared on a non-match on purpose: leaving the previous id behind would let a renamed
        // merchant save against the merchant that was there before.
        if (rowIndex == 0 && actorTypeFor(isPayer) == ActorType.MERCHANT) {
            val merchant = allMerchants.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            if (isPayer) payerMerchantId = merchant?.id else payeeMerchantId = merchant?.id
            refreshRefundCandidatesIfNeeded()
            if (isCategorySectionVisible) maybePrefillCategory()
        }

        // The same for a friend at either end, for the same reason: the endpoint's id is what saving
        // trusts first (see resolveFriendId), so a renamed primary friend kept the friend it was
        // loaded with -- on a friend's transaction, always the sender. A name that matches nobody
        // clears it, and saving finds or adds that person and points the row at them.
        if (rowIndex == 0 && actorTypeFor(isPayer) == ActorType.FRIEND) {
            val friend = allFriends.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            if (isPayer) payerFriendId = friend?.id else payeeFriendId = friend?.id
            if (friend != null) {
                updateShareRowParticipant(rows, 0, ActorType.FRIEND, friend.id, friend.name, friend.avatarInitials)
            } else if (rows[0].friendId != null) {
                rows[0] = rows[0].copy(key = UNRESOLVED_PRIMARY_KEY, friendId = null)
            }
            return
        }

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
                amountPaise = it.amountPaise,
                keepPayeeLeg = it.keepPayeeLeg,
                keepPayerLeg = it.keepPayerLeg,
                payeeVia = it.payeeVia,
                payerVia = it.payerVia
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
                amountPaise = it.amountPaise,
                keepPayeeLeg = it.keepPayeeLeg,
                keepPayerLeg = it.keepPayerLeg,
                payeeVia = it.payeeVia,
                payerVia = it.payerVia
            )
        })
    }

    private fun getCurrentAmountPaise(): Long = ((etAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()

    private fun suggestShareAmount(rows: MutableList<ShareRow>): Long {
        val total = getCurrentAmountPaise()
        return shareCalculator.suggestShareAmount(total, rows.map { it.amountPaise })
    }

    /**
     * What ME is left up or down by, measured the way saving records it: the money through ME's own
     * account against the IOU lines kept. Leaving a line out is what makes part of a split ME's own.
     */
    private fun categoryTargeting(): CategoryTargeting {
        if (isTransferMode()) return CategoryTargeting(0L, CategoryKind.EXPENSE)
        val payer = draftActorRef(true)
        val payee = draftActorRef(false)
        val shares = buildSharesForPersistence(0L)
        val amountPaise = getCurrentAmountPaise()
        val kept = IouLegs.legs(
            payer, payee, shares, amountPaise, LedgerEffect.DEBT, chosenIouRecovery(payer, payee, shares)
        )
        return shareCalculator.categoryTargeting(
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            isLinkedRefund = refundsTransactionId != null,
            meNetPaise = IouLegs.meNet(payer, payee, amountPaise, kept)
        )
    }

    private fun myShareForCategories(): Long = categoryTargeting().sharePaise

    /** Fills [entry]'s split with what's left of ME's share after every other checked category. */
    private fun applyRemainderAmount(entry: CategoryEntry) {
        val othersSum = categoryEntries
            .filter { it.isChecked && it.category.id != entry.category.id }
            .sumOf { it.myAmountPaise }
        entry.myAmountPaise = (myShareForCategories() - othersSum).coerceAtLeast(0L)
    }

    /** Keeps the tracked category (the one last checked) matching ME's share as it changes. */
    private fun updateTrackedCategoryAmount() {
        val trackedId = trackedCategoryId ?: return
        val entry = categoryEntries.firstOrNull { it.category.id == trackedId && it.isChecked } ?: return
        applyRemainderAmount(entry)
        renderCategories()
    }

    private fun updateSectionBalance(isPayer: Boolean) {
        val tv = if (isPayer) tvPayerBalance else tvPayeeBalance
        if (isTransferMode()) {
            tv.text = if (isPayer) "From account" else "To account"
            return
        }
        val rows = rowsFor(isPayer)
        val total = getCurrentAmountPaise()
        val summed = rows.sumOf { it.amountPaise }
        val result = shareCalculator.computeSectionBalance(total, summed)
        tv.text = when (result.state) {
            SectionBalanceState.BALANCED -> "✓ Balanced"
            SectionBalanceState.OVER -> "Over by ${AmountFormat.rupeesExact(result.deltaPaise)}"
            SectionBalanceState.REMAINING -> "Remaining: ${AmountFormat.rupeesExact(result.deltaPaise)}"
        }
    }

    private fun updateLiveCalc() {
        updateSectionBalance(true)
        updateSectionBalance(false)
        updateIouRecoveryUi()
        renderAgreementNotice()

        if (isTransferMode()) {
            tvBalance.text = transferSummaryText()
            updateCategoryVisibility()
            return
        }

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
            OverallAllocationState.OVER_ALLOCATED -> "Over-allocated: ${AmountFormat.rupeesExact(allocation.deltaPaise)}"
            OverallAllocationState.PAYER_UNALLOCATED -> "Payer unallocated: ${AmountFormat.rupeesExact(allocation.deltaPaise)}"
            OverallAllocationState.PAYEE_UNALLOCATED -> "Payee unallocated: ${AmountFormat.rupeesExact(allocation.deltaPaise)}"
            OverallAllocationState.UNALLOCATED -> "Unallocated"
            OverallAllocationState.BALANCED -> ""
        }
        updateCategoryVisibility()
    }

    /** Previews the exact figure the dashboard will count, via the same pure function. */
    private fun transferSummaryText(): String {
        val from = payerShareRows.firstOrNull()?.amountPaise ?: 0L
        val to = payeeShareRows.firstOrNull()?.amountPaise ?: 0L
        if (from <= 0L && to <= 0L) return "Enter a transfer amount"
        // A single populated leg is a credit or charge against the account, not a movement.
        if (from <= 0L) return "Credit ${AmountFormat.rupeesExact(to)}"
        if (to <= 0L) return "Charge ${AmountFormat.rupeesExact(from)}"

        val delta = BalanceDeltaCalculator.expenseDelta(
            TransferDeltaInput(payerAccountId, payeeAccountId, from, to)
        )
        return when {
            delta > 0L -> "Transfer ${AmountFormat.rupeesExact(from)} - fee ${AmountFormat.rupeesExact(delta)}"
            delta < 0L -> "Transfer ${AmountFormat.rupeesExact(from)} + gain ${AmountFormat.rupeesExact(-delta)}"
            else -> "Transfer ${AmountFormat.rupeesExact(from)}"
        }
    }

    /** Only a merchant paying ME can be a refund; anything else is a purchase or a transfer. */
    private fun isRefundApplicable(): Boolean =
        payerActorType == ActorType.MERCHANT && payeeActorType == ActorType.ME

    private fun updateLedgerOptionsVisibility() {
        val refundApplicable = isRefundApplicable()
        refundSection.visibility = if (refundApplicable) View.VISIBLE else View.GONE
        if (!refundApplicable && refundsTransactionId != null) {
            clearRefundTarget()
            rebuildCategoryEntries()
        }
        refreshRefundCandidatesIfNeeded()
        renderRefundTarget()
        // Also settles whether the card shows at all, since the "who owes whom" section lives in it too.
        updateIouRecoveryUi()
    }

    private fun renderRefundTarget() {
        val target = refundsTransactionId
        tvRefundTarget.text = if (target == null) {
            NOT_A_REFUND_LABEL
        } else {
            refundCandidates.firstOrNull { it.first.id == target }?.second ?: "Linked purchase"
        }
    }

    private fun clearRefundTarget() {
        refundsTransactionId = null
        refundAllowedCategoryIds = null
    }

    private fun showRefundPicker() {
        if (!isRefundApplicable()) return
        val eligible = refundCandidates.filter { it.first.dateEpoch <= selectedDateEpoch }
        val labels = buildList {
            add(NOT_A_REFUND_LABEL)
            addAll(eligible.map { it.second })
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Refund for")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    clearRefundTarget()
                    rebuildCategoryEntries()
                    renderRefundTarget()
                    updateCategoryVisibility()
                } else {
                    selectRefundTarget(eligible[which - 1].first.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectRefundTarget(originalId: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.Companion.getInstance(applicationContext)
            val splits = withContext(Dispatchers.IO) {
                db.categorySplitDao().getForTransaction(originalId)
            }
            if (splits.isEmpty()) {
                toast("That purchase has no categories to refund against")
                return@launch
            }
            refundsTransactionId = originalId
            refundAllowedCategoryIds = splits.map { it.categoryId }.toSet()
            rebuildCategoryEntries()
            // One category means there is nothing to choose: give the whole refund back to it.
            if (categoryEntries.size == 1) {
                categoryEntries[0].isChecked = true
                trackedCategoryId = categoryEntries[0].category.id
                applyRemainderAmount(categoryEntries[0])
            }
            renderRefundTarget()
            updateCategoryVisibility()
        }
    }

    /** No-op unless the payer merchant actually changed since the list was last built. */
    private fun refreshRefundCandidatesIfNeeded() {
        if (!isRefundApplicable()) return
        if (payerMerchantId == loadedRefundMerchantId) return
        lifecycleScope.launch {
            loadRefundCandidates(AppDatabase.Companion.getInstance(applicationContext))
        }
    }

    private suspend fun loadRefundCandidates(db: AppDatabase) {
        val merchantId = payerMerchantId
        val previousMerchantId = loadedRefundMerchantId

        // A merchant typed but not yet saved has no purchase history to refund against.
        val rows = if (merchantId == null) emptyList() else withContext(Dispatchers.IO) {
            db.transactionDao().getRefundCandidates(
                merchantId = merchantId,
                refundDateEpoch = Long.MAX_VALUE,
                excludeTransactionId = currentTransaction?.id ?: -1L,
                limit = REFUND_CANDIDATE_LIMIT
            )
        }

        // The merchant changed again while this query was in flight; that newer load owns the
        // list now, and committing these rows would leave the wrong merchant's purchases on
        // screen under a memo that says otherwise.
        if (payerMerchantId != merchantId) return
        loadedRefundMerchantId = merchantId

        // A target picked for a different merchant must not survive the switch: the candidate
        // list no longer contains it, so it would render as an unnamed "linked purchase" while
        // still saving a refund against another merchant's transaction. Only when a merchant was
        // genuinely loaded before -- the first load runs before an edit populates its merchant,
        // and must not clear the link that transaction already carries.
        if (previousMerchantId != null && previousMerchantId != merchantId &&
            refundsTransactionId != null
        ) {
            clearRefundTarget()
            rebuildCategoryEntries()
            updateCategoryVisibility()
        }

        // An edited refund whose purchase is older than the candidate limit is still valid; keep
        // it in the list so it renders by name and stays selectable.
        val target = refundsTransactionId
        val resolved = if (target != null && rows.none { it.id == target }) {
            val original = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(target) }
            listOfNotNull(original) + rows
        } else {
            rows
        }

        refundCandidates = resolved.map { tx ->
            val label = fmtDateTime(tx.dateEpoch) + " - " + tx.resolvePrimaryDisplay(db) +
                " - " + AmountFormat.rupeesExact(tx.amountPaise)
            tx to label
        }
        renderRefundTarget()
    }

    /**
     * The "who owes whom" section: the pay-back pills when the two sides would post different lines,
     * then one pill per line, tapped to keep it or leave it out. Built from the same [IouLegs] calls
     * saving makes, over the rows on screen.
     */
    private fun updateIouRecoveryUi() {
        val payer = draftActorRef(true)
        val payee = draftActorRef(false)
        val shares = buildSharesForPersistence(0L)
        val lines = if (isTransferMode()) emptyList() else currentIouLines(payer, payee, shares)
        iouRecoverySection.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        if (lines.isNotEmpty()) {
            val recovery = chosenIouRecovery(payer, payee, shares)
            iouRecoveryPills.visibility =
                if (IouLegs.choiceMatters(payer, payee, shares, LedgerEffect.DEBT)) View.VISIBLE else View.GONE
            tvRecoverFromPayers.isSelected = recovery == IouRecovery.FROM_SECONDARY_PAYERS
            tvRecoverFromPayees.isSelected = recovery == IouRecovery.FROM_SECONDARY_PAYEES
            renderIouLines(lines)
        }

        // The refund section sits above this one: only space the two apart when both show.
        val params = iouRecoverySection.layoutParams as ViewGroup.MarginLayoutParams
        val topMargin = if (refundSection.visibility == View.VISIBLE) {
            resources.getDimensionPixelSize(R.dimen.space_m)
        } else {
            0
        }
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            iouRecoverySection.layoutParams = params
        }
        val anySectionShown = listOf(refundSection, iouRecoverySection).any { it.visibility == View.VISIBLE }
        ledgerOptionsCard.visibility = if (anySectionShown) View.VISIBLE else View.GONE
    }

    private fun currentIouLines(payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>): List<IouLine> =
        IouLegs.lines(payer, payee, shares, getCurrentAmountPaise(), chosenIouRecovery(payer, payee, shares))

    private fun renderIouLines(lines: List<IouLine>) {
        val shown = lines.map { listOf(lineText(it), it.kept, routeActionFor(it)?.first) }
        if (shown == renderedIouLines) return
        renderedIouLines = shown

        iouLinesContainer.removeAllViews()
        lines.forEach { line ->
            val lineRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val pill = LayoutInflater.from(this).inflate(R.layout.item_iou_line, lineRow, false) as TextView
            pill.text = lineText(line)
            pill.isSelected = line.kept
            // Struck through as well as unselected, so a line left out reads as not owed at a glance.
            pill.paintFlags = if (line.kept) {
                pill.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                pill.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            pill.setOnClickListener {
                viewModel.onAction(TransactionEntryAction.IouLineToggled(line.debtor, line.creditor, line.routed.isNotEmpty()))
            }
            lineRow.addView(pill)

            // Beside the pill rather than in a menu on it: tapping the pill already means keep or leave out.
            routeActionFor(line)?.let { (label, throughMe) ->
                val action = TextView(this).apply {
                    text = label
                    textSize = 11f
                    setTextColor(themeColor(ThemeAttr.primary))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        viewModel.onAction(
                            TransactionEntryAction.IouLineRouted(line.debtor, line.creditor, line.routed.isNotEmpty(), throughMe)
                        )
                    }
                }
                lineRow.addView(
                    action,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = resources.getDimensionPixelSize(R.dimen.space_xs)
                    }
                )
            }
            iouLinesContainer.addView(lineRow)
        }
    }

    /**
     * What can be done to where [line] runs, as a label and whether it routes through ME: a debt
     * between two other people can go through ME instead, and one that runs through someone can go
     * straight again. Null for a line straight between ME and someone, where there is nothing between.
     */
    private fun routeActionFor(line: IouLine): Pair<String, Boolean>? = when {
        line.routable -> "Through me" to true
        line.routed.isNotEmpty() -> "Directly" to false
        else -> null
    }

    /**
     * Flips every share-row leg folded into the line between [debtor] and [creditor] -- the one passed on
     * through a go-between, or the straight one -- found again from the rows as they stand now: the
     * pill that was tapped may have been drawn before a row changed.
     */
    private fun toggleIouLine(debtor: IouParty, creditor: IouParty, passedOn: Boolean) {
        val draft = draftShareRows(0L)
        val line = findIouLine(draft, debtor, creditor, passedOn) ?: return
        line.sources.forEach { source ->
            val row = draft[source.shareIndex].first
            if (source.owesPayer) row.keepPayerLeg = !line.kept else row.keepPayeeLeg = !line.kept
        }
        updateIouRecoveryUi()
        updateCategoryVisibility()
        updateTrackedCategoryAmount()
    }

    /**
     * Runs the line between [debtor] and [creditor] through ME, or straight again. Either way everyone
     * ends up owing or owed what they were overall -- only who they owe it to changes -- so what ME
     * categorises does not move.
     */
    private fun routeIouLine(debtor: IouParty, creditor: IouParty, passedOn: Boolean, throughMe: Boolean) {
        val draft = draftShareRows(0L)
        val line = findIouLine(draft, debtor, creditor, passedOn) ?: return
        val sources: List<IouLegSource> = if (throughMe) line.sources else line.routed.map { it.source }
        sources.forEach { source ->
            val row = draft[source.shareIndex].first
            val via = if (throughMe) IouParty.Me else null
            if (source.owesPayer) row.payerVia = via else row.payeeVia = via
        }
        updateIouRecoveryUi()
        updateCategoryVisibility()
        updateTrackedCategoryAmount()
    }

    private fun findIouLine(
        draft: List<Pair<ShareRow, TransactionShare>>,
        debtor: IouParty,
        creditor: IouParty,
        passedOn: Boolean
    ): IouLine? {
        val payer = draftActorRef(true)
        val payee = draftActorRef(false)
        return currentIouLines(payer, payee, draft.map { it.second })
            .firstOrNull { setOf(it.debtor, it.creditor) == setOf(debtor, creditor) && it.routed.isNotEmpty() == passedOn }
    }

    /** What saving now would store: the user's pick, then the split's shape. */
    private fun chosenIouRecovery(payer: ActorRef, payee: ActorRef, shares: List<TransactionShare>): IouRecovery =
        IouLegs.effectiveRecovery(
            payer,
            payee,
            iouRecoveryOverride ?: IouLegs.shapeDefault(payer, payee, shares)
        )

    /**
     * An endpoint as it stands on screen, before saving resolves or creates anyone. A friend picked into
     * the primary row may not have reached [payerFriendId] or [payeeFriendId] yet, so the row's id counts.
     */
    private fun draftActorRef(isPayer: Boolean): ActorRef {
        val label = primaryLabel(isPayer).ifBlank { null }
        return when (actorTypeFor(isPayer)) {
            ActorType.ME -> ActorRef(ActorType.ME, rawLabel = "Me")
            ActorType.FRIEND -> ActorRef(
                ActorType.FRIEND,
                friendId = (if (isPayer) payerFriendId else payeeFriendId) ?: rowsFor(isPayer).firstOrNull()?.friendId,
                rawLabel = label
            )
            ActorType.MERCHANT -> ActorRef(
                ActorType.MERCHANT,
                merchantId = if (isPayer) payerMerchantId else payeeMerchantId,
                rawLabel = label
            )
            else -> ActorRef(actorTypeFor(isPayer), rawLabel = label)
        }
    }

    private fun lineText(line: IouLine): String {
        val amount = AmountFormat.rupees(line.amountPaise)
        val base = when {
            line.debtor == IouParty.Me -> "You owe ${partyName(line.creditor)} $amount"
            line.creditor == IouParty.Me -> "${partyName(line.debtor)} owes you $amount"
            else -> "${partyName(line.debtor)} owes ${partyName(line.creditor)} $amount"
        }
        if (line.routed.isEmpty()) return base
        // Whose debt is being passed on: "(for Asha)", or "(₹500 for Bob)" when only part of it is.
        val byFarEnd = line.routed.groupBy { it.farEnd }.mapValues { (_, parts) -> parts.sumOf { it.amountPaise } }
        val detail = byFarEnd.entries.joinToString(", ") { (farEnd, paise) ->
            if (byFarEnd.size == 1 && paise == line.amountPaise) "for ${partyName(farEnd)}"
            else "${AmountFormat.rupees(paise)} for ${partyName(farEnd)}"
        }
        return "$base ($detail)"
    }

    private fun partyName(party: IouParty): String = when (party) {
        IouParty.Me -> "You"
        is IouParty.Friend -> allFriends.firstOrNull { it.id == party.friendId }?.name ?: "A friend"
        is IouParty.Person -> party.label?.takeIf { it.isNotBlank() } ?: "Someone"
        is IouParty.Counterparty -> party.label?.takeIf { it.isNotBlank() } ?: "Someone"
    }

    /** Swapping direction invalidates every checked pill, so clear them rather than carry them over. */
    private fun applyCategoryKind(kind: CategoryKind) {
        if (kind == categoryKind) return
        categoryKind = kind
        rebuildCategoryEntries()
    }

    /**
     * A linked refund narrows the list to the original's categories; otherwise it is every
     * category of the direction currently being attributed.
     */
    private fun rebuildCategoryEntries() {
        val allowed = refundAllowedCategoryIds
        categoryEntries.clear()
        categoryEntries.addAll(
            allCategories
                .filter { if (allowed != null) it.id in allowed else it.kind == categoryKind }
                .map { CategoryEntry(category = it, isChecked = false, myAmountPaise = 0L) }
        )
        trackedCategoryId = null
        // Render here rather than in updateCategoryVisibility: this is the only place the list
        // actually changes, and updateCategoryVisibility is reached on every keystroke via
        // CategoryAmountChanged -- re-rendering there would recreate the pill EditTexts mid-edit
        // and steal focus. Guarded because the first rebuild runs before the views are laid out.
        if (::categoryContainer.isInitialized) renderCategories()
    }

    private fun updateCategoryVisibility() {
        val visibilityDecision = categorySplitManager.visibilityDecision(categoryTargeting())
        applyCategoryKind(visibilityDecision.kind)

        val wasVisible = isCategorySectionVisible
        isCategorySectionVisible = visibilityDecision.showCategories
        categoryContainer.visibility = if (visibilityDecision.showCategories) View.VISIBLE else View.GONE
        // A pended form keeps the pills it had even while there is nothing to categorise: hidden, and
        // never saved that way, but there again once there is.
        if (visibilityDecision.shouldClearSelections && !restoredFromDraft) {
            categoryEntries.forEach {
                it.isChecked = false
                it.myAmountPaise = 0L
            }
            trackedCategoryId = null
            renderCategories()
            return
        }

        if (!wasVisible && visibilityDecision.showCategories) {
            formScroll.post {
                formScroll.smoothScrollTo(0, categoryScrollView.top)
            }
        }
        if (visibilityDecision.showCategories) maybePrefillCategory()
    }

    /**
     * Checks the category this merchant has only ever been filed under, in this direction, so a
     * routine purchase needs no tap on the pills at all. It then tracks ME's share like any pill
     * checked last.
     *
     * Only until the user does anything to the categories themselves. Before that, a different
     * merchant swaps the pick this made for its own suggestion, or clears it when it has none.
     */
    private fun maybePrefillCategory() {
        if (userTouchedCategories || isOriginalView || refundsTransactionId != null || isTransferMode()) return
        val suggestion = suggestedCategoryId()
        val checked = categoryEntries.filter { it.isChecked }
        val onlyOurPick = checked.size == 1 && checked[0].category.id == prefilledCategoryId
        if (checked.isNotEmpty() && !onlyOurPick) return
        if (onlyOurPick && checked[0].category.id == suggestion) return

        if (onlyOurPick) {
            checked[0].isChecked = false
            checked[0].myAmountPaise = 0L
            if (trackedCategoryId == checked[0].category.id) trackedCategoryId = null
            prefilledCategoryId = null
        }
        val entry = suggestion?.let { id -> categoryEntries.firstOrNull { it.category.id == id } }
        if (entry != null) {
            entry.isChecked = true
            trackedCategoryId = entry.category.id
            applyRemainderAmount(entry)
            prefilledCategoryId = entry.category.id
        }
        if (entry != null || onlyOurPick) renderCategories()
    }

    private fun suggestedCategoryId(): Long? {
        val side = MerchantCategoryHistory.merchantSide(payerActorType, payeeActorType, payerMerchantId, payeeMerchantId)
            ?: return null
        if (side.second != categoryKind) return null
        return merchantCategories[side]
    }

    private suspend fun handleDone() {
        // Must precede the amount check: in transfer mode etAmount is disabled and empty.
        if (isTransferMode()) return handleDoneTransfer()

        if (chapterLocked) {
            val name = chapterOptions.firstOrNull { it.chapter.id == selectedChapterId }?.chapter?.name
            return toast("In ${name ?: "a closed chapter"} (closed). Reopen it to edit.")
        }

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

        val myShare = myShareForCategories()
        val categoryMandatory = categorySplitManager.visibilityDecision(categoryTargeting()).showCategories
        val categoryValidation = transactionValidator.validateCategories(
            mandatory = categoryMandatory,
            myShareForCategoriesPaise = myShare,
            checkedAmountsPaise = categoryEntries.filter { it.isChecked }.map { it.myAmountPaise }
        )
        if (!categoryValidation.isValid) {
            return toast(categoryValidation.message ?: "Fix category split")
        }

        val refundValidation = validateRefundCoverage()
        if (!refundValidation.isValid) {
            return toast(refundValidation.message ?: "Fix refund allocation")
        }

        val db = AppDatabase.Companion.getInstance(applicationContext)

        if (!resolveChapterBeforeSaving(db, amountPaise)) return
        if (!decideOutsiders()) return

        val membersBefore = chapterMemberIds(db, selectedChapterId)
        // A row first counted now: typed in, or reviewed from pending. Nothing about it was known
        // when any agreement it falls under was made.
        val firstCounted = currentTransaction?.isPending ?: true
        val chaptersTouched = setOfNotNull(currentTransaction?.chapterId, selectedChapterId)
        val savedId = try {
            withContext(Dispatchers.IO) { persistTransaction(db, amountPaise, payerLabel, payeeLabel, myShare) }
        } catch (e: ChapterException) {
            return toast(e.message ?: "Could not save it in that chapter")
        }
        publishIfShared(db, chaptersTouched)
        reportAddedMembers(db, membersBefore)
        currentTransaction?.let { TransactionNotificationHelper.cancel(applicationContext, it.id.toInt()) }
        if (firstCounted && offerToAddLateRow(db, savedId)) return
        finish()
    }

    /**
     * Saves a ME -> ME entry as an [AccountTransfer] and nothing else: this path never touches
     * [transactionPersistenceService], so no `Transaction` row is written.
     */
    private suspend fun handleDoneTransfer() {
        if (chapterLocked) return toast("This is in a closed chapter. Reopen it to make changes.")
        val fromId = payerAccountId
        val toId = payeeAccountId
        val amountFrom = payerShareRows.firstOrNull()?.amountPaise ?: 0L
        val amountTo = payeeShareRows.firstOrNull()?.amountPaise ?: 0L

        val validation = accountTransferValidator.validate(
            TransferValidationInput(fromId, toId, amountFrom, amountTo)
        )
        if (!validation.isValid) return toast(validation.message ?: "Invalid transfer")

        val accounts = availableAccounts()
        val fromType = accounts.firstOrNull { it.id == fromId }?.type
            ?: return toast("Source account not found")
        val toType = accounts.firstOrNull { it.id == toId }?.type
            ?: return toast("Destination account not found")

        val type = when (
            val resolution =
                AccountTransferTypeResolver.resolve(fromType, toType, amountFrom, amountTo)
        ) {
            is TransferTypeResolution.Unsupported -> return toast(resolution.message)
            is TransferTypeResolution.Resolved -> resolution.type
        }

        val existing = currentTransfer
        // Non-null only when a pending transaction is being reclassified; canSwitchTransferMode
        // rejects the flip for anything already settled.
        val convertedFrom = currentTransaction
        val entity = AccountTransfer(
            id = existing?.id ?: UUID.randomUUID().toString(),
            fromAccountId = fromId,
            toAccountId = toId,
            amountFromPaise = amountFrom,
            amountToPaise = amountTo,
            type = type,
            dateEpoch = selectedDateEpoch,
            // Preserved so editing an imported transfer, or converting an imported transaction,
            // keeps its dedupe key and provenance.
            source = existing?.source ?: convertedFrom?.entrySource() ?: EntrySource.MANUAL,
            statementRefNo = existing?.statementRefNo ?: convertedFrom?.statementRefNo,
            notes = etDescription.text.toString().trim().ifEmpty { null },
            // The update replaces the whole row, so an edit would otherwise wipe it and let the
            // SMS backlog scan bring this payment back as a transaction.
            upiRefId = existing?.upiRefId ?: convertedFrom?.upiRefId
        )

        val db = AppDatabase.Companion.getInstance(applicationContext)
        val repository = AccountRepository(db)
        try {
            withContext(Dispatchers.IO) {
                if (convertedFrom != null) {
                    repository.convertTransactionToTransfer(convertedFrom.id, entity)
                } else {
                    repository.upsertTransfer(entity)
                }
            }
        } catch (e: AccountMutationException) {
            return toast(e.message ?: "Could not save transfer")
        }
        if (convertedFrom != null) {
            // Leaving a shared chapter changes what its members' copies show.
            publishIfShared(db, setOfNotNull(convertedFrom.chapterId))
            TransactionNotificationHelper.cancel(applicationContext, convertedFrom.id.toInt())
        }
        finish()
    }

    /**
     * Guards both directions of the refund invariant: this transaction being a refund that would
     * over-refund a category, and this transaction being a purchase edited down below what has
     * already been refunded against it.
     */
    private suspend fun validateRefundCoverage(): ValidationResult {
        val db = AppDatabase.Companion.getInstance(applicationContext)
        val names = allCategories.associate { it.id to it.name }
        val checkedAmounts = categoryEntries
            .filter { it.isChecked && it.myAmountPaise > 0L }
            .associate { it.category.id to it.myAmountPaise }

        refundsTransactionId?.let { originalId ->
            val original = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionById(originalId)
            } ?: return ValidationResult.invalid("The purchase this refunds no longer exists.")
            val merchantId = payerMerchantId
            if (merchantId == null ||
                (original.payerMerchantId != merchantId && original.payeeMerchantId != merchantId)
            ) {
                return ValidationResult.invalid(
                    "That purchase is from a different merchant. Pick the refund again."
                )
            }

            val thisId = currentTransaction?.id ?: -1L
            val (originalSplits, otherRefunds) = withContext(Dispatchers.IO) {
                db.categorySplitDao().getForTransaction(originalId) to
                    db.transactionDao().getRefundedAmountsForOriginal(originalId, thisId)
            }
            val refunded = checkedAmounts.toMutableMap()
            otherRefunds.forEach { row ->
                refunded[row.categoryId] = (refunded[row.categoryId] ?: 0L) + row.amountPaise
            }
            val result = transactionValidator.validateRefundCoverage(
                originalAmountsPaise = originalSplits.associate { it.categoryId to it.myAmountPaise },
                refundedAmountsPaise = refunded,
                categoryNames = names
            )
            if (!result.isValid) return result
        }

        val editedId = currentTransaction?.id ?: return ValidationResult.valid()
        if (refundsTransactionId != null) return ValidationResult.valid()
        val refundedAgainstThis = withContext(Dispatchers.IO) {
            db.transactionDao().getRefundedAmountsForOriginal(editedId, -1L)
        }
        if (refundedAgainstThis.isEmpty()) return ValidationResult.valid()
        return transactionValidator.validateRefundCoverage(
            originalAmountsPaise = checkedAmounts,
            refundedAmountsPaise = refundedAgainstThis.associate { it.categoryId to it.amountPaise },
            categoryNames = names
        )
    }

    // --- pend ----------------------------------------------------------------------------------

    /**
     * Keeps the form exactly as it is against a pending row, however unfinished, and closes. Nothing
     * is checked and nobody is created from a name: see [EntryDraftRepository.pend]. Only a row a
     * closed chapter holds is refused, as it is for Done (R8).
     */
    private suspend fun handlePend() {
        if (chapterLocked) {
            val name = chapterOptions.firstOrNull { it.chapter.id == selectedChapterId }?.chapter?.name
            return toast("In ${name ?: "a closed chapter"} (closed). Reopen it to edit.")
        }
        val db = AppDatabase.Companion.getInstance(applicationContext)
        val existing = currentTransaction
        // Read off the screen here, on the main thread.
        val shell = pendingShell()
        val form = currentForm()
        try {
            withContext(Dispatchers.IO) { EntryDraftRepository(db).pend(existing, currentTransfer, shell, form) }
        } catch (e: PendException) {
            return toast(e.message ?: "Could not save as pending")
        } catch (e: ChapterException) {
            return toast(e.message ?: "Could not save as pending")
        }
        // A confirmed row taken out of a shared chapter changes what its members' copies show.
        if (existing?.isPending == false) publishIfShared(db, setOfNotNull(existing.chapterId))
        existing?.let { TransactionNotificationHelper.cancel(applicationContext, it.id.toInt()) }
        toast("Saved as pending")
        finish()
    }

    /**
     * What stands in the lists for a new entry pended before it was ever saved: the figures as typed,
     * the ends as far as they are named, and no shares or splits -- those are only written by a save
     * that passed every check. A name that matches nobody yet stays a raw label; nobody is created.
     */
    private fun pendingShell(): Transaction {
        val amount = if (isTransferMode()) {
            payerShareRows.firstOrNull()?.amountPaise?.takeIf { it > 0L } ?: payeeShareRows.firstOrNull()?.amountPaise ?: 0L
        } else {
            getCurrentAmountPaise()
        }
        val payer = draftActorRef(true)
        val payee = draftActorRef(false)
        fun rawLabel(isPayer: Boolean) = primaryLabel(isPayer).takeIf { it.isNotBlank() && actorTypeFor(isPayer) != ActorType.ME }
        return Transaction(
            amountPaise = amount,
            payerActorType = payerActorType,
            payerFriendId = payer.friendId,
            payerMerchantId = payer.merchantId,
            payerRawLabel = rawLabel(true),
            payeeActorType = payeeActorType,
            payeeFriendId = payee.friendId,
            payeeMerchantId = payee.merchantId,
            payeeRawLabel = rawLabel(false),
            reason = etDescription.text.toString().trim().ifEmpty { null },
            myAccountId = accountIdForPersistence(),
            dateEpoch = selectedDateEpoch,
            source = "MANUAL",
            isPending = true
        )
    }

    /** Everything on the form, character for character, for [restoreDraft] to put back. */
    private fun currentForm(): EntryDraftSnapshot {
        val draft = EntryDraft(
            transactionId = 0L,
            savedEpoch = System.currentTimeMillis(),
            payerActorType = payerActorType,
            payerFriendId = payerFriendId,
            payerMerchantId = payerMerchantId,
            payeeActorType = payeeActorType,
            payeeFriendId = payeeFriendId,
            payeeMerchantId = payeeMerchantId,
            payerAccountId = payerAccountId,
            payeeAccountId = payeeAccountId,
            amountText = etAmount.text.toString(),
            dateEpoch = selectedDateEpoch,
            description = etDescription.text.toString().ifEmpty { null },
            chapterId = selectedChapterId,
            refundsTransactionId = refundsTransactionId,
            iouRecoveryOverride = iouRecoveryOverride,
            categoryKind = categoryKind,
            trackedCategoryId = trackedCategoryId
        )
        val rows = listOf("PAYER" to payerShareRows, "PAYEE" to payeeShareRows).flatMap { (side, sideRows) ->
            sideRows.mapIndexed { position, row ->
                EntryDraftRow(
                    transactionId = 0L,
                    side = side,
                    position = position,
                    participantType = row.participantType,
                    friendId = row.friendId,
                    label = row.label,
                    initials = row.initials,
                    amountPaise = row.amountPaise,
                    keepPayeeLeg = row.keepPayeeLeg,
                    keepPayerLeg = row.keepPayerLeg
                ).let { draftRow ->
                    val via = IouLegs.withVia(
                        IouLegs.withVia(TransactionShare(transactionId = 0L, participantType = row.participantType, amountPaise = 0L), false, row.payeeVia),
                        true,
                        row.payerVia
                    )
                    draftRow.copy(
                        payeeLegVia = via.payeeLegVia,
                        payeeLegViaFriendId = via.payeeLegViaFriendId,
                        payerLegVia = via.payerLegVia,
                        payerLegViaFriendId = via.payerLegViaFriendId
                    )
                }
            }
        }
        val categories = categoryEntries.filter { it.isChecked }.map {
            EntryDraftCategory(transactionId = 0L, categoryId = it.category.id, myAmountPaise = it.myAmountPaise)
        }
        return EntryDraftSnapshot(draft, rows, categories)
    }

    /**
     * Puts the pended form back, in place of [populateExistingTransaction]. The row itself only
     * supplies what an endpoint falls back to when its field is left blank.
     *
     * A friend, merchant, category or chapter that has gone since is let go of: its row keeps the
     * name typed and reads as not yet matched, a category is dropped, a chapter no longer offered.
     */
    private suspend fun restoreDraft(tx: Transaction, snapshot: EntryDraftSnapshot, db: AppDatabase) {
        val draft = snapshot.draft
        restoredFromDraft = true
        // The categories on it are the user's, even where none were picked.
        userTouchedCategories = true

        payerActorType = draft.payerActorType
        payeeActorType = draft.payeeActorType
        payerFriendId = draft.payerFriendId
        payerMerchantId = draft.payerMerchantId
        payeeFriendId = draft.payeeFriendId
        payeeMerchantId = draft.payeeMerchantId
        draft.payerAccountId?.let { payerAccountId = it }
        draft.payeeAccountId?.let { payeeAccountId = it }
        selectedDateEpoch = draft.dateEpoch
        tvDateValue.text = fmtDateTime(selectedDateEpoch)
        etAmount.setText(draft.amountText)
        etDescription.setText(draft.description ?: "")
        iouRecoveryOverride = draft.iouRecoveryOverride

        refundsTransactionId = draft.refundsTransactionId
        refundAllowedCategoryIds = draft.refundsTransactionId?.let { originalId ->
            withContext(Dispatchers.IO) {
                db.categorySplitDao().getForTransaction(originalId).map { it.categoryId }.toSet()
            }
        }

        smsPayerAliasFallback = resolveInitialEndpointLabel(db, true, tx)
        smsPayeeAliasFallback = resolveInitialEndpointLabel(db, false, tx)

        payerShareRows.clear()
        payeeShareRows.clear()
        snapshot.rows.sortedBy { it.position }.forEach { row ->
            val isPayer = row.side == "PAYER"
            rowsFor(isPayer).add(shareRowFromDraft(row, isPayer, isPrimary = row.position == 0))
        }

        categoryKind = draft.categoryKind
        rebuildCategoryEntries()
        snapshot.categories.forEach { checked ->
            categoryEntries.firstOrNull { it.category.id == checked.categoryId }?.let { entry ->
                entry.isChecked = true
                entry.myAmountPaise = checked.myAmountPaise
            }
        }
        trackedCategoryId = draft.trackedCategoryId?.takeIf { id ->
            categoryEntries.any { it.category.id == id && it.isChecked }
        }
    }

    /** A pended row back as it was; its key is worked out again from who it names now. */
    private fun shareRowFromDraft(row: EntryDraftRow, isPayer: Boolean, isPrimary: Boolean): ShareRow {
        val friend = row.friendId?.let { id -> allFriends.firstOrNull { it.id == id } }
        val key = when (row.participantType) {
            ActorType.ME -> "ME"
            ActorType.MERCHANT -> (if (isPayer) payerMerchantId else payeeMerchantId)?.let { "M:$it" } ?: ""
            else -> when {
                friend != null -> "F:${friend.id}"
                isPrimary -> UNRESOLVED_PRIMARY_KEY
                else -> ""
            }
        }
        return ShareRow(
            key = key,
            participantType = row.participantType,
            friendId = friend?.id,
            label = if (row.participantType == ActorType.ME) meLabel else row.label,
            initials = row.initials,
            amountPaise = row.amountPaise,
            keepPayeeLeg = row.keepPayeeLeg,
            keepPayerLeg = row.keepPayerLeg,
            payeeVia = knownVia(viaOfDraft(row.payeeLegVia, row.payeeLegViaFriendId)),
            payerVia = knownVia(viaOfDraft(row.payerLegVia, row.payerLegViaFriendId))
        )
    }

    private fun viaOfDraft(type: String?, friendId: Long?): IouParty? = when (type) {
        ActorType.ME -> IouParty.Me
        ActorType.FRIEND -> friendId?.let { IouParty.Friend(it) }
        else -> null
    }

    // --- chapter template --------------------------------------------------------------------

    private fun isNewManualEntry(): Boolean =
        currentTransaction == null && currentTransfer == null && !isOriginalView

    /**
     * Fills a side of a new entry with the selected chapter's members, so adding everyone is never
     * typing and dropping someone is one tap: see [ShareManager.withChapterMembers]. With no chapter
     * selected the side is just its primary row. Duplicates are left for Done to point out.
     */
    private fun applyChapterTemplate(isPayer: Boolean) {
        if (!isNewManualEntry() || isTransferMode()) return
        val rows = rowsFor(isPayer)
        val primary = rows.firstOrNull() ?: return
        val chapter = chapterOptions.firstOrNull { it.chapter.id == selectedChapterId }
        if (chapter == null) {
            rows.clear()
            rows.add(primary)
        } else {
            rows.replaceFromShareRowModels(
                shareManager.withChapterMembers(
                    primary = listOf(primary).toShareRowModels().single(),
                    actorType = actorTypeFor(isPayer),
                    memberIds = chapter.memberIds,
                    friends = allFriends,
                    meLabel = meLabel
                )
            )
        }
        // Copies: rows are changed in place as amounts are typed, and that is what marks them touched.
        templateSnapshot[isPayer] = rows.map { it.copy() }
    }

    /** A different chapter picked: each side still exactly as its template left it takes the new one. */
    private fun refollowChapterTemplate() {
        if (!isNewManualEntry()) return
        var changed = false
        listOf(true, false).forEach { isPayer ->
            val snapshot = templateSnapshot[isPayer] ?: return@forEach
            if (rowsFor(isPayer) != snapshot) return@forEach
            applyChapterTemplate(isPayer)
            buildShareSection(isPayer)
            changed = true
        }
        if (changed) updateLiveCalc()
    }

    // --- people outside the chapter --------------------------------------------------------------

    /** Someone on the form: a friend, or a name typed in that this save will add as one. */
    private data class FormPerson(val friendId: Long?, val name: String) {
        val party: IouParty get() = friendId?.let { IouParty.Friend(it) } ?: IouParty.Person(name)
    }

    /** Everyone the form names, once each: either end, a split row, or someone a debt runs through. */
    private fun peopleOnForm(): List<FormPerson> {
        val people = linkedMapOf<String, FormPerson>()
        fun add(friendId: Long?, name: String) {
            val trimmed = name.trim()
            val key = friendId?.let { "id:$it" } ?: trimmed.lowercase().takeIf { it.isNotEmpty() }?.let { "name:$it" } ?: return
            people.putIfAbsent(key, FormPerson(friendId, trimmed))
        }
        listOf(true, false).forEach { isPayer ->
            if (actorTypeFor(isPayer) == ActorType.FRIEND) {
                val friendId = draftActorRef(isPayer).friendId
                add(friendId, friendId?.let(::friendNameOf) ?: primaryLabel(isPayer))
            }
        }
        val rows = payerShareRows + payeeShareRows
        rows.filter { it.participantType == ActorType.FRIEND }.forEach { add(it.friendId, it.label) }
        rows.flatMap { listOfNotNull(it.payeeVia, it.payerVia) }
            .filterIsInstance<IouParty.Friend>()
            .forEach { add(it.friendId, friendNameOf(it.friendId)) }
        return people.values.toList()
    }

    /**
     * Asks, for everyone on the form the selected chapter does not hold, whether to add them to it --
     * what tagging has always done (R5) -- or keep them outside, where what they and ME owe each other
     * stays in the base ledger. Keeping someone out routes their debts with anyone else in it through
     * ME, and the question says so.
     *
     * Asked every time rather than remembered per person, since who deals with whom changes -- except
     * about whoever the saved row already keeps outside, when that still holds as it stands. False
     * when the user backs out, which stops the save.
     */
    private suspend fun decideOutsiders(): Boolean {
        keptOutsideIds = emptySet()
        keptOutsideNames = emptySet()
        val option = chapterOptions.firstOrNull { it.chapter.id == selectedChapterId } ?: return true
        // A friend's chapter takes nothing tagged here, so its members are not this save's to grow.
        if (!option.chapter.isOwn) return true
        val missing = peopleOnForm().filter { it.friendId == null || it.friendId !in option.memberIds }
        if (missing.isEmpty()) return true

        val outside = mutableListOf<FormPerson>()
        for (person in missing) {
            val parties = (outside + person).map { it.party }.toSet()
            val payer = draftActorRef(true)
            val payee = draftActorRef(false)
            val shares = buildSharesForPersistence(0L)
            val routed = IouLegs.routeOutsidersThroughMe(
                payer, payee, shares, getCurrentAmountPaise(), chosenIouRecovery(payer, payee, shares), parties
            )
            val reroutes = routed != null && routed != shares
            if (person.friendId != null && person.friendId in storedOutsiders && routed != null && !reroutes) {
                outside += person
                continue
            }
            val note = routingNote(person.party, parties)
            when (askAboutOutsider(person.name, option.chapter.name, canKeepOutside = routed != null, routingNote = note)) {
                OutsiderChoice.ADD -> Unit
                OutsiderChoice.KEEP_OUTSIDE -> {
                    routed?.let(::applyRouting)
                    outside += person
                }
                OutsiderChoice.CANCEL -> return false
            }
        }
        keptOutsideIds = outside.mapNotNull { it.friendId }.toSet()
        keptOutsideNames = outside.filter { it.friendId == null }.map { it.name.lowercase() }.toSet()
        if (outside.isNotEmpty()) {
            buildShareSection(true)
            buildShareSection(false)
            updateLiveCalc()
        }
        return true
    }

    /** "Bob owing Asha ₹500 will go through you." for each debt keeping [party] out would reroute. */
    private fun routingNote(party: IouParty, outsiders: Set<IouParty>): String? {
        val payer = draftActorRef(true)
        val payee = draftActorRef(false)
        val lines = currentIouLines(payer, payee, buildSharesForPersistence(0L)).filter { line ->
            val ends = setOf(line.debtor, line.creditor)
            line.kept && party in ends && ends.none { it == IouParty.Me || (it != party && it in outsiders) }
        }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n") { line ->
            "${partyName(line.debtor)} owing ${partyName(line.creditor)} ${AmountFormat.rupees(line.amountPaise)} will go through you."
        }
    }

    /** Copies who each IOU runs through off [routed] -- [buildSharesForPersistence]'s list, rerouted -- onto the rows. */
    private fun applyRouting(routed: List<TransactionShare>) {
        draftShareRows(0L).forEachIndexed { index, (row, _) ->
            val share = routed.getOrNull(index) ?: return@forEachIndexed
            row.payeeVia = IouLegs.viaOf(share, owesPayer = false)
            row.payerVia = IouLegs.viaOf(share, owesPayer = true)
        }
    }

    /**
     * The friends [decideOutsiders] kept out, by id now that the save has resolved everyone: someone
     * typed in fresh is matched by the name they were kept out under.
     */
    private fun resolvedOutsiders(): Set<Long> {
        if (keptOutsideIds.isEmpty() && keptOutsideNames.isEmpty()) return emptySet()
        val byName = buildSet {
            listOf(true, false).forEach { isPayer ->
                val friendId = if (isPayer) payerFriendId else payeeFriendId
                if (actorTypeFor(isPayer) == ActorType.FRIEND && friendId != null &&
                    primaryLabel(isPayer).lowercase() in keptOutsideNames
                ) add(friendId)
            }
            (payerShareRows + payeeShareRows).forEach { row ->
                val friendId = row.friendId
                if (friendId != null && row.label.trim().lowercase() in keptOutsideNames) add(friendId)
            }
        }
        return keptOutsideIds + byName
    }

    private enum class OutsiderChoice { ADD, KEEP_OUTSIDE, CANCEL }

    private suspend fun askAboutOutsider(
        name: String,
        chapterName: String,
        canKeepOutside: Boolean,
        routingNote: String?
    ): OutsiderChoice = suspendCancellableCoroutine { continuation ->
        fun answer(choice: OutsiderChoice) {
            if (continuation.isActive) continuation.resume(choice) { _, _, _ -> }
        }
        val message = if (canKeepOutside) {
            "Add them to it, or keep them outside: what you and $name owe each other on this stays " +
                "between the two of you, out of the chapter." + (routingNote?.let { "\n\n$it" } ?: "")
        } else {
            "Someone in the chapter pays $name, or is paid by them, straight, so they cannot be kept " +
                "outside it. Add them, or take this out of the chapter."
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("$name isn't in $chapterName")
            .setMessage(message)
            .setPositiveButton("Add to chapter") { _, _ -> answer(OutsiderChoice.ADD) }
            .setNeutralButton("Cancel") { _, _ -> answer(OutsiderChoice.CANCEL) }
            .setOnCancelListener { answer(OutsiderChoice.CANCEL) }
        if (canKeepOutside) builder.setNegativeButton("Keep outside") { _, _ -> answer(OutsiderChoice.KEEP_OUTSIDE) }
        val dialog = builder.create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    /** [categoryAmountPaise] is the figure the categories were just validated against. */
    // --- chapters -------------------------------------------------------------------------------

    /**
     * Loads the chapter field and decides what it starts on.
     *
     * A transfer is never in a chapter, so the field stays hidden there. An existing row keeps the
     * chapter it already has; a new one starts on the active chapter, but only when that chapter
     * could take it and would not have to grow to do so (R19).
     */
    private suspend fun setupChapterField(db: AppDatabase) {
        // Loaded in transfer mode too: a transfer switched to a transaction can then be put in one.
        chapterOptions = withContext(Dispatchers.IO) {
            val repository = ChapterRepository(db)
            db.chapterDao().getAll().map { chapter ->
                ChapterOption(
                    chapter = chapter,
                    memberIds = db.chapterDao().memberIds(chapter.id).toSet(),
                    // A friend's chapter is never offered, so whether it is settled is never asked.
                    settled = !chapter.isOwn || repository.resultFor(chapter.id).settled
                )
            }
        }
        chapterOwnerNames = withContext(Dispatchers.IO) {
            chapterOptions.mapNotNull { option ->
                val ownerId = option.chapter.ownerFriendId ?: return@mapNotNull null
                db.friendDao().getFriendById(ownerId)?.let { option.chapter.id to it.name }
            }.toMap()
        }
        val tx = currentTransaction
        val current = tx?.chapterId?.let { id -> chapterOptions.firstOrNull { it.chapter.id == id } }
        // R8 is about the user's own chapters: a friend closing theirs locks nothing here (S6).
        chapterLocked = current?.chapter?.isOwn == true && current.chapter.state == ChapterState.CLOSED
        storedOutsiders = tx?.takeIf { it.chapterId != null }
            ?.let { withContext(Dispatchers.IO) { db.chapterOutsiderDao().forTransaction(it.id).toSet() } }
            .orEmpty()

        val pended = draftSnapshot?.draft
        selectedChapterId = when {
            // The pick the form was pended with, while it can still be made: the chapter the row is
            // already in, or one of the user's own that is still open.
            pended != null -> pended.chapterId?.takeIf { id ->
                id == tx?.chapterId ||
                    chapterOptions.any { it.chapter.id == id && it.chapter.isOwn && it.chapter.state == ChapterState.OPEN }
            }
            tx?.chapterId != null -> tx.chapterId
            tx != null -> {
                val original = withContext(Dispatchers.IO) {
                    tx.refundsTransactionId?.let { db.transactionDao().getTransactionById(it) }
                }
                val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(tx.id) }
                ChapterPrompt.preselect(tx, shares, chapterOptions, original)?.chapter?.id
            }
            // A transfer switched to a transaction is not new to the books: nothing picks one for it.
            currentTransfer != null -> null
            // A blank manual entry names nobody yet, so the active chapter can only be right.
            else -> chapterOptions.firstOrNull { it.chapter.isActive && it.chapter.state == ChapterState.OPEN }
                ?.chapter?.id
        }
        renderChapterField()
    }

    private fun renderChapterField() {
        if (isTransferMode() || chapterOptions.isEmpty()) {
            chapterCard.visibility = View.GONE
            return
        }
        chapterCard.visibility = View.VISIBLE
        val selected = chapterOptions.firstOrNull { it.chapter.id == selectedChapterId }
        val owner = selected?.let { chapterOwnerNames[it.chapter.id] }
        tvChapterValue.text = when {
            selected == null -> "Not in a chapter"
            owner != null -> "$owner's ${selected.chapter.name}"
            else -> selected.chapter.name
        }

        when {
            chapterLocked && selected != null -> {
                tvChapterHint.text = "In ${selected.chapter.name} (closed). Reopen it to edit this."
                tvChapterHint.setTextColor(themeColor(ThemeAttr.warning))
                tvChapterHint.visibility = View.VISIBLE
            }
            // S6: it can still be edited, but it stays where the owner's plan counts it.
            selected != null && !selected.chapter.isOwn -> {
                val who = owner ?: "Its owner"
                tvChapterHint.text = "$who keeps this chapter. This stays in it until $who takes it out."
                tvChapterHint.setTextColor(themeColor(ThemeAttr.textMuted))
                tvChapterHint.visibility = View.VISIBLE
            }
            else -> tvChapterHint.visibility = View.GONE
        }
    }

    private fun showChapterPicker() {
        if (chapterLocked) {
            toast("This is in a closed chapter. Reopen it to make changes.")
            return
        }
        chapterOptions.firstOrNull { it.chapter.id == selectedChapterId && !it.chapter.isOwn }?.let { copy ->
            val who = chapterOwnerNames[copy.chapter.id] ?: "its owner"
            toast("In $who's ${copy.chapter.name}. It stays there until $who takes it out.")
            return
        }
        // S10: only the user's own chapters take anything tagged here.
        val open = chapterOptions.filter { it.chapter.isOwn && it.chapter.state == ChapterState.OPEN }
        if (open.isEmpty()) {
            toast("No open chapters")
            return
        }
        val labels = (listOf("Not in a chapter") + open.map { it.chapter.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chapter")
            .setItems(labels) { _, which ->
                selectedChapterId = if (which == 0) null else open[which - 1].chapter.id
                refollowChapterTemplate()
                renderChapterField()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** What the settle-up prompt came back with. Cancelling is not the same as saying "no chapter". */
    private sealed interface ChapterChoice {
        data class Picked(val chapterId: Long?) : ChapterChoice
        data object Cancelled : ChapterChoice
    }

    /**
     * R18. Money straight between two people who are both in an open, unsettled chapter is very
     * likely settling it up -- and if it is, the chapter has to know, or its plan will keep asking
     * for a payment that has already been made.
     */
    private suspend fun askAboutSettleUp(candidates: List<ChapterOption>): ChapterChoice =
        suspendCancellableCoroutine { continuation ->
            val labels = (candidates.map { it.chapter.name } + "Not for a chapter").toTypedArray()
            val dialog = AlertDialog.Builder(this)
                .setTitle("Is this settling up?")
                .setItems(labels) { _, which ->
                    val id = if (which == candidates.size) null else candidates[which].chapter.id
                    if (continuation.isActive) continuation.resume(ChapterChoice.Picked(id)) {  _, _, _ -> }
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(ChapterChoice.Cancelled) { _, _, _ -> }
                }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    /**
     * Asks about a settle-up when one is on the cards, and returns whether to go on saving.
     *
     * Skipped when a chapter is already chosen -- the question has been answered -- and when the
     * shape is not money straight between two people who share a chapter.
     */
    private suspend fun resolveChapterBeforeSaving(db: AppDatabase, amountPaise: Long): Boolean {
        if (isTransferMode() || selectedChapterId != null) return true
        val provisional = provisionalTransaction(amountPaise) ?: return true
        val candidates = ChapterPrompt.settleUpCandidates(provisional, emptyList(), chapterOptions)
        if (candidates.isEmpty()) return true

        return when (val choice = askAboutSettleUp(candidates)) {
            is ChapterChoice.Picked -> {
                selectedChapterId = choice.chapterId
                renderChapterField()
                true
            }
            ChapterChoice.Cancelled -> false
        }
    }

    /**
     * The transaction as it stands on screen, good enough to ask [ChapterPrompt] about.
     *
     * Only the settle-up shape is being tested, so the two ends and the amount are the whole of
     * what matters; anything with a share row is not that shape and returns null early.
     */
    private fun provisionalTransaction(amountPaise: Long): Transaction? {
        if (buildSharesForPersistence(0L).isNotEmpty()) return null
        return Transaction(
            id = currentTransaction?.id ?: 0L,
            amountPaise = amountPaise,
            payerActorType = payerActorType,
            payerFriendId = payerFriendId,
            payeeActorType = payeeActorType,
            payeeFriendId = payeeFriendId,
            dateEpoch = selectedDateEpoch,
            source = currentTransaction?.source ?: "MANUAL",
            chapterId = null
        )
    }

    /**
     * A save into, out of or inside a shared chapter of the user's own leaves its members' copies
     * behind. They are sent in the background, since this screen is about to close.
     */
    private suspend fun publishIfShared(db: AppDatabase, chapterIds: Set<Long>) {
        if (chapterIds.isEmpty()) return
        withContext(Dispatchers.IO) { ChapterPublishing.soonIfShared(applicationContext, db, chapterIds) }
    }

    private suspend fun chapterMemberIds(db: AppDatabase, chapterId: Long?): Set<Long> {
        if (chapterId == null) return emptySet()
        return withContext(Dispatchers.IO) { db.chapterDao().memberIds(chapterId).toSet() }
    }

    /** R5: tagging pulls everyone in a transaction into the chapter, so say who that was. */
    private suspend fun reportAddedMembers(db: AppDatabase, before: Set<Long>) {
        val chapterId = selectedChapterId ?: return
        val names = withContext(Dispatchers.IO) {
            val after = db.chapterDao().memberIds(chapterId).toSet()
            val chapterName = db.chapterDao().getById(chapterId)?.name
            (after - before).mapNotNull { db.friendDao().getFriendById(it)?.name } to chapterName
        }
        val (added, chapterName) = names
        if (added.isNotEmpty() && chapterName != null) {
            toast("Added ${added.joinToString(", ")} to $chapterName")
        }
    }

    private suspend fun persistTransaction(
        db: AppDatabase,
        amountPaise: Long,
        payerLabel: String,
        payeeLabel: String,
        categoryAmountPaise: Long
    ): Long =
        transactionPersistenceService.persist(
            db = db,
            request = PersistTransactionRequest(
                existingTransaction = currentTransaction,
                amountPaise = amountPaise,
                selectedAccountId = accountIdForPersistence(),
                dateEpoch = selectedDateEpoch,
                description = etDescription.text.toString().trim().ifEmpty { null },
                refundsTransactionId = refundsTransactionId,
                chapterId = selectedChapterId,
                // A saved transfer switched to a transaction: replaced in the same database
                // transaction, keeping what the importers dedupe on and where it came from.
                replacesTransferId = currentTransfer?.id,
                upiRefId = currentTransfer?.upiRefId,
                statementRefNo = currentTransfer?.statementRefNo,
                source = currentTransfer?.source?.transactionSource() ?: "MANUAL"
            ),
            resolveActors = {
                val payer = resolveActor(db, true, payerActorType, payerLabel)
                val payee = resolveActor(db, false, payeeActorType, payeeLabel)
                payer to payee
            },
            resolveUnresolvedShareRows = { resolveUnresolvedShareRows(db) },
            buildSharesForPersistence = { txId -> buildSharesForPersistence(txId) },
            categoryAmountPaise = { categoryAmountPaise },
            chooseIouRecovery = { payer, payee, shares -> chosenIouRecovery(payer, payee, shares) },
            persistCategories = { transactionId, toCategorisePaise, payer, payee ->
                persistCategories(db, transactionId, toCategorisePaise, payer, payee)
            },
            resolveOutsiders = { resolvedOutsiders() }
        )

    // --- agreed balances ----------------------------------------------------------------------------

    private suspend fun loadAgreements(db: AppDatabase) {
        checkpoints = declarations.allCheckpoints()
        linkedFriendIds = withContext(Dispatchers.IO) {
            db.mailboxDao().getAllLinks().filter { it.state == FriendLinkState.LINKED }.map { it.friendId }.toSet()
        }
        renderAgreementNotice()
    }

    /** Every friend this row names, as it stands on screen: either end, or a row of the split. */
    private fun friendsInRow(): Set<Long> = buildSet {
        if (payerActorType == ActorType.FRIEND) payerFriendId?.let(::add)
        if (payeeActorType == ActorType.FRIEND) payeeFriendId?.let(::add)
        (payerShareRows + payeeShareRows)
            .filter { it.participantType == ActorType.FRIEND }
            .mapNotNullTo(this) { it.friendId }
    }

    private fun friendNameOf(friendId: Long): String =
        allFriends.firstOrNull { it.id == friendId }?.name ?: "your friend"

    /** "Bob (10 Sep 2026) and Carol (2 Aug 2026)". */
    private fun agreementNames(agreements: List<IouDeclaration>): String =
        agreements.joinToString(" and ") { "${friendNameOf(it.friendId)} (${AgreementText.day(requireNotNull(it.asOfEpoch))})" }

    /**
     * D8 on the entry screen: says when this row is dated at or before a balance agreed with someone in
     * it -- saving it changes nothing between the two of you -- and when an edit moves it past one.
     */
    private fun renderAgreementNotice() {
        if (!::agreementNotice.isInitialized) return
        if (isTransferMode() || checkpoints.isEmpty()) {
            agreementNotice.visibility = View.GONE
            return
        }
        val inRow = friendsInRow().mapNotNull { checkpoints[it] }
        val covered = inRow.filter { requireNotNull(it.asOfEpoch) >= selectedDateEpoch }
        val stored = currentTransaction
        val movedPast = inRow.filter { checkpoint ->
            val asOf = requireNotNull(checkpoint.asOfEpoch)
            stored != null && stored.dateEpoch <= asOf && selectedDateEpoch > asOf
        }
        val text = when {
            covered.isNotEmpty() ->
                "Dated before your agreed balance with ${agreementNames(covered)}, so it does not change what you owe each other."
            movedPast.isNotEmpty() ->
                "Now dated after your agreed balance with ${agreementNames(movedPast)}, so it will count on top of it."
            else -> null
        }
        if (text == null) {
            agreementNotice.visibility = View.GONE
            return
        }
        agreementNotice.visibility = View.VISIBLE
        tvAgreementNotice.text = text
        tvAgreementNotice.setTextColor(themeColor(ThemeAttr.warning))

        // Only a saved row, as saved: what would be added is measured off the stored copy.
        val askable = askableAgreements()
        btnAgreementAdd.visibility = if (askable.isEmpty()) View.GONE else View.VISIBLE
        btnAgreementAdd.text = if (askable.size == 1) {
            "Ask ${friendNameOf(askable.single().friendId)} to add it"
        } else {
            "Ask to add it\u2026"
        }
    }

    /** The agreements the saved row falls under that can still be amended, if the form still shows it as saved. */
    private fun askableAgreements(): List<IouDeclaration> {
        val stored = currentTransaction ?: return emptyList()
        if (stored.isPending || stored.chapterId != null || selectedChapterId != null) return emptyList()
        if (stored.dateEpoch != selectedDateEpoch) return emptyList()
        return friendsInRow().mapNotNull { checkpoints[it] }.filter {
            !it.archived && it.friendId in linkedFriendIds && requireNotNull(it.asOfEpoch) >= stored.dateEpoch
        }
    }

    private fun askToAddSavedRow() {
        val stored = currentTransaction ?: return
        val askable = askableAgreements()
        when (askable.size) {
            0 -> return
            1 -> askToAdd(stored, askable.single())
            else -> AlertDialog.Builder(this)
                .setTitle("Ask who?")
                .setItems(askable.map { friendNameOf(it.friendId) }.toTypedArray()) { _, which ->
                    askToAdd(stored, askable[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun askToAdd(tx: Transaction, checkpoint: IouDeclaration, onDone: () -> Unit = {}) {
        lifecycleScope.launch {
            val delta = declarations.effectOf(tx.id, checkpoint.friendId)
            agreementDialogs.askToAdd(checkpoint.friendId, friendNameOf(checkpoint.friendId), checkpoint, delta, describe(tx), onDone)
        }
    }

    /** How a row is named in the note an amendment carries. */
    private fun describe(tx: Transaction): String {
        val what = tx.reason?.takeIf { it.isNotBlank() }?.let { "\"$it\", " }.orEmpty()
        return "${what}${AmountFormat.rupeesExact(tx.amountPaise)} on ${AgreementText.day(tx.dateEpoch)}"
    }

    /**
     * D10, offered at the moment it matters: a row counted for the first time but dated at or before
     * an agreement with a linked friend moved nothing, and right after saving it is when to ask them
     * to fold it in. True when the question is up; the screen then finishes once it is answered.
     */
    private suspend fun offerToAddLateRow(db: AppDatabase, transactionId: Long): Boolean {
        val saved = withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(transactionId) } ?: return false
        if (saved.chapterId != null || saved.isPending) return false
        val shares = withContext(Dispatchers.IO) { db.transactionShareDao().getSharesForTransaction(saved.id) }
        val checkpoint = ChapterMath.friendsIn(saved, shares)
            .mapNotNull { checkpoints[it] }
            .firstOrNull { !it.archived && it.friendId in linkedFriendIds && requireNotNull(it.asOfEpoch) >= saved.dateEpoch }
            ?: return false
        if (declarations.effectOf(saved.id, checkpoint.friendId) == 0L) return false
        askToAdd(saved, checkpoint) { finish() }
        return true
    }

    /** The rows that become shares on saving, each beside the share it becomes, in the order they are saved. */
    private fun draftShareRows(txId: Long): List<Pair<ShareRow, TransactionShare>> =
        listOf("PAYER" to payerShareRows, "PAYEE" to payeeShareRows).flatMap { (side, rows) ->
            rows.filter { it.participantType != ActorType.MERCHANT && it.key.isNotBlank() }.map { row ->
                row to TransactionShare(
                    transactionId = txId,
                    side = side,
                    participantType = row.participantType,
                    friendId = row.friendId,
                    amountPaise = row.amountPaise,
                    rawLabel = row.carriedLabel(),
                    keepPayeeLeg = row.keepPayeeLeg,
                    keepPayerLeg = row.keepPayerLeg
                ).let { IouLegs.withVia(IouLegs.withVia(it, false, row.payeeVia), true, row.payerVia) }
            }
        }

    private fun buildSharesForPersistence(txId: Long): List<TransactionShare> =
        draftShareRows(txId).map { it.second }

    private suspend fun resolveActor(db: AppDatabase, isPayer: Boolean, actorType: String, typedLabel: String): ActorRef {
        return when (actorType) {
            ActorType.ME -> ActorRef(ActorType.ME, rawLabel = "Me")
            ActorType.FRIEND -> {
                val friendId = resolveFriendId(db, typedLabel, isPayer)
                currentSmsRawLabel(isPayer)?.let { maybeLinkRawAliasToFriend(db, friendId, it) }
                pointPrimaryRowAt(db, isPayer, friendId)
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

    /**
     * Makes the primary share row name [friendId], the friend the endpoint was just resolved to.
     * Runs before the shares are built, so a primary friend typed in by a new name saves with their
     * share under them rather than under whoever the row named before.
     */
    private suspend fun pointPrimaryRowAt(db: AppDatabase, isPayer: Boolean, friendId: Long) {
        if (isPayer) payerFriendId = friendId else payeeFriendId = friendId
        val rows = rowsFor(isPayer)
        if (rows.isEmpty() || rows[0].friendId == friendId) return
        val friend = allFriends.firstOrNull { it.id == friendId } ?: db.friendDao().getFriendById(friendId) ?: return
        updateShareRowParticipant(rows, 0, ActorType.FRIEND, friend.id, friend.name, friend.avatarInitials)
    }

    private suspend fun resolveFriendId(db: AppDatabase, typedLabel: String, isPayer: Boolean): Long {
        (if (isPayer) payerFriendId else payeeFriendId)?.let { return it }
        return resolveOrCreateFriend(db, typedLabel).id
    }

    private fun computeFriendInitials(name: String): String = initialsOf(name, fallback = "F")

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

    /**
     * Whether a name typed or picked into a share row means the user: "Me", the "Asha(Me)" the
     * dropdown offers, or the bare alias. The bare alias gives way to a friend who has exactly that
     * name, who could otherwise never be picked from the list at all.
     */
    private fun isMeName(name: String): Boolean {
        val typed = name.trim()
        if (typed.equals("Me", ignoreCase = true) || typed.equals(meLabel, ignoreCase = true)) return true
        val alias = selfAlias ?: return false
        return typed.equals(alias, ignoreCase = true) && allFriends.none { it.name.equals(typed, ignoreCase = true) }
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
            isMeName(name) -> {
                if (rows.withIndex().any { (i, r) -> i != index && r.participantType == ActorType.ME }) {
                    toast("Person already added")
                    return false
                }
                updateShareRowParticipant(rows, index, ActorType.ME, null, meLabel, "ME")
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
            isMeName(name) -> {
                if (rows.withIndex().any { (i, r) -> i != index && r.participantType == ActorType.ME }) return
                updateShareRowParticipant(rows, index, ActorType.ME, null, meLabel, "ME")
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

    private suspend fun persistCategories(
        db: AppDatabase,
        transactionId: Long,
        categoryAmountPaise: Long,
        payer: ActorRef,
        payee: ActorRef
    ) {
        if (categoryAmountPaise <= 0L) return
        // Use the just-resolved actor refs, not the cached payerMerchantId/payeeMerchantId fields:
        // those are still null for a merchant newly created earlier in this same save.
        //
        // Null for a ledger-neutral gift, which has a friend on the other side. That must not stop
        // the splits being written: the entry screen makes categories mandatory for those too, and
        // persist() has already deleted the previous rows by the time this runs -- so returning
        // early here silently dropped every category the user was just forced to pick.
        val merchantId = categorySplitManager.selectedMerchantId(
            payerActorType = payerActorType,
            payeeActorType = payeeActorType,
            payerMerchantId = payer.merchantId,
            payeeMerchantId = payee.merchantId
        )
        categoryEntries.filter { it.isChecked }.forEach { entry ->
            // Only a suggestion cache for next time; nothing that affects money reads it.
            if (merchantId != null) {
                db.categoryDao().linkMerchantCategory(
                    MerchantCategory(
                        merchantId = merchantId,
                        categoryId = entry.category.id
                    )
                )
            }
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
        categoryEntries.forEach { entry ->
            val rowView = LayoutInflater.from(this).inflate(R.layout.item_transaction_category_split, categoryContainer, false)
            val tvName = rowView.findViewById<TextView>(R.id.tvCategoryName)
            val tvSeparator = rowView.findViewById<TextView>(R.id.tvCategorySeparator)
            val etMyAmount = rowView.findViewById<EditText>(R.id.etCategoryMyAmount)

            tvName.text = entry.category.name
            tvSeparator.visibility = if (entry.isChecked) View.VISIBLE else View.GONE
            etMyAmount.visibility = if (entry.isChecked) View.VISIBLE else View.GONE
            styleCategoryPill(rowView, entry.isChecked)

            (etMyAmount.tag as? TextWatcher)?.let { etMyAmount.removeTextChangedListener(it) }
            etMyAmount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            etMyAmount.imeOptions = EditorInfo.IME_ACTION_DONE
            etMyAmount.setText(if (entry.myAmountPaise > 0L) AmountFormat.forInput(entry.myAmountPaise) else "")

            val watcher = simpleWatcher {
                entry.myAmountPaise = ((etMyAmount.text.toString().toDoubleOrNull() ?: 0.0) * 100).toLong()
                userTouchedCategories = true
                // A manual edit is an explicit override; stop auto-following ME's share for this one.
                if (trackedCategoryId == entry.category.id) {
                    trackedCategoryId = null
                }
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

            // No native Checkable widget anymore (a real Chip can't host the inline
            // EditText) — toggle manually on the whole pill row instead.
            rowView.setOnClickListener {
                userTouchedCategories = true
                val checked = !entry.isChecked
                entry.isChecked = checked
                if (checked) {
                    trackedCategoryId = entry.category.id
                    applyRemainderAmount(entry)
                } else if (trackedCategoryId == entry.category.id) {
                    trackedCategoryId = null
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

    private fun fmtDateTime(epoch: Long): String = SimpleDateFormat(
        "dd MMM yyyy, hh:mm a",
        Locale.getDefault()
    ).format(Date(epoch))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}