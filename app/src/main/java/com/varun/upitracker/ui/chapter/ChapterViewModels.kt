package com.varun.upitracker.ui.chapter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.repository.ChapterException
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.domain.chapter.ChapterEligibility
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterParty
import com.varun.upitracker.domain.chapter.ChapterResult
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.TransactionRowInfo
import com.varun.upitracker.ui.resolvePrimaryDisplay
import com.varun.upitracker.ui.resolveTypeLabel
import com.varun.upitracker.ui.stableId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One chapter as the list draws it. */
data class ChapterRowUiState(
    val chapter: Chapter,
    val memberCount: Int,
    /** ME's net in this chapter. Positive means the group owes the user. */
    val myNetPaise: Long,
    val settled: Boolean,
    val pendingCount: Int
)

data class ChaptersUiState(
    val isLoading: Boolean = true,
    val chapters: List<ChapterRowUiState> = emptyList(),
    val friends: List<Friend> = emptyList()
)

class ChaptersViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)

    private val _uiState = MutableStateFlow(ChaptersUiState())
    val uiState: StateFlow<ChaptersUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val friends = db.friendDao().getAllFriendsSync()
                val chapters = db.chapterDao().getAll().map { chapter ->
                    val result = repository.resultFor(chapter.id)
                    ChapterRowUiState(
                        chapter = chapter,
                        memberCount = db.chapterDao().memberCount(chapter.id),
                        myNetPaise = result.nets[ChapterParty.Me] ?: 0L,
                        settled = result.settled,
                        pendingCount = result.pendingCount
                    )
                }
                ChaptersUiState(isLoading = false, chapters = chapters, friends = friends)
            }
            _uiState.value = state
        }
    }

    fun create(name: String, memberIds: Set<Long>, notes: String?, onError: (String) -> Unit) {
        run(onError) { repository.create(name, memberIds, notes) }
    }

    fun delete(chapterId: Long, onError: (String) -> Unit) {
        run(onError) { repository.delete(chapterId) }
    }

    private fun run(onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: ChapterException) {
                onError(e.message ?: "That did not work")
                return@launch
            }
            load()
        }
    }
}

/** A line of the balances, with the names already resolved. */
data class ChapterPartyRow(
    val label: String,
    val amountPaise: Long,
    val involvesMe: Boolean
)

/** One choice offered by the friend or merchant filter, with how many rows it would leave. */
data class ChapterFilterOption(
    val id: Long,
    val name: String,
    val count: Int
)

data class ChapterDetailUiState(
    val isLoading: Boolean = true,
    val chapter: Chapter? = null,
    val members: List<Friend> = emptyList(),
    val balances: List<ChapterPartyRow> = emptyList(),
    /** What the list shows, after the two filters. */
    val entries: List<LedgerEntry> = emptyList(),
    /** Per-row title, note and figures, keyed by [stableId]. */
    val rows: Map<String, TransactionRowInfo> = emptyMap(),
    /** Everything tagged here, before either filter, so the bar can say what it hides. */
    val totalCount: Int = 0,
    /** Friends appearing in this chapter's transactions, for the filter list. */
    val friendOptions: List<ChapterFilterOption> = emptyList(),
    /** Merchants appearing in this chapter's transactions, for the filter list. */
    val merchantOptions: List<ChapterFilterOption> = emptyList(),
    val friendFilterId: Long? = null,
    val merchantFilterId: Long? = null,
    val settled: Boolean = false,
    val pendingCount: Int = 0,
    /** Who still owes whom, as sentences, for the warning on closing an unsettled chapter. */
    val outstanding: List<String> = emptyList(),
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** Rows that can be passed on to someone; see [ParcelExportRepository.eligibility]. */
    val shareable: Set<String> = emptySet()
) {
    val isFiltered: Boolean get() = friendFilterId != null || merchantFilterId != null

    val selectedTransactionIds: Set<Long>
        get() = entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in selected && it.stableId() in shareable }
            .map { it.transaction.id }
            .toSet()
}

class ChapterDetailViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)
    private val exportRepository = ParcelExportRepository(db, context.applicationContext)

    private val _uiState = MutableStateFlow(ChapterDetailUiState())
    val uiState: StateFlow<ChapterDetailUiState> = _uiState.asStateFlow()

    private var loaded: ChapterLoad? = null
    private var friendFilterId: Long? = null
    private var merchantFilterId: Long? = null
    private var selectionMode: Boolean = false
    private var selected: Set<String> = emptySet()

    /** Everything one load reads, before the filters narrow it. */
    private data class ChapterLoad(
        val chapter: Chapter,
        val members: List<Friend>,
        val balances: List<ChapterPartyRow>,
        val transactions: List<Transaction>,
        val rows: Map<String, TransactionRowInfo>,
        /** Which friends and which merchant each transaction names, for the two filters. */
        val friendsByTransaction: Map<Long, Set<Long>>,
        val merchantByTransaction: Map<Long, Long>,
        val friendOptions: List<ChapterFilterOption>,
        val merchantOptions: List<ChapterFilterOption>,
        val shareable: Set<String>,
        val settled: Boolean,
        val pendingCount: Int,
        val outstanding: List<String>
    )

    fun load(chapterId: Long) {
        viewModelScope.launch {
            val load = withContext(Dispatchers.IO) { read(chapterId) }
            loaded = load
            if (load == null) {
                _uiState.value = ChapterDetailUiState(isLoading = false)
                return@launch
            }
            // A filter can name someone a reload no longer has, so both are validated before they
            // are applied rather than silently emptying the list.
            if (load.friendOptions.none { it.id == friendFilterId }) friendFilterId = null
            if (load.merchantOptions.none { it.id == merchantFilterId }) merchantFilterId = null
            emitState()
        }
    }

    private suspend fun read(chapterId: Long): ChapterLoad? {
        val chapter = db.chapterDao().getById(chapterId) ?: return null
        val members = db.chapterDao().memberIds(chapterId).mapNotNull { db.friendDao().getFriendById(it) }
        val result: ChapterResult = repository.resultFor(chapterId)

        val friendNames = db.friendDao().getAllFriendsSync().associate { it.id to it.name }
        val merchantNames = db.merchantDao().getAllMerchantsSync().associate { it.id to it.name }
        fun label(party: ChapterParty): String = when (party) {
            is ChapterParty.Me -> "You"
            is ChapterParty.Friend -> friendNames[party.friendId] ?: "Friend ${party.friendId}"
        }

        val transactions = db.chapterDao().taggedTransactions(chapterId)
        val sharesByTransaction = if (transactions.isEmpty()) {
            emptyMap()
        } else {
            db.transactionShareDao()
                .getSharesForTransactions(transactions.map { it.id })
                .groupBy { it.transactionId }
        }

        val friendsByTransaction = transactions.associate { tx ->
            tx.id to ChapterMath.friendsIn(tx, sharesByTransaction[tx.id].orEmpty())
        }
        // A chapter row holds at most one shop: a transaction has two ends, and a merchant at one of
        // them is what makes it spending rather than a transfer between people.
        val merchantByTransaction = transactions.mapNotNull { tx ->
            (tx.payeeMerchantId ?: tx.payerMerchantId)?.let { tx.id to it }
        }.toMap()

        val eligibility = exportRepository.eligibility(transactions)
        return ChapterLoad(
            chapter = chapter,
            members = members,
            balances = result.nets.map { (party, net) ->
                ChapterPartyRow(label(party), net, party is ChapterParty.Me)
            },
            transactions = transactions,
            rows = transactions.associate { tx ->
                LedgerEntry.Tx(tx).stableId() to TransactionRowInfo(
                    title = tx.resolvePrimaryDisplay(friendNames, merchantNames),
                    note = chapterNote(tx),
                    noteWhenSelecting = eligibility.blockedReasons[tx.id]
                )
            },
            friendsByTransaction = friendsByTransaction,
            merchantByTransaction = merchantByTransaction,
            friendOptions = optionsFor(
                friendsByTransaction.values.flatten().groupingBy { it }.eachCount(), friendNames
            ),
            merchantOptions = optionsFor(
                merchantByTransaction.values.groupingBy { it }.eachCount(), merchantNames
            ),
            shareable = transactions.filter { it.id in eligibility.exportable }
                .map { LedgerEntry.Tx(it).stableId() }
                .toSet(),
            settled = result.settled,
            pendingCount = result.pendingCount,
            outstanding = result.plan.map {
                "${label(it.debtor)} owes ${label(it.creditor)} ${formatRupees(it.amountPaise)}"
            }
        )
    }

    /** The second line: the note the user wrote, else what kind of row it is. */
    private fun chapterNote(tx: Transaction): String = when {
        tx.isPending -> "Awaiting review"
        !tx.reason.isNullOrBlank() -> tx.reason!!
        else -> tx.resolveTypeLabel()
    }

    /** Named, counted, and ordered by how much of the chapter each one accounts for. */
    private fun optionsFor(
        counts: Map<Long, Int>,
        names: Map<Long, String>
    ): List<ChapterFilterOption> =
        counts.map { (id, count) -> ChapterFilterOption(id, names[id] ?: "#$id", count) }
            .sortedWith(compareByDescending<ChapterFilterOption> { it.count }.thenBy { it.name })

    // --- filters and selection ------------------------------------------------------------------

    fun setFriendFilter(friendId: Long?) {
        if (friendId == friendFilterId) return
        friendFilterId = friendId
        emitState()
    }

    fun setMerchantFilter(merchantId: Long?) {
        if (merchantId == merchantFilterId) return
        merchantFilterId = merchantId
        emitState()
    }

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

    /** Only what can actually be passed on: ticking a blocked row would promise something false. */
    fun selectAllShareable() {
        val state = _uiState.value
        selected = state.entries.map { it.stableId() }
            .filter { it in state.shareable }
            .take(ParcelExportRepository.MAX_TRANSACTIONS)
            .toSet()
        emitState()
    }

    private fun emitState() {
        val load = loaded ?: return
        val friendId = friendFilterId
        val merchantId = merchantFilterId
        val entries = load.transactions
            .filter { friendId == null || friendId in load.friendsByTransaction[it.id].orEmpty() }
            .filter { merchantId == null || load.merchantByTransaction[it.id] == merchantId }
            .map(LedgerEntry::Tx)
        val visible = entries.map { it.stableId() }.toSet()

        _uiState.value = ChapterDetailUiState(
            isLoading = false,
            chapter = load.chapter,
            members = load.members,
            balances = load.balances,
            entries = entries,
            rows = load.rows,
            totalCount = load.transactions.size,
            friendOptions = load.friendOptions,
            merchantOptions = load.merchantOptions,
            friendFilterId = friendId,
            merchantFilterId = merchantId,
            settled = load.settled,
            pendingCount = load.pendingCount,
            outstanding = load.outstanding,
            selectionMode = selectionMode,
            // A filter can hide a ticked row; dropping it keeps the count honest about what an
            // action would actually touch.
            selected = selected.intersect(visible).also { selected = it },
            shareable = load.shareable
        )
    }

    fun rename(chapterId: Long, name: String, notes: String?, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.rename(chapterId, name, notes) }

    fun close(chapterId: Long, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.close(chapterId) }

    fun reopen(chapterId: Long, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.reopen(chapterId) }

    fun setActive(chapterId: Long, active: Boolean, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.setActive(if (active) chapterId else null) }

    fun addMembers(chapterId: Long, friendIds: Set<Long>, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.addMembers(chapterId, friendIds) }

    fun removeMember(chapterId: Long, friendId: Long, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.removeMember(chapterId, friendId) }

    /**
     * Takes rows out of the chapter, reporting which it could not and why.
     *
     * Collected rather than aborting on the first refusal: a refund follows the purchase it reverses
     * and will always be turned down, and stopping there would leave the user guessing what happened
     * to the rest of the selection.
     */
    fun untagAll(chapterId: Long, transactionIds: Set<Long>, onDone: (failures: List<String>) -> Unit) {
        if (transactionIds.isEmpty()) return onDone(emptyList())
        viewModelScope.launch {
            val failures = withContext(Dispatchers.IO) {
                transactionIds.mapNotNull { id ->
                    try {
                        repository.setChapterFor(id, null)
                        null
                    } catch (e: ChapterException) {
                        e.message ?: "That did not work"
                    }
                }
            }
            exitSelection()
            load(chapterId)
            onDone(failures.distinct())
        }
    }

    /** The screen finishes on success, so there is nothing to reload -- only an error to report. */
    fun delete(chapterId: Long, onDeleted: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(chapterId) }
            } catch (e: ChapterException) {
                onError(e.message ?: "That did not work")
                return@launch
            }
            onDeleted()
        }
    }

    /** Friends who could be added, i.e. everyone not already in. */
    suspend fun addableFriends(chapterId: Long): List<Friend> = withContext(Dispatchers.IO) {
        val members = db.chapterDao().memberIds(chapterId).toSet()
        db.friendDao().getAllFriendsSync().filterNot { it.id in members }
    }

    private fun run(chapterId: Long, onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: ChapterException) {
                onError(e.message ?: "That did not work")
                return@launch
            }
            load(chapterId)
        }
    }
}

/** One candidate for the bulk picker, with the reason it cannot be taken when there is one. */
data class TaggableRowUiState(
    val transaction: Transaction,
    val title: String,
    val blockedReason: String?
)

data class ChapterAddUiState(
    val isLoading: Boolean = true,
    val chapter: Chapter? = null,
    val rows: List<TaggableRowUiState> = emptyList(),
    val fromEpoch: Long = 0L,
    val toEpoch: Long = 0L
)

/**
 * The bulk picker: untagged transactions a chapter could take, over a date range.
 *
 * Offers rows involving a member, and also rows with no friend in them at all -- a chapter is meant
 * to read as a complete record of a trip, and solo spending is part of that even though it moves no
 * balance.
 */
class ChapterAddViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)

    private val _uiState = MutableStateFlow(ChapterAddUiState())
    val uiState: StateFlow<ChapterAddUiState> = _uiState.asStateFlow()

    fun load(chapterId: Long, fromEpoch: Long, toEpoch: Long) {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val chapter = db.chapterDao().getById(chapterId)
                    ?: return@withContext ChapterAddUiState(isLoading = false)
                val memberIds = db.chapterDao().memberIds(chapterId).toSet()
                val candidates = db.transactionDao().getTransactionsBetweenSync(fromEpoch, toEpoch)
                    .filter { it.chapterId == null && it.refundsTransactionId == null }

                val sharesByTransaction = if (candidates.isEmpty()) {
                    emptyMap()
                } else {
                    db.transactionShareDao()
                        .getSharesForTransactions(candidates.map { it.id })
                        .groupBy { it.transactionId }
                }

                val rows = candidates.mapNotNull { tx ->
                    val shares = sharesByTransaction[tx.id].orEmpty()
                    val friends = ChapterMath.friendsIn(tx, shares)
                    // Either it involves someone already here, or it involves nobody at all.
                    if (friends.isNotEmpty() && friends.none { it in memberIds }) return@mapNotNull null
                    TaggableRowUiState(
                        transaction = tx,
                        title = tx.resolvePrimaryDisplay(db),
                        blockedReason = ChapterEligibility.blockedReason(tx, shares, chapter, null)
                    )
                }.sortedWith(compareByDescending<TaggableRowUiState> { it.transaction.dateEpoch }
                    .thenByDescending { it.transaction.id })

                ChapterAddUiState(
                    isLoading = false,
                    chapter = chapter,
                    rows = rows,
                    fromEpoch = fromEpoch,
                    toEpoch = toEpoch
                )
            }
            _uiState.value = state
        }
    }

    /**
     * Tags everything selected, reporting who had to be added to the chapter to make it work (R5).
     */
    fun tagAll(
        chapterId: Long,
        transactionIds: Set<Long>,
        onDone: (added: List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val added = try {
                withContext(Dispatchers.IO) {
                    val before = db.chapterDao().memberIds(chapterId).toSet()
                    transactionIds.forEach { repository.setChapterFor(it, chapterId) }
                    val after = db.chapterDao().memberIds(chapterId).toSet()
                    (after - before).mapNotNull { db.friendDao().getFriendById(it)?.name }
                }
            } catch (e: ChapterException) {
                onError(e.message ?: "That did not work")
                return@launch
            }
            onDone(added)
        }
    }
}

/** Mirrors ScreenViewModelFactory: one factory for the three chapter screens. */
class ChapterViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChaptersViewModel::class.java) ->
            ChaptersViewModel(context.applicationContext) as T
        modelClass.isAssignableFrom(ChapterDetailViewModel::class.java) ->
            ChapterDetailViewModel(context.applicationContext) as T
        modelClass.isAssignableFrom(ChapterAddViewModel::class.java) ->
            ChapterAddViewModel(context.applicationContext) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
