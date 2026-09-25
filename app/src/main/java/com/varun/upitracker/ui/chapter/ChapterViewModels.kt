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
import com.varun.upitracker.domain.chapter.ChapterEligibility
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterParty
import com.varun.upitracker.domain.chapter.ChapterResult
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.resolvePrimaryDisplay
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

/** A line of the plan, or of the balances, with the names already resolved. */
data class ChapterPartyRow(
    val label: String,
    val amountPaise: Long,
    val involvesMe: Boolean
)

data class ChapterPaymentRow(
    val debtorLabel: String,
    val creditorLabel: String,
    val amountPaise: Long,
    val involvesMe: Boolean
)

data class ChapterDetailUiState(
    val isLoading: Boolean = true,
    val chapter: Chapter? = null,
    val members: List<Friend> = emptyList(),
    val plan: List<ChapterPaymentRow> = emptyList(),
    val balances: List<ChapterPartyRow> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val settled: Boolean = false,
    val pendingCount: Int = 0,
    /** Who still owes whom, as sentences, for the warning on closing an unsettled chapter. */
    val outstanding: List<String> = emptyList()
)

class ChapterDetailViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)

    private val _uiState = MutableStateFlow(ChapterDetailUiState())
    val uiState: StateFlow<ChapterDetailUiState> = _uiState.asStateFlow()

    fun load(chapterId: Long) {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val chapter = db.chapterDao().getById(chapterId)
                    ?: return@withContext ChapterDetailUiState(isLoading = false)
                val memberIds = db.chapterDao().memberIds(chapterId)
                val members = memberIds.mapNotNull { db.friendDao().getFriendById(it) }
                val names = members.associate { it.id to it.name }
                val result: ChapterResult = repository.resultFor(chapterId)

                fun label(party: ChapterParty): String = when (party) {
                    is ChapterParty.Me -> "You"
                    is ChapterParty.Friend -> names[party.friendId] ?: "Friend ${party.friendId}"
                }

                ChapterDetailUiState(
                    isLoading = false,
                    chapter = chapter,
                    members = members,
                    // Rows involving the user first: they are the ones that touch their own money.
                    plan = result.plan
                        .map {
                            ChapterPaymentRow(
                                debtorLabel = label(it.debtor),
                                creditorLabel = label(it.creditor),
                                amountPaise = it.amountPaise,
                                involvesMe = it.debtor is ChapterParty.Me || it.creditor is ChapterParty.Me
                            )
                        }
                        .sortedByDescending { it.involvesMe },
                    balances = result.nets.map { (party, net) ->
                        ChapterPartyRow(label(party), net, party is ChapterParty.Me)
                    },
                    transactions = db.chapterDao().taggedTransactions(chapterId),
                    settled = result.settled,
                    pendingCount = result.pendingCount,
                    outstanding = result.plan.map {
                        "${label(it.debtor)} owes ${label(it.creditor)} ${formatRupees(it.amountPaise)}"
                    }
                )
            }
            _uiState.value = state
        }
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

    fun untag(chapterId: Long, transactionId: Long, onError: (String) -> Unit) =
        run(chapterId, onError) { repository.setChapterFor(transactionId, null) }

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
