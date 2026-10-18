package com.varun.upitracker.data.repository

import androidx.room.withTransaction
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.dao.clearDraft
import com.varun.upitracker.database.entity.AccountTransfer
import com.varun.upitracker.database.entity.AccountTransferType
import com.varun.upitracker.database.entity.EntryDraft
import com.varun.upitracker.database.entity.EntryDraftCategory
import com.varun.upitracker.database.entity.EntryDraftRow
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.domain.transactionSource

/** A pended form as it is read back: the form, its share rows in order, and its checked categories. */
data class EntryDraftSnapshot(
    val draft: EntryDraft,
    val rows: List<EntryDraftRow>,
    val categories: List<EntryDraftCategory>
)

/** Why Pend could not keep this form, worded for the user. */
class PendException(message: String) : Exception(message)

/**
 * Pend: keeping the entry screen exactly as it is, however unfinished, against a pending row.
 *
 * Nothing about the form is checked or resolved -- no friend or merchant is created from a name half
 * typed, no split has to add up -- because none of it is used until the row is confirmed. What the
 * row itself holds is only ever what a confirming save wrote, or what an import said; Pend changes
 * nothing about it but that it is pending. The one row it does write is a brand-new entry's, so that
 * something stands in the lists for the form to be reopened from.
 */
class EntryDraftRepository(private val db: AppDatabase) {

    suspend fun load(transactionId: Long): EntryDraftSnapshot? {
        val draft = db.entryDraftDao().get(transactionId) ?: return null
        return EntryDraftSnapshot(
            draft = draft,
            rows = db.entryDraftDao().getRows(transactionId),
            categories = db.entryDraftDao().getCategories(transactionId)
        )
    }

    /**
     * Saves [form] against the row the screen is editing and returns that row's id.
     *
     * - [existing] null and [transfer] null: a new entry. [shell] is inserted as its pending row.
     * - [transfer]: a saved transfer being pended. It becomes a pending transaction -- transfers
     *   have no pending state -- keeping the references every importer dedupes on.
     * - [existing] pending: left as it is, unless it is a shell Pend wrote before, which takes [shell]'s
     *   new figures so the lists keep up with the form.
     * - [existing] confirmed: taken out of the books the way a revert does, and marked pending.
     *
     * [form]'s transaction ids are ignored; every part is stamped with the row's.
     *
     * @throws PendException for an FD return, which only the Accounts screen may change.
     * @throws ChapterException for a row in a closed chapter (R8).
     */
    suspend fun pend(
        existing: Transaction?,
        transfer: AccountTransfer?,
        shell: Transaction,
        form: EntryDraftSnapshot
    ): Long = db.withTransaction {
        ChapterRepository(db).assertNotLockedByClosedChapter(existing?.chapterId)
        val transactionId = when {
            existing == null && transfer != null -> replaceTransfer(transfer, shell)
            existing == null -> db.transactionDao().insert(shell)
            !existing.isPending -> {
                takeOutOfBooks(existing)
                existing.id
            }
            isShell(existing) -> {
                db.transactionDao().update(
                    existing.copy(
                        amountPaise = shell.amountPaise,
                        payerActorType = shell.payerActorType,
                        payerFriendId = shell.payerFriendId,
                        payerMerchantId = shell.payerMerchantId,
                        payerRawLabel = shell.payerRawLabel,
                        payeeActorType = shell.payeeActorType,
                        payeeFriendId = shell.payeeFriendId,
                        payeeMerchantId = shell.payeeMerchantId,
                        payeeRawLabel = shell.payeeRawLabel,
                        reason = shell.reason,
                        myAccountId = shell.myAccountId,
                        dateEpoch = shell.dateEpoch
                    )
                )
                existing.id
            }
            else -> existing.id
        }

        db.entryDraftDao().clearDraft(transactionId)
        db.entryDraftDao().insert(form.draft.copy(transactionId = transactionId))
        if (form.rows.isNotEmpty()) {
            db.entryDraftDao().insertRows(form.rows.map { it.copy(id = 0, transactionId = transactionId) })
        }
        if (form.categories.isNotEmpty()) {
            db.entryDraftDao().insertCategories(form.categories.map { it.copy(transactionId = transactionId) })
        }
        transactionId
    }

    /**
     * A row Pend made for a new entry: typed in by hand and never confirmed, so it has no shares or
     * splits. An imported pending row is never one -- its amount, date and raw label are the bank's.
     */
    private suspend fun isShell(tx: Transaction): Boolean =
        tx.isPending && tx.source == MANUAL &&
            db.transactionShareDao().getSharesForTransaction(tx.id).isEmpty() &&
            db.categorySplitDao().getForTransaction(tx.id).isEmpty()

    private suspend fun replaceTransfer(transfer: AccountTransfer, shell: Transaction): Long {
        if (transfer.type == AccountTransferType.FD_RETURN) {
            throw PendException("Edit FD returns from the Accounts screen.")
        }
        // Before the insert: both rows would otherwise claim the same UPI reference at once, and the
        // SMS scan treats a reference on either table as already recorded.
        db.accountTransferDao().deleteById(transfer.id)
        return db.transactionDao().insert(
            shell.copy(
                source = transfer.source.transactionSource(),
                upiRefId = transfer.upiRefId,
                statementRefNo = transfer.statementRefNo
            )
        )
    }

    /**
     * What [ReceivedOriginalRepository.revert] does to put a row back to pending: the friends it
     * named are rebuilt without it and its chapter works its plan out again. Its shares, splits and
     * chapter stay as last confirmed -- the draft is where the unfinished version lives.
     */
    private suspend fun takeOutOfBooks(tx: Transaction) {
        val chapters = ChapterRepository(db)
        // Read while the row and its entries are still here: they are how we know who to rebuild.
        val friends = chapters.baseLedgerFriendsOf(tx)
        val chapterIds = setOfNotNull(tx.chapterId)
        val before = chapters.contributionsOf(chapterIds)

        db.transactionDao().update(tx.copy(isPending = true))
        db.iouDao().deleteForTransaction(tx.id)

        val absorbed = chapters.recomputeAbsorbingInTransaction(chapterIds, before, listOf(tx.dateEpoch))
        chapters.replayInTransaction(friends + absorbed)
    }

    private companion object {
        const val MANUAL = "MANUAL"
    }
}
