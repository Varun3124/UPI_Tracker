package com.varun.upitracker.domain.chapter

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.domain.parcel.ParcelActor
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.domain.parcel.ParcelTransaction

/** Everything about one of this phone's chapters that goes into a snapshot, whoever it is for. */
class SnapshotSource(
    val chapter: Chapter,
    /** What `ChapterMath` works out on this phone: the plan every member uses. */
    val result: ChapterResult,
    val memberIds: Set<Long>,
    val rows: List<TaggedTx>,
    /** This phone's own reference for each row, minted before any snapshot goes out. */
    val shareRefOf: (Long) -> String,
    val friendName: (Long) -> String?,
    val merchantName: (Long) -> String?,
    /**
     * Members the chapter goes to, by the account each linked with: people sent the same thing may
     * be named to each other by account, and nobody else is (the rule parcels follow).
     */
    val receivingUids: Map<Long, String>,
    val sentEpoch: Long
)

/**
 * Writes one of this phone's chapters from one member's seat: this phone is [ParcelActor.Sender], the
 * member is [ParcelActor.Me], exactly as a parcel is flipped. See docs/declarations-design.md S3.
 */
object SnapshotBuilder {

    /** Rows sent at most; more than a trip ever holds, and small enough for one mailbox message. */
    const val MAX_ROWS = 300

    /**
     * [pasteTokenForRecipient] is the token this phone uses when pasting to them, when it ever has:
     * each row then also carries the reference a pasted copy would have landed under.
     */
    fun build(
        source: SnapshotSource,
        recipientFriendId: Long,
        hint: SnapshotHint?,
        pasteTokenForRecipient: String?
    ): ChapterSnapshot {
        val shareId = requireNotNull(source.chapter.shareId) { "Only a shared chapter has a snapshot." }
        fun actorFor(party: ChapterParty): ParcelActor = when (party) {
            ChapterParty.Me -> ParcelActor.Sender
            is ChapterParty.Friend -> actorForFriend(source, party.friendId, recipientFriendId)
        }

        val others = source.memberIds.filter { it != recipientFriendId }.sorted()
        val members = listOf(ParcelActor.Sender, ParcelActor.Me) +
            others.map { actorForFriend(source, it, recipientFriendId) }

        // The member's own rows first: those are the ones their phone may hold a copy of to claim.
        val shareable = source.rows.filter { ChapterMath.friendsIn(it.transaction, it.shares).isNotEmpty() }
        val ordered = shareable.sortedWith(
            compareByDescending<TaggedTx> { recipientFriendId in ChapterMath.friendsIn(it.transaction, it.shares) }
                .thenByDescending { it.transaction.dateEpoch }
                .thenByDescending { it.transaction.id }
        )

        val rows = mutableListOf<ParcelTransaction>()
        val extras = mutableListOf<SnapshotRowExtra>()
        ordered.forEach { tagged ->
            if (rows.size >= MAX_ROWS) return@forEach
            val tx = tagged.transaction
            val row = ParcelPerspective.flipForRecipient(
                transaction = tx,
                shares = tagged.shares,
                recipientFriendId = recipientFriendId,
                friendName = source.friendName,
                merchantName = source.merchantName,
                linkedUidOf = { source.receivingUids[it] }
            ).copy(
                sourceId = 0L,
                shareRef = source.shareRefOf(tx.id),
                legacyRef = pasteTokenForRecipient?.let { ParcelPerspective.sharedRefIdFor(it, tx.id) }
            )
            // Two ends that read the same from their seat say nothing a parcel can carry; the parcel
            // grammar refuses such a row, and it would take the whole snapshot down with it.
            if (row.payer == row.payee) return@forEach
            rows += row
            extras += SnapshotRowExtra(pending = tx.isPending, sourceRef = tx.sharedRefId)
        }

        return ChapterSnapshot(
            shareId = shareId,
            version = source.chapter.shareVersion,
            state = source.chapter.state,
            name = source.chapter.name.take(ChapterSnapshotFormat.MAX_NAME_CHARS),
            notes = source.chapter.notes?.take(ChapterSnapshotFormat.MAX_NOTES_CHARS),
            sentEpoch = source.sentEpoch,
            members = members,
            nets = source.result.nets.map { (party, amount) -> SnapshotNet(actorFor(party), amount) },
            plan = source.result.plan.map { SnapshotPayment(actorFor(it.debtor), actorFor(it.creditor), it.amountPaise) },
            hint = hint,
            rows = rows,
            extras = extras,
            omittedRows = shareable.size - rows.size
        )
    }

    private fun actorForFriend(source: SnapshotSource, friendId: Long, recipientFriendId: Long): ParcelActor {
        if (friendId == recipientFriendId) return ParcelActor.Me
        val name = source.friendName(friendId)?.takeIf { it.isNotBlank() } ?: "Someone"
        return source.receivingUids[friendId]?.let { ParcelActor.Linked(it, name) } ?: ParcelActor.Person(name)
    }
}
