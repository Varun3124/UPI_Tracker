package com.varun.upitracker.ui.chapter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.varun.upitracker.data.chapter.ChapterRecipient
import com.varun.upitracker.data.chapter.ReplicaBook
import com.varun.upitracker.data.chapter.SharedChapterException
import com.varun.upitracker.data.chapter.SharedChapterRepository
import com.varun.upitracker.data.repository.ChapterException
import com.varun.upitracker.data.repository.ChapterRepository
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterShareMode
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.data.repository.ParcelExportRepository
import com.varun.upitracker.domain.chapter.ChapterEligibility
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterParty
import com.varun.upitracker.domain.chapter.ChapterResult
import com.varun.upitracker.domain.chapter.ReplicaMath
import com.varun.upitracker.domain.chapter.ReplicaParty
import com.varun.upitracker.domain.chapter.SnapshotPayment
import com.varun.upitracker.domain.parcel.LocalParcelRow
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.maintenance.ChapterPublishing
import com.varun.upitracker.ui.AmountPerspective
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.TransactionRowInfo
import com.varun.upitracker.ui.resolvePrimaryDisplay
import com.varun.upitracker.ui.resolveTypeLabel
import com.varun.upitracker.ui.stableId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a copy of a friend's chapter stands, in a word or two. See docs/declarations-design.md S7. */
internal fun copyStatusLabel(mode: String): String = when (mode) {
    ChapterShareMode.REPLICA -> "live"
    ChapterShareMode.FROZEN -> "frozen"
    else -> "copy"
}

/** One chapter as the list draws it. */
data class ChapterRowUiState(
    val chapter: Chapter,
    val memberCount: Int,
    /** ME's net in this chapter. Positive means the group owes the user. */
    val myNetPaise: Long,
    val settled: Boolean,
    val pendingCount: Int,
    /** "Active", "Shared", or whose chapter it is: "Alice's · live". Null when there is nothing to say. */
    val badge: String? = null
)

data class ChaptersUiState(
    val isLoading: Boolean = true,
    val chapters: List<ChapterRowUiState> = emptyList(),
    val friends: List<Friend> = emptyList()
)

class ChaptersViewModel(context: Context) : ViewModel() {

    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)
    private val sharing = SharedChapterRepository(context.applicationContext, db)

    private val _uiState = MutableStateFlow(ChaptersUiState())
    val uiState: StateFlow<ChaptersUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val friends = db.friendDao().getAllFriendsSync()
                val names = friends.associate { it.id to it.name }
                val copies = ReplicaBook(db)
                val chapters = db.chapterDao().getAll().map { chapter ->
                    if (chapter.isOwn) ownRow(chapter) else copyRow(chapter, copies, names)
                }
                ChaptersUiState(isLoading = false, chapters = chapters, friends = friends)
            }
            _uiState.value = state
        }
    }

    private suspend fun ownRow(chapter: Chapter): ChapterRowUiState {
        val result = repository.resultFor(chapter.id)
        return ChapterRowUiState(
            chapter = chapter,
            memberCount = db.chapterDao().memberCount(chapter.id),
            myNetPaise = result.nets[ChapterParty.Me] ?: 0L,
            settled = result.settled,
            pendingCount = result.pendingCount,
            badge = listOfNotNull(
                "Active".takeIf { chapter.isActive },
                "Shared".takeIf { chapter.mode == ChapterShareMode.SHARED }
            ).joinToString(" · ").ifEmpty { null }
        )
    }

    /**
     * A friend's chapter reads as they last sent it -- their nets, their plan -- never as the rows this
     * phone happens to hold would work out (S3).
     */
    private fun copyRow(chapter: Chapter, copies: ReplicaBook, names: Map<Long, String>): ChapterRowUiState {
        val snapshot = copies.snapshotOf(chapter)
        val owner = chapter.ownerFriendId?.let(names::get) ?: "A friend"
        val pending = snapshot?.extras?.count { it.pending } ?: 0
        return ChapterRowUiState(
            chapter = chapter,
            // Everyone but the user: the same count a chapter of the user's own shows.
            memberCount = ((snapshot?.members?.size ?: 1) - 1).coerceAtLeast(0),
            myNetPaise = snapshot?.nets?.firstOrNull { it.party == ParcelActor.Me }?.amountPaise ?: 0L,
            settled = snapshot == null || (snapshot.nets.isEmpty() && pending == 0),
            pendingCount = pending,
            badge = "$owner's · ${copyStatusLabel(chapter.mode)}"
        )
    }

    fun create(name: String, memberIds: Set<Long>, notes: String?, onError: (String) -> Unit) {
        run(onError) { repository.create(name, memberIds, notes) }
    }

    /** One of the user's own goes for everyone who holds a copy (S7); a copy goes only from here. */
    fun delete(chapterId: Long, onError: (String) -> Unit) {
        run(onError) {
            val chapter = db.chapterDao().getById(chapterId) ?: return@run
            if (chapter.isOwn) sharing.deleteOwn(chapterId) else sharing.removeCopy(chapterId)
        }
    }

    private fun run(onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: ChapterException) {
                onError(e.message ?: "That did not work")
                return@launch
            } catch (e: SharedChapterException) {
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

/** One payment of a friend's plan, as a sentence and an amount whose colour says which way it goes for the user. */
data class ChapterPlanLine(
    val text: String,
    val amountPaise: Long,
    val direction: AmountPerspective
)

/** One of the owner's people in a copy of their chapter, and who they are here (S4). */
data class CopyPerson(
    /** What a mapping is stored under: see [ReplicaMath.personKey]. */
    val key: String,
    /** As the owner wrote them. */
    val name: String,
    val friendId: Long?,
    val friendName: String?,
    /** Placed by a link the two made, not by hand: there is nothing to choose. */
    val byLink: Boolean
)

/** A payment with the user naming someone nobody has placed yet: shown, and not counted. */
data class UnplacedLine(val personKey: String, val text: String)

/** What a copy of a friend's chapter shows beyond what every chapter does. */
data class CopyInfo(
    val ownerFriendId: Long,
    val ownerName: String,
    val mode: String,
    /** When the owner made the copy this phone holds. */
    val asOfEpoch: Long,
    /** Rows left out to keep the copy small. The owner's plan still counts them. */
    val omittedRows: Int,
    val plan: List<ChapterPlanLine>,
    val unplaced: List<UnplacedLine>,
    val people: List<CopyPerson>,
    /** The user's own row behind a row of the copy, keyed by the copy row's [stableId]. */
    val localIds: Map<String, Long>,
    /** How many of the user's own rows sit in the copy, and go back to the direct ledger with it. */
    val claimedCount: Int
) {
    /** A copy kept up to date stays while it is shared (S7). */
    val canRemove: Boolean get() = mode != ChapterShareMode.REPLICA
}

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
    /** Everyone in it but the user. For a friend's chapter, everyone the owner named. */
    val memberCount: Int = 0,
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
    val shareable: Set<String> = emptySet(),
    /** Set for a friend's chapter, which this phone only reads. */
    val copy: CopyInfo? = null
) {
    val isFiltered: Boolean get() = friendFilterId != null || merchantFilterId != null

    val selectedTransactionIds: Set<Long>
        get() = entries.filterIsInstance<LedgerEntry.Tx>()
            .filter { it.stableId() in selected && it.stableId() in shareable }
            .map { it.transaction.id }
            .toSet()
}

class ChapterDetailViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context)
    private val repository = ChapterRepository(db)
    private val sharing = SharedChapterRepository(appContext, db)
    private val exportRepository = ParcelExportRepository(db, appContext)

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
        val memberCount: Int,
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
        val outstanding: List<String>,
        val copy: CopyInfo? = null
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
        return if (chapter.isOwn) readOwn(chapter) else readCopy(chapter)
    }

    private suspend fun readOwn(chapter: Chapter): ChapterLoad {
        val chapterId = chapter.id
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
            memberCount = members.size,
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

    /**
     * A friend's chapter, read from the snapshot they last sent: their nets, their plan and the rows
     * they sent, each marked with whether the user holds it too (S3-S6). The rows are drawn the way the
     * user's own are -- turned into transactions that are never saved, with ids no saved row can have.
     */
    private suspend fun readCopy(chapter: Chapter): ChapterLoad? {
        val ownerFriendId = chapter.ownerFriendId ?: return null
        val copies = ReplicaBook(db)
        val snapshot = copies.snapshotOf(chapter) ?: return null

        val friends = db.friendDao().getAllFriendsSync()
        val friendNames = friends.associate { it.id to it.name }
        val merchants = db.merchantDao().getAllMerchantsSync()
        val merchantNames = merchants.associate { it.id to it.name }
        val merchantByName = merchants.associate { it.name.trim().lowercase() to it.id }
        val chapterNames = db.chapterDao().getAll().associate { it.id to it.name }
        val ownerName = friendNames[ownerFriendId] ?: "Friend $ownerFriendId"

        val resolve = copies.resolver(chapter.id, ownerFriendId)
        val nameOf = copies.namer(chapter, friendNames)
        val mapped = db.chapterDao().peopleFor(chapter.id).associate { it.personKey to it.friendId }
        val linked = db.mailboxDao().getAllLinks().associate { it.uid to it.friendId }
        val mine = sharing.localCopies(chapter, snapshot)

        val landed: List<LocalParcelRow> = snapshot.rows.mapIndexed { index, row ->
            val mapPerson = { name: String -> ReplicaMath.personKey(ParcelActor.Person(name))?.let(mapped::get) }
            val resolveShop = { name: String -> merchantByName[name.trim().lowercase()] }
            val local = if (row.shareRef != null) {
                ParcelPerspective.toLocalFromMailbox(
                    row, ownerFriendId, senderUid = "copy", mapPerson = mapPerson,
                    resolveLinked = { uid -> linked[uid] ?: mapped["uid:$uid"] },
                    resolveShop = resolveShop, carryUpiRefId = false
                )
            } else {
                ParcelPerspective.toLocal(row, ownerFriendId, "copy", mapPerson, resolveShop, carryUpiRefId = false)
            }
            local.copy(
                transaction = local.transaction.copy(
                    id = -(index + 1L),
                    isPending = snapshot.extras[index].pending,
                    chapterId = chapter.id
                )
            )
        }
        val order = landed.indices.sortedWith(
            compareByDescending<Int> { landed[it].transaction.dateEpoch }.thenBy { it }
        )
        val transactions = order.map { landed[it].transaction }

        fun marker(index: Int): String {
            val own = mine[index]
            return when {
                own != null && own.chapterId == chapter.id -> "In your book"
                own != null && own.chapterId != null ->
                    "Also in ${chapterNames[own.chapterId] ?: "another chapter"} in your book: take it out there, or it counts twice"
                snapshot.extras[index].pending -> "Awaiting review on $ownerName's phone"
                own != null -> "In your book, outside this chapter"
                else -> "Only in $ownerName's book"
            }
        }

        val friendsByTransaction = landed.associate { it.transaction.id to ChapterMath.friendsIn(it.transaction, it.shares) }
        val merchantByTransaction = landed.mapNotNull { row ->
            (row.transaction.payeeMerchantId ?: row.transaction.payerMerchantId)?.let { row.transaction.id to it }
        }.toMap()

        val people = snapshot.members.filter { it != ParcelActor.Me && it != ParcelActor.Sender }.mapNotNull { actor ->
            val key = ReplicaMath.personKey(actor) ?: return@mapNotNull null
            val placed = (resolve(actor) as? ReplicaParty.Friend)?.friendId
            CopyPerson(
                key = key,
                name = when (actor) {
                    is ParcelActor.Person -> actor.name
                    is ParcelActor.Linked -> actor.name
                    else -> key
                },
                friendId = placed,
                friendName = placed?.let(friendNames::get),
                byLink = actor is ParcelActor.Linked && linked[actor.uid] != null
            )
        }
        val unplaced = ReplicaMath.balances(snapshot.plan, resolve).unresolved.map { payment ->
            val who = payment.person.name
            val what = if (payment.amountPaise > 0L) {
                "$who owes you ${formatRupees(payment.amountPaise)}"
            } else {
                "You owe $who ${formatRupees(-payment.amountPaise)}"
            }
            UnplacedLine(payment.person.key, "$what. Not counted until you say who $who is.")
        }
        val pending = snapshot.extras.count { it.pending }

        return ChapterLoad(
            chapter = chapter,
            members = emptyList(),
            memberCount = (snapshot.members.size - 1).coerceAtLeast(0),
            balances = snapshot.nets.map { ChapterPartyRow(nameOf(it.party), it.amountPaise, it.party == ParcelActor.Me) },
            transactions = transactions,
            rows = order.associate { index ->
                val tx = landed[index].transaction
                LedgerEntry.Tx(tx).stableId() to TransactionRowInfo(
                    title = tx.resolvePrimaryDisplay(friendNames, merchantNames),
                    note = marker(index)
                )
            },
            friendsByTransaction = friendsByTransaction,
            merchantByTransaction = merchantByTransaction,
            friendOptions = optionsFor(friendsByTransaction.values.flatten().groupingBy { it }.eachCount(), friendNames),
            merchantOptions = optionsFor(merchantByTransaction.values.groupingBy { it }.eachCount(), merchantNames),
            // Read-only: nothing here is the user's to pass on or take out.
            shareable = emptySet(),
            settled = snapshot.nets.isEmpty() && pending == 0,
            pendingCount = pending,
            outstanding = snapshot.plan.map { sentence(it, nameOf) },
            copy = CopyInfo(
                ownerFriendId = ownerFriendId,
                ownerName = ownerName,
                mode = chapter.mode,
                asOfEpoch = snapshot.sentEpoch,
                omittedRows = snapshot.omittedRows,
                plan = snapshot.plan.map { planLine(it, nameOf) },
                unplaced = unplaced,
                people = people,
                localIds = landed.indices.mapNotNull { index ->
                    mine[index]?.let { LedgerEntry.Tx(landed[index].transaction).stableId() to it.id }
                }.toMap(),
                claimedCount = db.chapterDao().countTaggedTransactions(chapter.id)
            )
        )
    }

    private fun sentence(payment: SnapshotPayment, nameOf: (ParcelActor) -> String): String {
        val creditor = if (payment.creditor == ParcelActor.Me) "you" else nameOf(payment.creditor)
        val verb = if (payment.debtor == ParcelActor.Me) "owe" else "owes"
        return "${nameOf(payment.debtor)} $verb $creditor ${formatRupees(payment.amountPaise)}"
    }

    private fun planLine(payment: SnapshotPayment, nameOf: (ParcelActor) -> String): ChapterPlanLine = when {
        payment.debtor == ParcelActor.Me ->
            ChapterPlanLine("You pay ${nameOf(payment.creditor)}", payment.amountPaise, AmountPerspective.OUTGOING)
        payment.creditor == ParcelActor.Me ->
            ChapterPlanLine("${nameOf(payment.debtor)} pays you", payment.amountPaise, AmountPerspective.INCOMING)
        else ->
            ChapterPlanLine("${nameOf(payment.debtor)} pays ${nameOf(payment.creditor)}", payment.amountPaise, AmountPerspective.NEUTRAL)
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
            memberCount = load.memberCount,
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
            shareable = load.shareable,
            copy = load.copy
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

    /**
     * The screen finishes on success, so there is nothing to reload -- only an error to report. Every
     * member holding a copy is told first, so theirs goes too (S7).
     */
    fun delete(chapterId: Long, onDeleted: () -> Unit, onError: (String) -> Unit) =
        finishing(onDeleted, onError) { sharing.deleteOwn(chapterId) }

    /** Friends who could be added, i.e. everyone not already in. */
    suspend fun addableFriends(chapterId: Long): List<Friend> = withContext(Dispatchers.IO) {
        val members = db.chapterDao().memberIds(chapterId).toSet()
        db.friendDao().getAllFriendsSync().filterNot { it.id in members }
    }

    // --- sharing: the owner's side ---------------------------------------------------------------

    /** S1: sends it to every linked member, then says who has it and who still needs a copy pasting. */
    fun share(chapterId: Long, onDone: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val recipients = try {
                withContext(Dispatchers.IO) {
                    sharing.share(chapterId)
                    sharing.recipients(chapterId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "Could not share it.")
                return@launch
            }
            load(chapterId)
            onDone(sharedMessage(recipients))
        }
    }

    private fun sharedMessage(recipients: List<ChapterRecipient>): String {
        val sent = recipients.filter { it.linked && it.upToDate }.map { it.name }
        val later = recipients.filter { it.linked && !it.upToDate }.map { it.name }
        val unlinked = recipients.filter { !it.linked }.map { it.name }
        return buildString {
            append(if (sent.isEmpty()) "Shared." else "Shared with ${sent.joinToString(", ")}.")
            if (later.isNotEmpty()) append(" ${later.joinToString(", ")}: at the next mailbox check.")
            if (unlinked.isNotEmpty()) {
                append(" Not linked with you: ${unlinked.joinToString(", ")}. Send them a copy to paste instead.")
            }
        }
    }

    /** Members' copies freeze where they stand and keep counting (S7). */
    fun stopSharing(chapterId: Long, onError: (String) -> Unit) =
        runSharing(chapterId, onError) { sharing.stopSharing(chapterId) }

    suspend fun recipients(chapterId: Long): List<ChapterRecipient> = sharing.recipients(chapterId)

    /** A copy for [friendId] to paste, which never updates. Throws with a reason fit to show. */
    suspend fun pastedCopyFor(chapterId: Long, friendId: Long): String = sharing.pastedCopyFor(chapterId, friendId)

    /**
     * Whatever changed on this screen goes to the chapter's members in the background, now that the
     * user is leaving it (S2). A chapter nobody else holds sends nothing.
     */
    fun publishIfShared() {
        val chapter = loaded?.chapter ?: return
        if (chapter.isOwn && chapter.mode == ChapterShareMode.SHARED) ChapterPublishing.soon(appContext)
    }

    // --- a friend's chapter --------------------------------------------------------------------------

    /** S4: says who one of the owner's people is here, or, with a null [friendId], that nobody is. */
    fun mapPerson(chapterId: Long, personKey: String, friendId: Long?, onError: (String) -> Unit) =
        runSharing(chapterId, onError) { sharing.mapPerson(chapterId, personKey, friendId) }

    /** Friends someone in a friend's chapter could be: anyone but the owner, who is already counted. */
    suspend fun friendsForMapping(ownerFriendId: Long): List<Friend> = withContext(Dispatchers.IO) {
        db.friendDao().getAllFriendsSync().filter { it.id != ownerFriendId }.sortedBy { it.name.lowercase() }
    }

    /** S7: only a copy that no longer updates. */
    fun removeCopy(chapterId: Long, onRemoved: () -> Unit, onError: (String) -> Unit) =
        finishing(onRemoved, onError) { sharing.removeCopy(chapterId) }

    // --- plumbing ------------------------------------------------------------------------------

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

    /** [run] for what reaches the mailbox, which fails in more ways than a local change does. */
    private fun runSharing(chapterId: Long, onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "That did not work")
                return@launch
            }
            load(chapterId)
        }
    }

    private fun finishing(onDone: () -> Unit, onError: (String) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "That did not work")
                return@launch
            }
            onDone()
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
