package com.varun.upitracker.domain.parcel

import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect

/**
 * A batch of transactions one person hands to another, already written from the reader's point of
 * view: the writer is [ParcelActor.Sender] throughout and the reader is [ParcelActor.Me].
 *
 * Writing it flipped rather than shipping the sender's own rows plus a "swap these two" note keeps
 * the awkward reasoning in one place -- [ParcelPerspective.flipForRecipient] -- instead of leaving
 * every reader of the format to work out whose "ME" a given row means.
 *
 * Three versions exist across the two routes:
 *  - **3** is pasted through a chat app. Nothing in it identifies anyone; the reader says who sent it.
 *    **1** is the same without [ParcelTransaction.iouRecovery], and is still read from older apps.
 *  - **2** only ever travels inside a signed, encrypted mailbox envelope, whose verified sender is
 *    what says who wrote it. Each row carries its own [ParcelTransaction.shareRef], and people the
 *    writer sent the same rows to can be named by account as [ParcelActor.Linked].
 */
data class Parcel(
    val version: Int,
    /**
     * Pasted parcels only: an opaque token the writer keeps per recipient, qualifying
     * [ParcelTransaction.sourceId] so two senders' row 41s cannot collide. Deliberately not a name:
     * the reader says who sent this. Null in version 2, where every row carries its own reference.
     */
    val originToken: String?,
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

    /**
     * A third party named by the account they linked with, as well as by name. Only a version 2
     * parcel carries one, and only for someone the writer sent the same rows to, so an account is
     * never shown to anyone who was not sent the transaction too.
     *
     * The reader resolves [uid] through their own links and nothing else: linking was an explicit,
     * two-sided act, which a matching name is not. Unlinked on the reader's side, it is a [Person].
     */
    data class Linked(val uid: String, val name: String) : ParcelActor

    /** A shop. The reader's alias tables may or may not know it. */
    data class Shop(val name: String) : ParcelActor

    /** A counterparty the writer's app never classified, carried so the row still reads. */
    data class Unnamed(val label: String) : ParcelActor
}

data class ParcelShare(
    /** "PAYER" or "PAYEE", matching [com.varun.upitracker.database.entity.TransactionShare.side]. */
    val side: String,
    /** Only [ParcelActor.Me], [ParcelActor.Sender], [ParcelActor.Person] and [ParcelActor.Linked] mean anything here. */
    val participant: ParcelActor,
    val amountPaise: Long,
    /**
     * Which of this share's IOUs the writer kept, applied by the reader as they stand -- see
     * [com.varun.upitracker.database.entity.TransactionShare.keepPayeeLeg]. Always both from a
     * version 1 parcel, which predates the choice.
     */
    val keepPayeeLeg: Boolean = true,
    val keepPayerLeg: Boolean = true
)

data class ParcelTransaction(
    /**
     * Pasted: the writer's own `transactions.id`, meaningful only with the parcel's origin token.
     * The mailbox's version 2 does not carry it, and it reads back as 0.
     */
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
    val shares: List<ParcelShare>,
    /**
     * Version 2: the writer's [com.varun.upitracker.database.entity.Transaction.shareRef], the same
     * for every friend the row is sent to. Null when pasted.
     */
    val shareRef: String? = null,
    /**
     * Version 2: the reference this row would have landed under had the writer pasted it to this
     * reader, when they ever pasted one -- so a row already imported by paste is recognised rather
     * than arriving twice. Null when there is none.
     */
    val legacyRef: String? = null,
    /**
     * Which side of the split decides who pays whom back: the writer's choice, which the reader applies
     * as it stands, since a copy recovering from the other side would disagree about who owes what.
     * Null only when read from a version 1 parcel, which predates it.
     */
    val iouRecovery: IouRecovery? = null
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
