package com.varun.upitracker.domain.dedup

import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.ui.ActorType
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * How loosely the import screens look for a row that may already be recorded -- a bank statement's
 * row, or one a friend shared. Set in Settings.
 *
 * Loose on purpose: a false match costs one tap to untick, a missed one a duplicate in the books. Two
 * records of one payment rarely agree exactly. The bank's value date lags the payment and falls
 * either side of midnight; a friend's phone timestamps its own SMS; a shop rounds, or adds a small fee.
 * So the date can be [daysEitherSide] calendar days off, and the amount [amountTolerancePaise] either
 * way. Which side of the payment ME is on is never loosened: a payment out cannot be a payment in.
 */
data class DuplicateRules(
    val daysEitherSide: Int = DEFAULT_DAYS,
    val amountTolerancePaise: Long = DEFAULT_TOLERANCE_PAISE,
    /** Only offer records naming the same friend or shop as the row. Off: the name is a hint, not a rule. */
    val sameCounterpartyOnly: Boolean = false,
    /** Statements only: only offer records on the statement's own account, or on none yet. */
    val sameAccountOnly: Boolean = false,
    /**
     * Leave out records already carrying a different UPI or statement reference: those are another
     * payment's, which two records of one payment never are.
     */
    val skipOtherReferences: Boolean = true
) {

    /** The amounts a record may have and still be offered, never below zero. */
    fun amountRange(amountPaise: Long): LongRange =
        (amountPaise - amountTolerancePaise).coerceAtLeast(0L)..(amountPaise + amountTolerancePaise)

    /**
     * From the start of the day [daysEitherSide] before [epoch]'s, to the end of the day as many
     * after -- in calendar days, so zero still means "the same day" for a timestamp late in it.
     */
    fun dateRange(epoch: Long, timeZone: TimeZone = TimeZone.getDefault()): LongRange {
        val start = Calendar.getInstance(timeZone).apply {
            timeInMillis = epoch
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -daysEitherSide)
        }.timeInMillis
        val end = Calendar.getInstance(timeZone).apply {
            timeInMillis = epoch
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, daysEitherSide + 1)
        }.timeInMillis - 1
        return start..end
    }

    companion object {
        const val DEFAULT_DAYS = 1
        const val DEFAULT_TOLERANCE_PAISE = 1_000L

        /** A week either side is already loose enough to offer most of a month's spending. */
        const val MAX_DAYS = 7

        /** ₹1000: past that, "the same payment" stops meaning anything. */
        const val MAX_TOLERANCE_PAISE = 100_000L

        /** Null when the two numbers can be kept; otherwise why not, fit to show. */
        fun problemWith(daysEitherSide: Int, amountTolerancePaise: Long): String? = when {
            daysEitherSide !in 0..MAX_DAYS -> "Days either side has to be from 0 to $MAX_DAYS."
            amountTolerancePaise !in 0..MAX_TOLERANCE_PAISE -> "The amount can differ by at most ₹1000."
            else -> null
        }
    }
}

/** The checks the import screens share, kept apart from the database so they stay under test. */
object DuplicateMatching {

    private const val PAYER = "PAYER"
    private const val PAYEE = "PAYEE"
    private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)

    /**
     * Whether [candidate] already holds another payment's reference -- a UPI or statement reference
     * that is not this row's. The bank gives one payment one reference, so it cannot be this one.
     */
    fun carriesOtherReference(upiRefId: String?, statementRefNo: String?, candidate: Transaction): Boolean =
        (upiRefId != null && candidate.upiRefId != null && candidate.upiRefId != upiRefId) ||
            (statementRefNo != null && candidate.statementRefNo != null && candidate.statementRefNo != statementRefNo)

    /** The sides of [tx] ME is on: an end, or a share row on that side. */
    fun meSides(tx: Transaction, shares: List<TransactionShare>): Set<String> = buildSet {
        if (tx.payerActorType == ActorType.ME || shares.any { it.side == PAYER && it.participantType == ActorType.ME }) add(PAYER)
        if (tx.payeeActorType == ActorType.ME || shares.any { it.side == PAYEE && it.participantType == ActorType.ME }) add(PAYEE)
    }

    /**
     * Nearest first: the counterparty agreeing, then how far off the amount is, then the date -- so
     * the record most likely to be this one sits at the top of what is offered.
     */
    fun <T> closestFirst(
        amountPaise: Long,
        epoch: Long,
        transactionOf: (T) -> Transaction,
        counterpartyAgrees: (T) -> Boolean
    ): Comparator<T> =
        compareByDescending<T> { counterpartyAgrees(it) }
            .thenBy { abs(transactionOf(it).amountPaise - amountPaise) }
            .thenBy { abs(transactionOf(it).dateEpoch - epoch) / DAY_MILLIS }
            .thenBy { transactionOf(it).dateEpoch }
}
