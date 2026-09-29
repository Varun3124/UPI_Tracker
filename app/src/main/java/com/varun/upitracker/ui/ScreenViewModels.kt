package com.varun.upitracker.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.varun.upitracker.data.repository.AccountMutationException
import com.varun.upitracker.data.repository.AccountRepository
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.data.repository.LedgerRepository
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.data.repository.SettingsRepository
import com.varun.upitracker.data.repository.TransactionRemoval
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountType
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.domain.AccountTypes
import com.varun.upitracker.domain.BalanceConfidence
import com.varun.upitracker.domain.search.TransactionSearch
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.statistics.ListWindow
import com.varun.upitracker.domain.statistics.PayeeRef
import com.varun.upitracker.domain.statistics.resolve
import com.varun.upitracker.maintenance.ChapterPublishing
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.database.entity.Category
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Merchant
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.transactionentry.EntrySide
import com.varun.upitracker.ui.transactionentry.TransactionEntryAction
import com.varun.upitracker.ui.transactionentry.TransactionEntryEffect
import com.varun.upitracker.ui.transactionentry.TransactionEntryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class TransactionEntryReferenceData(
    val friends: List<Friend> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** Accounts a normal transaction may be attributed to. */
    val accounts: List<Account> = emptyList(),
    /** Wider list used in transfer mode, where any active account is a valid endpoint. */
    val transferAccounts: List<Account> = emptyList(),
    val transaction: Transaction? = null,
    val transfer: AccountTransfer? = null
)

class TransactionEntryViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val _referenceData = MutableLiveData<TransactionEntryReferenceData>()
    val referenceData: LiveData<TransactionEntryReferenceData> = _referenceData
    private val _uiState = MutableLiveData(TransactionEntryUiState())
    val uiState: LiveData<TransactionEntryUiState> = _uiState
    private val _effects = MutableLiveData<TransactionEntryEffect>()
    val effects: LiveData<TransactionEntryEffect> = _effects

    fun launchTask(task: suspend () -> Unit) {
        viewModelScope.launch {
            task()
        }
    }

    fun onAction(action: TransactionEntryAction) {
        when (action) {
            is TransactionEntryAction.AmountChanged -> {
                _uiState.value = (_uiState.value ?: TransactionEntryUiState()).copy(amountRaw = action.rawAmount)
            }

            is TransactionEntryAction.AccountSelected -> {
                val state = _uiState.value ?: TransactionEntryUiState()
                _uiState.value = if (action.side == EntrySide.PAYER) {
                    state.copy(payerAccountId = action.accountId)
                } else {
                    state.copy(payeeAccountId = action.accountId)
                }
            }

            is TransactionEntryAction.DescriptionChanged -> {
                _uiState.value = (_uiState.value ?: TransactionEntryUiState()).copy(description = action.text)
            }

            else -> Unit
        }
        _effects.value = TransactionEntryEffect.RunLegacyAction(action)
    }

    fun load(transactionId: Long?, transferId: String? = null) {
        viewModelScope.launch {
            _referenceData.value = withContext(Dispatchers.IO) {
                TransactionEntryReferenceData(
                    friends = db.friendDao().getAllFriendsByFrequency(),
                    merchants = db.merchantDao().getAllMerchantsSync(),
                    categories = db.categoryDao().getAllCategoriesSync(),
                    accounts = db.accountDao().getActiveByTypes(AccountTypes.LIQUID),
                    transferAccounts = db.accountDao().getActiveSync(),
                    transaction = transactionId?.let { db.transactionDao().getTransactionById(it) },
                    transfer = transferId?.let { db.accountTransferDao().getById(it) }
                )
            }
        }
    }
}

data class AllTransactionsUiState(
    val entries: List<LedgerEntry> = emptyList(),
    /** Per-row title, note and figures, resolved on the loading thread. Keyed by [stableId]. */
    val rows: Map<String, TransactionRowInfo> = emptyMap(),
    val accountLabels: Map<String, String> = emptyMap(),
    /** The span on screen: a month, a hand-picked range, or all of history. */
    val window: ListWindow = ListWindow.currentMonth(),
    val pendingOnly: Boolean = false,
    /** True when only entries where neither side of the transaction is ME should show. */
    val thirdPartyOnly: Boolean = false,
    /** Every account, for the scope picker: archived ones included, since one can be named. */
    val accounts: List<Account> = emptyList(),
    /** Which accounts the list and the balance row cover. */
    val scope: AccountScope = AccountScope.Liquid,
    /** What the search box holds. Matched against [TransactionRowInfo] names and the note. */
    val query: String = "",
    /** Amount bounds, inclusive, on the figure the row shows. Either may stand alone. */
    val minPaise: Long? = null,
    val maxPaise: Long? = null,
    /** A merchant or friend the list is narrowed to, as a merchant slice on Statistics opens it. */
    val payee: PayeeFilter? = null,
    /** Entries in the range before any filter, so the bar can show what it hides. */
    val totalEntryCount: Int = 0,
    val showBalance: Boolean = false,
    /** Combined balance of the in-scope accounts immediately before the window opens. */
    val openingBalancePaise: Long = 0L,
    /** Opening plus every in-scope movement in the window. */
    val closingBalancePaise: Long = 0L,
    /** True when at least one account is in scope, so the figures mean something. */
    val hasBalance: Boolean = false,
    /**
     * When the in-scope balances stop being a reconstruction; null when they never do.
     *
     * Entries older than this show their running balance in the speculative colour -- see
     * [BalanceConfidence].
     */
    val balanceCertainFromEpoch: Long? = null,
    val selectionMode: Boolean = false,
    /** [stableId]s, because the list interleaves Long transaction ids with UUID transfer ids. */
    val selected: Set<String> = emptySet()
) {
    val isFiltered: Boolean
        get() = pendingOnly || thirdPartyOnly || scope != AccountScope.Liquid ||
            query.isNotBlank() || minPaise != null || maxPaise != null || payee != null

    val isOpeningSpeculative: Boolean
        get() = BalanceConfidence.isSpeculative(window.startEpoch - 1, balanceCertainFromEpoch)

    val isClosingSpeculative: Boolean
        get() = BalanceConfidence.isSpeculative(window.lastMillis, balanceCertainFromEpoch)

    /** Transactions only: an account transfer is nobody else's business to share. */
    val selectedTransactionIds: Set<Long>
        get() = entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in selected }
            .map { it.transaction.id }
            .toSet()
}

/** A counterparty the transactions list is narrowed to, with the name its pill shows. */
data class PayeeFilter(val ref: PayeeRef, val name: String)

/** What adding a selection to a chapter came to, for the screen to report. */
data class ChapterTagOutcome(
    val chapterName: String,
    val taggedCount: Int,
    /** Friends who had to join the chapter to take these transactions (R5). */
    val addedMembers: List<String>,
    /** How many were refused, and the distinct reasons why. */
    val failedCount: Int,
    val failureReasons: List<String>
)

class AllTransactionsViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val accountRepository = AccountRepository(db)

    private var window: ListWindow = ListWindow.currentMonth()
    private var pendingOnly: Boolean = false
    private var thirdPartyOnly: Boolean = false
    private var scope: AccountScope = AccountScope.Liquid
    private var query: String = ""
    private var minPaise: Long? = null
    private var maxPaise: Long? = null
    private var payee: PayeeFilter? = null
    private var showBalance: Boolean = false
    private var selectionMode: Boolean = false
    private var selected: Set<String> = emptySet()

    private var loadedEntries: List<LedgerEntry> = emptyList()
    private var loadedAccountLabels: Map<String, String> = emptyMap()
    private var loadedAccounts: List<Account> = emptyList()
    /** Every name each entry can be found by, keyed by [stableId]. Built once per load. */
    private var searchFields: Map<String, List<String?>> = emptyMap()
    private var titles: Map<String, String> = emptyMap()
    private var openingBalancePaise: Long = 0L
    private var hasBalance: Boolean = false
    private var balanceCertainFromEpoch: Long? = null

    private val _uiState = MutableLiveData(AllTransactionsUiState())
    val uiState: LiveData<AllTransactionsUiState> = _uiState

    /** Reloads whatever window is showing, so a custom range survives returning to the screen. */
    fun reload() = load(window)

    /**
     * Starts the screen somewhere other than this month's liquid accounts -- for arriving from a
     * merchant slice on Statistics. Takes effect on the next [reload], which the screen always runs.
     *
     * The pie there counts every account, so the list opens on all of them rather than on Liquid: a
     * purchase from an account outside Liquid would otherwise be in the slice and missing here.
     */
    fun openOn(window: ListWindow, payee: PayeeFilter?, query: String, scope: AccountScope) {
        this.window = window
        this.payee = payee
        this.query = query
        this.scope = scope
    }

    fun load(next: ListWindow) {
        window = next
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val transactions = db.transactionDao()
                    .getTransactionsBetweenSync(window.startEpoch, window.endExclusiveEpoch)
                    .map(LedgerEntry::Tx)
                val transfers = db.accountTransferDao()
                    .getTransfersBetween(window.startEpoch, window.endExclusiveEpoch)
                    .map(LedgerEntry::Transfer)
                loadedEntries = (transactions + transfers).sortedByDescending { it.dateEpoch }
                loadedAccounts = db.accountDao().getAllSync()
                loadedAccountLabels = loadedAccounts.associate { it.id to it.label }
                indexNames()
                loadOpeningBalance()
            }
            emitState()
        }
    }

    /**
     * The names every row is titled and searched by.
     *
     * Two queries for the whole screen rather than one per row: a list that resolved each row as it
     * bound would spend a query per row per redraw, and a filtered list redraws on every keystroke.
     */
    private suspend fun indexNames() {
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }
        val fields = HashMap<String, List<String?>>(loadedEntries.size)
        val resolved = HashMap<String, String>(loadedEntries.size)
        loadedEntries.forEach { entry ->
            val key = entry.stableId()
            when (entry) {
                is LedgerEntry.Tx -> {
                    val tx = entry.transaction
                    resolved[key] = tx.resolvePrimaryDisplay(friendNames, merchantNames)
                    fields[key] = tx.searchableNames(friendNames, merchantNames) + tx.reason
                }
                is LedgerEntry.Transfer -> {
                    val transfer = entry.transfer
                    resolved[key] = transfer.resolvePrimaryDisplay()
                    fields[key] = listOf(
                        transfer.resolvePrimaryDisplay(),
                        transfer.fromAccountId?.let { loadedAccountLabels[it] },
                        transfer.toAccountId?.let { loadedAccountLabels[it] }
                    )
                }
            }
        }
        searchFields = fields
        titles = resolved
    }

    // --- filters, all over what is already loaded ------------------------------------------------

    fun setPendingOnly(enabled: Boolean) = ifChanged(enabled != pendingOnly) { pendingOnly = enabled }

    fun setThirdPartyOnly(enabled: Boolean) = ifChanged(enabled != thirdPartyOnly) { thirdPartyOnly = enabled }

    fun setShowBalance(enabled: Boolean) = ifChanged(enabled != showBalance) { showBalance = enabled }

    fun setQuery(text: String) = ifChanged(text != query) { query = text }

    fun clearPayee() = ifChanged(payee != null) { payee = null }

    fun setAmountRange(min: Long?, max: Long?) =
        ifChanged(min != minPaise || max != maxPaise) {
            minPaise = min
            maxPaise = max
        }

    private inline fun ifChanged(changed: Boolean, apply: () -> Unit) {
        if (!changed) return
        apply()
        emitState()
    }

    /**
     * Unlike the other filters this needs a database round trip: the scope decides which accounts the
     * opening balance covers, not just which rows are shown.
     */
    fun setScope(next: AccountScope) {
        if (next == scope) return
        scope = next
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadOpeningBalance() }
            emitState()
        }
    }

    // --- selection ------------------------------------------------------------------------------

    fun enterSelection(stableId: String) {
        selectionMode = true
        selected = setOf(stableId)
        emitState()
    }

    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected = emptySet()
        emitState()
    }

    fun toggleSelection(stableId: String) {
        selected = if (stableId in selected) selected - stableId else selected + stableId
        emitState()
    }

    fun selectAllVisible() {
        selected = _uiState.value?.entries?.map { it.stableId() }?.toSet().orEmpty()
        emitState()
    }

    // --- chapters -------------------------------------------------------------------------------

    /**
     * The chapters a selection can go into: open ones of the user's own, the active one first. A
     * friend's chapter takes nothing tagged here (S10): what is in it is theirs to say.
     */
    suspend fun openChapters(): List<Chapter> = withContext(Dispatchers.IO) {
        db.chapterDao().getAll()
            .filter { it.isOwn && it.state == ChapterState.OPEN }
            .sortedByDescending { it.isActive }
    }

    fun addSelectedToChapter(chapterId: Long, onDone: (ChapterTagOutcome) -> Unit) =
        tagSelection(onDone) { chapterId }

    /** Made with nobody in it: tagging brings in whoever the transactions name (R5). */
    fun addSelectedToNewChapter(name: String, onDone: (ChapterTagOutcome) -> Unit) =
        tagSelection(onDone) { ChapterRepository(db).create(name, emptySet()) }

    /**
     * The selected transactions into whichever chapter [chapterId] resolves to. Transfers in the
     * selection are passed over: a chapter holds transactions, and money moved between your own
     * accounts is nobody's share of anything.
     */
    private fun tagSelection(onDone: (ChapterTagOutcome) -> Unit, chapterId: suspend () -> Long) {
        val ids = _uiState.value?.selectedTransactionIds.orEmpty()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val target = chapterId()
                val result = ChapterRepository(db).tagAll(target, ids)
                // Moved into a shared chapter, or out of one: its members' copies are behind now.
                if (db.chapterDao().sharedByMe().isNotEmpty()) ChapterPublishing.soon(appContext)
                ChapterTagOutcome(
                    chapterName = db.chapterDao().getById(target)?.name.orEmpty(),
                    taggedCount = result.taggedCount,
                    addedMembers = result.addedMemberIds.mapNotNull { db.friendDao().getFriendById(it)?.name },
                    failedCount = result.failures.size,
                    failureReasons = result.failures.distinct()
                )
            }
            exitSelection()
            reload()
            onDone(outcome)
        }
    }

    // --- balance --------------------------------------------------------------------------------

    private suspend fun loadOpeningBalance() {
        val ids = scope.resolve(loadedAccounts)
        hasBalance = ids.isNotEmpty()
        // One millisecond before the window: getBalance's upper bound is inclusive, and an entry
        // dated exactly at the window start belongs inside it, not before it.
        openingBalancePaise = ids.sumOf { accountRepository.getBalance(it, window.startEpoch - 1) }
        balanceCertainFromEpoch = accountRepository.balanceCertainFrom(ids)
    }

    // --- publishing -----------------------------------------------------------------------------

    private fun emitState() {
        val ids = scope.resolve(loadedAccounts)
        val filtered = loadedEntries
            // Account transfers have no pending state, so that filter excludes them entirely.
            .filter { !pendingOnly || (it is LedgerEntry.Tx && it.transaction.isPending) }
            // Transfers never have ME as payer/payee, so this filter excludes them entirely too.
            .filter {
                !thirdPartyOnly ||
                    (it is LedgerEntry.Tx && it.transaction.amountPerspective() == AmountPerspective.NEUTRAL)
            }
            .filter { it.isInScope(scope, ids) }
            .filter { it.matchesAmount(minPaise, maxPaise) }
            .filter { entry -> payee.let { it == null || entry.involves(it.ref) } }
            .filter { TransactionSearch.matches(searchFields[it.stableId()].orEmpty(), query) }

        // Accumulated over the unfiltered list: hidden entries still moved the balance.
        val balances = runningBalances(loadedEntries, ids, openingBalancePaise)
        val visible = filtered.map { it.stableId() }.toSet()

        _uiState.value = AllTransactionsUiState(
            entries = filtered,
            rows = rowsFor(filtered, balances),
            accountLabels = loadedAccountLabels,
            window = window,
            accounts = loadedAccounts,
            scope = scope,
            pendingOnly = pendingOnly,
            thirdPartyOnly = thirdPartyOnly,
            query = query,
            minPaise = minPaise,
            maxPaise = maxPaise,
            payee = payee,
            totalEntryCount = loadedEntries.size,
            showBalance = showBalance,
            openingBalancePaise = openingBalancePaise,
            closingBalancePaise = loadedEntries.firstOrNull()
                ?.let { balances[it.stableId()] }
                ?: openingBalancePaise,
            hasBalance = hasBalance,
            balanceCertainFromEpoch = balanceCertainFromEpoch,
            selectionMode = selectionMode,
            // A filter can hide a row that was ticked. Dropping it keeps what the bar counts and
            // what an action would touch the same as what is on screen.
            selected = selected.intersect(visible).also { selected = it }
        )
    }

    private fun rowsFor(
        entries: List<LedgerEntry>,
        balances: Map<String, Long>
    ): Map<String, TransactionRowInfo> = entries.associate { entry ->
        val key = entry.stableId()
        val balance = balances[key]
        key to TransactionRowInfo(
            title = titles[key].orEmpty(),
            note = when (entry) {
                is LedgerEntry.Tx -> entry.transaction.resolveTypeLabel()
                is LedgerEntry.Transfer -> entry.transfer.resolveRouteLabel(loadedAccountLabels)
            },
            balance = if (showBalance && balance != null) formatRupees(balance) else null,
            // Everything before the first reconciliation is worked out from the transaction record
            // alone, which is the part nobody has ever checked.
            balanceAttr = if (BalanceConfidence.isSpeculative(entry.dateEpoch, balanceCertainFromEpoch)) {
                ThemeAttr.speculative
            } else {
                ThemeAttr.textMuted
            }
        )
    }

    // --- deleting -------------------------------------------------------------------------------

    fun deleteTransaction(transactionId: Long, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { deleteOneTransaction(transactionId) }
            if (failure != null) {
                onError(failure)
                return@launch
            }
            reload()
        }
    }

    fun deleteTransfer(transferId: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { deleteOneTransfer(transferId) }
            if (failure != null) {
                onError(failure)
                return@launch
            }
            reload()
        }
    }

    /**
     * Deletes everything selected, reporting what it could not.
     *
     * Failures are collected rather than aborting the run: a linked refund blocks one row and says so,
     * and stopping there would leave the user guessing which of the rest went.
     */
    fun deleteSelected(onDone: (failures: List<String>) -> Unit) {
        val entries = _uiState.value?.entries?.filter { it.stableId() in selected }.orEmpty()
        if (entries.isEmpty()) return onDone(emptyList())
        viewModelScope.launch {
            val failures = withContext(Dispatchers.IO) {
                entries.mapNotNull { entry ->
                    when (entry) {
                        is LedgerEntry.Tx -> deleteOneTransaction(entry.transaction.id)
                        is LedgerEntry.Transfer -> deleteOneTransfer(entry.transfer.id)
                    }
                }
            }
            exitSelection()
            reload()
            onDone(failures.distinct())
        }
    }

    /**
     * One transaction and its shares. Returns the reason it could not go, or null.
     *
     * A purchase with refunds pointing at it cannot be deleted: the refund is real money that
     * arrived, and its category credit is attributed to this transaction's date. Blocking beats
     * silently dropping the refund or silently turning it into income.
     */
    private suspend fun deleteOneTransaction(transactionId: Long): String? {
        val refundCount = db.transactionDao().getRefundIdsForOriginal(transactionId).size
        if (refundCount > 0) {
            return if (refundCount == 1) {
                "A refund is linked to this transaction. Delete or unlink the refund first."
            } else {
                "$refundCount refunds are linked to this transaction. Delete or unlink them first."
            }
        }
        val chapterId = db.transactionDao().getTransactionById(transactionId)?.chapterId
        db.withTransaction { TransactionRemoval(db).removeInTransaction(transactionId) }
        ChapterPublishing.soonIfShared(appContext, db, listOf(chapterId))
        return null
    }

    private suspend fun deleteOneTransfer(transferId: String): String? = try {
        AccountRepository(db).deleteTransfer(transferId)
        null
    } catch (e: AccountMutationException) {
        e.message ?: "Could not delete transfer"
    }
}

data class FriendDetailUiState(
    val isLoading: Boolean = true,
    val friend: Friend? = null,
    val summary: com.varun.upitracker.data.repository.FriendLedgerSummary? = null,
    /** What the list shows, after the window and the search box. */
    val entries: List<LedgerEntry> = emptyList(),
    /** Per-row title, note and figures, keyed by [stableId]. */
    val rows: Map<String, TransactionRowInfo> = emptyMap(),
    /** This friend's whole history, before either filter, so the bar can say what it hides. */
    val totalCount: Int = 0,
    /** The span on screen: a month, a hand-picked range, or all of it. */
    val window: ListWindow = ListWindow.allTime(),
    val query: String = "",
    /** [stableId]s that can be shared with someone. See [ParcelExportRepository.eligibility]. */
    val shareable: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** How this friend is linked to a DhanMoney account, if at all. */
    val link: com.varun.upitracker.database.entity.FriendLink? = null,
    /** When the newest invite to link sent to them runs out, if one is still on record. */
    val openInviteExpiresEpoch: Long? = null,
    val mailboxOn: Boolean = false,
    /** The balance agreed with them, and any proposal about it waiting on either side. */
    val agreement: com.varun.upitracker.data.declaration.FriendAgreement? = null
) {
    val isFiltered: Boolean get() = !window.isAllTime || query.isNotBlank()

    /** Only the shareable ones: ticking a blocked row would promise something false. */
    val selectedShareableIds: Set<Long>
        get() = entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in selected && it.stableId() in shareable }
            .map { it.transaction.id }
            .toSet()

    val selectedTransactionIds: Set<Long>
        get() = entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in selected }
            .map { it.transaction.id }
            .toSet()

    val canExport: Boolean get() = selectedShareableIds.isNotEmpty()
}

class FriendDetailViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val exportRepository = ParcelExportRepository(db, appContext)
    private val links = com.varun.upitracker.data.mailbox.LinkRepository(appContext, db)
    private val identities = com.varun.upitracker.data.mailbox.MailboxIdentityRepository(appContext, db)
    private val declarations = com.varun.upitracker.data.declaration.DeclarationRepository(appContext, db)
    private val _uiState = MutableLiveData(FriendDetailUiState())
    val uiState: LiveData<FriendDetailUiState> = _uiState

    private var friendId: Long = -1L
    private var loaded: FriendLoad? = null
    private var window: ListWindow = ListWindow.allTime()
    private var query: String = ""
    private var selectionMode: Boolean = false
    private var selected: Set<String> = emptySet()

    /** Everything one load reads, before the window and the search box narrow it. */
    private data class FriendLoad(
        val friend: Friend?,
        val summary: com.varun.upitracker.data.repository.FriendLedgerSummary?,
        val transactions: List<Transaction>,
        val rows: Map<String, TransactionRowInfo>,
        val searchFields: Map<String, List<String?>>,
        val shareable: Set<String>,
        val link: com.varun.upitracker.database.entity.FriendLink?,
        val openInviteExpiresEpoch: Long?,
        val mailboxOn: Boolean,
        val agreement: com.varun.upitracker.data.declaration.FriendAgreement
    )

    fun load(friendId: Long) {
        this.friendId = friendId
        viewModelScope.launch {
            val load = withContext(Dispatchers.IO) { read(friendId) }
            loaded = load
            emitState()
        }
    }

    /**
     * This friend's whole history, once.
     *
     * The window and the search box then filter it in memory. One person's transactions are a bounded
     * set, and the query behind them is a four-way join over `iou_entries` and `transaction_shares`
     * that a date bound would only complicate -- so "All time" costs no reload and typing costs no
     * round trip, exactly as the pending and account filters already work on All Transactions.
     */
    private suspend fun read(friendId: Long): FriendLoad {
        val transactions = db.transactionDao().getUntaggedTransactionsForFriendSync(friendId)
        val eligibility = exportRepository.eligibility(transactions)
        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }

        // One query for the whole list rather than one per row: the old adapter asked per row, on
        // every bind, from the main thread.
        val iouByTransaction = db.iouDao().getAllEntriesForFriend(friendId).groupBy { it.transactionId }
        val summary = LedgerRepository(db).getSummaryForFriend(friendId)
        // Rows at or before the agreed checkpoint are covered by it: they post nothing (D8).
        val coveredUntil = summary?.checkpoint?.asOfEpoch
        val sharesByTransaction = if (transactions.isEmpty()) {
            emptyMap()
        } else {
            db.transactionShareDao()
                .getSharesForTransactions(transactions.map { it.id })
                .groupBy { it.transactionId }
        }

        val rows = HashMap<String, TransactionRowInfo>(transactions.size)
        val fields = HashMap<String, List<String?>>(transactions.size)
        transactions.forEach { tx ->
            val key = LedgerEntry.Tx(tx).stableId()
            rows[key] = friendRow(
                tx = tx,
                title = tx.resolvePrimaryDisplay(friendNames, merchantNames),
                iouEntries = iouByTransaction[tx.id].orEmpty(),
                shares = sharesByTransaction[tx.id].orEmpty(),
                blockedReason = eligibility.blockedReasons[tx.id],
                coveredUntil = coveredUntil
            )
            fields[key] = tx.searchableNames(friendNames, merchantNames) + tx.reason
        }

        return FriendLoad(
            friend = db.friendDao().getFriendById(friendId),
            summary = summary,
            transactions = transactions,
            rows = rows,
            searchFields = fields,
            shareable = transactions.filter { it.id in eligibility.exportable }
                .map { LedgerEntry.Tx(it).stableId() }
                .toSet(),
            link = db.mailboxDao().getLink(friendId),
            openInviteExpiresEpoch = db.mailboxDao().getInvitesForFriend(friendId).firstOrNull()?.expiresEpoch,
            mailboxOn = identities.status() is com.varun.upitracker.data.mailbox.MailboxStatus.On,
            agreement = declarations.agreement(friendId)
        )
    }

    /**
     * What this row did to the balance with this friend, or why it cannot be passed on.
     *
     * The blocked reason wins: while a selection is running that is more use than the IOU state, and
     * out of one it still explains a row the share button will not take.
     */
    private fun friendRow(
        tx: Transaction,
        title: String,
        iouEntries: List<com.varun.upitracker.database.entity.IouEntry>,
        shares: List<com.varun.upitracker.database.entity.TransactionShare>,
        blockedReason: String?,
        coveredUntil: Long?
    ): TransactionRowInfo {
        val share = shares.firstOrNull { it.participantType == ActorType.FRIEND && it.friendId == friendId }
        return when {
            coveredUntil != null && tx.dateEpoch <= coveredUntil -> TransactionRowInfo(
                title = title,
                note = "Covered by the agreement of " +
                    com.varun.upitracker.ui.declaration.AgreementText.day(coveredUntil),
                noteWhenSelecting = blockedReason
            )
            iouEntries.isNotEmpty() -> {
                val amount = iouEntries.sumOf { it.amountPaise }
                TransactionRowInfo(
                    title = title,
                    note = if (iouEntries.all { it.isSettled }) "IOU settled" else "IOU pending",
                    noteWhenSelecting = blockedReason,
                    trailing = when {
                        amount > 0L -> "owes " + AmountFormat.rupees(amount)
                        amount < 0L -> "you owe " + AmountFormat.rupees(-amount)
                        else -> null
                    },
                    trailingAttr = if (amount > 0L) ThemeAttr.positive else ThemeAttr.negative
                )
            }
            share != null -> TransactionRowInfo(
                title = title,
                note = "Friend share",
                noteWhenSelecting = blockedReason,
                trailing = AmountFormat.rupees(share.amountPaise),
                trailingAttr = ThemeAttr.secondary
            )
            else -> TransactionRowInfo(
                title = title,
                note = tx.resolveTypeLabel(),
                noteWhenSelecting = blockedReason
            )
        }
    }

    // --- filters and selection ------------------------------------------------------------------

    fun setWindow(next: ListWindow) {
        if (next == window) return
        window = next
        emitState()
    }

    fun setQuery(text: String) {
        if (text == query) return
        query = text
        emitState()
    }

    fun enterSelection(stableId: String? = null) {
        selectionMode = true
        selected = setOfNotNull(stableId)
        emitState()
    }

    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected = emptySet()
        emitState()
    }

    fun toggleSelection(stableId: String) {
        selected = if (stableId in selected) selected - stableId else selected + stableId
        emitState()
    }

    /** Only what can actually be passed on, capped at what one parcel will carry. */
    fun selectAllShareable() {
        val state = _uiState.value ?: return
        selected = state.entries.map { it.stableId() }
            .filter { it in state.shareable }
            .take(ParcelExportRepository.MAX_TRANSACTIONS)
            .toSet()
        emitState()
    }

    private fun emitState() {
        val load = loaded
        if (load == null) {
            _uiState.value = FriendDetailUiState(isLoading = true)
            return
        }
        val entries = load.transactions
            .filter { window.isAllTime || it.dateEpoch in window }
            .filter { TransactionSearch.matches(load.searchFields[LedgerEntry.Tx(it).stableId()].orEmpty(), query) }
            .map(LedgerEntry::Tx)
        val visible = entries.map { it.stableId() }.toSet()

        _uiState.value = FriendDetailUiState(
            isLoading = false,
            friend = load.friend,
            summary = load.summary,
            entries = entries,
            rows = load.rows,
            totalCount = load.transactions.size,
            window = window,
            query = query,
            shareable = load.shareable,
            selectionMode = selectionMode,
            // A filter can hide a ticked row; dropping it keeps the count honest about what an
            // action would actually touch.
            selected = selected.intersect(visible).also { selected = it },
            link = load.link,
            openInviteExpiresEpoch = load.openInviteExpiresEpoch,
            mailboxOn = load.mailboxOn,
            agreement = load.agreement
        )
    }

    // --- linking ----------------------------------------------------------------------------------

    /**
     * The code of an invite to link with [friendId]: the one still open, or a new one when [fresh]
     * or when there is none.
     */
    fun inviteCode(friendId: Long, fresh: Boolean, onReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val code = try {
                (if (fresh) null else links.openInviteCode(friendId)) ?: links.createInvite(friendId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                return@launch onError(error.message ?: "Could not make an invite.")
            }
            load(friendId)
            onReady(code)
        }
    }

    fun withdrawInvites(friendId: Long) {
        viewModelScope.launch {
            links.withdrawInvites(friendId)
            load(friendId)
        }
    }

    /**
     * Only the link, the invite and whether the mailbox is on. Cheap enough for every resume, and it
     * leaves the transaction list alone, so coming back does not jump it to the top.
     */
    fun refreshLink(friendId: Long) {
        viewModelScope.launch {
            val current = loaded ?: return@launch
            val link = withContext(Dispatchers.IO) { db.mailboxDao().getLink(friendId) }
            val inviteExpires = withContext(Dispatchers.IO) {
                db.mailboxDao().getInvitesForFriend(friendId).firstOrNull()?.expiresEpoch
            }
            val mailboxOn = identities.status() is com.varun.upitracker.data.mailbox.MailboxStatus.On
            // An answer collected in the background, or given in the inbox, changes this too.
            val agreement = declarations.agreement(friendId)
            val balanceMoved = agreement.checkpoint?.id != current.agreement.checkpoint?.id
            loaded = current.copy(
                link = link,
                openInviteExpiresEpoch = inviteExpires,
                mailboxOn = mailboxOn,
                agreement = agreement
            )
            // A new checkpoint moves the balance and which rows it covers: that needs the full read.
            if (balanceMoved) load(friendId) else emitState()
        }
    }

    /** Leaves the link in place, and says why, when the server could not be told. */
    fun unlink(friendId: Long, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                links.unlink(friendId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "Could not unlink.")
            }
            load(friendId)
        }
    }

    // --- sharing and deleting ---------------------------------------------------------------------

    fun deleteTransaction(friendId: Long, transactionId: Long, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { deleteOne(transactionId) }
            if (failure != null) {
                onError(failure)
                return@launch
            }
            load(friendId)
        }
    }

    /** See AllTransactionsViewModel.deleteSelected: failures are collected, not aborted on. */
    fun deleteSelected(onDone: (failures: List<String>) -> Unit) {
        val ids = _uiState.value?.selectedTransactionIds.orEmpty()
        if (ids.isEmpty()) return onDone(emptyList())
        viewModelScope.launch {
            val failures = withContext(Dispatchers.IO) { ids.mapNotNull { deleteOne(it) } }
            exitSelection()
            load(friendId)
            onDone(failures.distinct())
        }
    }

    /**
     * Mirrors AllTransactionsViewModel.deleteOneTransaction: a refund pointing at this transaction
     * must be dealt with first, same as everywhere else transactions are deleted.
     */
    private suspend fun deleteOne(transactionId: Long): String? {
        val refundCount = db.transactionDao().getRefundIdsForOriginal(transactionId).size
        if (refundCount > 0) {
            return if (refundCount == 1) {
                "A refund is linked to this transaction. Delete or unlink the refund first."
            } else {
                "$refundCount refunds are linked to this transaction. Delete or unlink them first."
            }
        }
        val chapterId = db.transactionDao().getTransactionById(transactionId)?.chapterId
        db.withTransaction { TransactionRemoval(db).removeInTransaction(transactionId) }
        ChapterPublishing.soonIfShared(appContext, db, listOf(chapterId))
        return null
    }
}

data class AliasMappingsCardItem(
    val id: Long,
    val title: String,
    val rawNames: List<AliasMappingsValueItem>,
    val upiIds: List<AliasMappingsValueItem>
)

data class AliasMappingsValueItem(val id: Long, val value: String)

data class AliasMappingsUiState(val cards: List<AliasMappingsCardItem> = emptyList())

class AliasMappingsViewModel(context: Context) : ViewModel() {
    private val repository = SettingsRepository(context.applicationContext)
    private val _uiState = MutableLiveData(AliasMappingsUiState())
    val uiState: LiveData<AliasMappingsUiState> = _uiState

    fun load(mode: String) {
        viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) {
                val cards = if (mode == AliasMappingsActivity.MODE_MERCHANT) {
                    repository.getMerchantAliasBundles().map { bundle ->
                        AliasMappingsCardItem(
                            id = bundle.merchant.id,
                            title = bundle.merchant.name,
                            rawNames = bundle.rawNames.sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds.sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.upiId) }
                        )
                    }
                } else {
                    repository.getFriendAliasBundles().map { bundle ->
                        AliasMappingsCardItem(
                            id = bundle.friend.id,
                            title = bundle.friend.name,
                            rawNames = bundle.rawNames.sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds.sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingsValueItem(it.id, it.upiId) }
                        )
                    }
                }
                AliasMappingsUiState(cards)
            }
        }
    }
}

class ScreenViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(TransactionEntryViewModel::class.java) -> TransactionEntryViewModel(context) as T
            modelClass.isAssignableFrom(AllTransactionsViewModel::class.java) -> AllTransactionsViewModel(context) as T
            modelClass.isAssignableFrom(FriendDetailViewModel::class.java) -> FriendDetailViewModel(context) as T
            modelClass.isAssignableFrom(AliasMappingsViewModel::class.java) -> AliasMappingsViewModel(context) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
