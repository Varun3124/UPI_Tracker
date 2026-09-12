package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.LedgerEffect

/**
 * A batch of transactions one person hands to another, already written from the reader's point of
 * view: the writer is [ParcelActor.Sender] throughout and the reader is [ParcelActor.Me].
 *
 * Writing it flipped rather than shipping the sender's own rows plus a "swap these two" note keeps
 * the awkward reasoning in one place -- [ParcelPerspective.flipForRecipient] -- instead of leaving
 * every reader of the format to work out whose "ME" a given row means.
 */
data class Parcel(
    val version: Int,
    /**
     * Opaque token the writer keeps per recipient, qualifying [ParcelTransaction.sourceId] so two
     * senders' row 41s cannot collide. Deliberately not a name: the reader says who sent this.
     */
    val originToken: String,
    val transactions: List<ParcelTransaction>
)

/** A person or shop as a parcel names them. */
sealed interface ParcelActor {

    /** Whoever imports this parcel. */
    data object Me : ParcelActor

    /** Whoever wrote it. The importer decides which of their friends that is. */
    data object Sender : ParcelActor

    /**
     * Someone the writer split with who is neither party. Carried by name only, and never resolved
     * to a friend automatically -- see [com.varun.upitracker.database.entity.TransactionShare.rawLabel].
     */
    data class Person(val name: String) : ParcelActor

    /** A shop. The reader's alias tables may or may not know it. */
    data class Shop(val name: String) : ParcelActor

    /** A counterparty the writer's app never classified, carried so the row still reads. */
    data class Unnamed(val label: String) : ParcelActor
}

data class ParcelShare(
    /** "PAYER" or "PAYEE", matching [com.varun.upitracker.database.entity.TransactionShare.side]. */
    val side: String,
    /** Only [ParcelActor.Me], [ParcelActor.Sender] and [ParcelActor.Person] are meaningful here. */
    val participant: ParcelActor,
    val amountPaise: Long
)

data class ParcelTransaction(
    /** The writer's own `transactions.id`, meaningful only with the parcel's origin token. */
    val sourceId: Long,
    val dateEpoch: Long,
    val amountPaise: Long,
    val payer: ParcelActor,
    val payee: ParcelActor,
    val ledgerEffect: LedgerEffect,
    /**
     * Set only where both parties genuinely share it -- a direct transfer between them, where both
     * banks issue the same reference. On a merchant row it is the writer's private reference and is
     * dropped.
     */
    val upiRefId: String?,
    val reason: String?,
    val shares: List<ParcelShare>
)

/**
 * Strict on purpose, unlike [com.varun.upitracker.domain.statistics.parseAccountScope], which falls
 * back to a default because a stale screen preference is not worth a crash. A malformed parcel is
 * money, so any bad row rejects the whole batch and says which line was wrong.
 */
sealed interface ParcelDecodeResult {
    data class Ok(val parcel: Parcel) : ParcelDecodeResult
    data class Failed(val reason: String) : ParcelDecodeResult
}
