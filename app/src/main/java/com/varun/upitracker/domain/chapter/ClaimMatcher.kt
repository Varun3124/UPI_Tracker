package com.varun.upitracker.domain.chapter

/** The references one snapshot row carries, and whether it counts yet. */
data class ClaimRow(
    /** The owner's own reference for the row: what a member who received it from the owner holds it under. */
    val shareRef: String?,
    /** What the row would have landed under had the owner pasted it to this member. */
    val legacyRef: String?,
    /** The owner's copy's `sharedRefId`, when the owner got the row from somebody. */
    val sourceRef: String?,
    val pending: Boolean
)

/** One of this phone's own transactions, reduced to what a claim can match it by. */
data class LocalRow(val id: Long, val shareRef: String?, val sharedRefId: String?)

/** Which of this phone's rows are worth reading to match a snapshot: by `shareRef`, by `sharedRefId`, by id. */
data class ClaimLookups(val shareRefs: Set<String>, val sharedRefIds: Set<String>, val ids: Set<Long>)

/**
 * Which of this phone's transactions are the rows of a friend's shared chapter (S5).
 *
 * Only ever by reference: the same transaction, passed between phones by mailbox or paste, carries a
 * reference both sides can name. Never by amount, date or name -- two dinners of the same amount on
 * the same night are two dinners, and claiming the wrong one would take a real debt out of the book.
 *
 * A row still pending on the owner's phone is not claimed: it counts for nothing in the owner's plan
 * yet, and taking this phone's copy out of the direct ledger would leave it counted nowhere.
 */
object ClaimMatcher {

    private const val MAILBOX_PREFIX = "mbx:"

    /**
     * The ids of [mine] that are rows of the snapshot, each claimed at most once.
     *
     * [ownerUid] and [myUid] are the two accounts, when known; [myPasteTokenForOwner] is the token this
     * phone puts on parcels it pastes to the owner, when it ever has.
     */
    fun match(
        rows: List<ClaimRow>,
        mine: List<LocalRow>,
        ownerUid: String?,
        myUid: String?,
        myPasteTokenForOwner: String?
    ): Set<Long> {
        val byShareRef = mine.filter { it.shareRef != null }.associateBy { it.shareRef!! }
        val bySharedRefId = mine.filter { it.sharedRefId != null }.associateBy { it.sharedRefId!! }
        val byId = mine.associateBy { it.id }

        val claimed = linkedSetOf<Long>()
        rows.filter { !it.pending }.forEach { row ->
            val hit = row.sourceRef?.let { source -> fromSource(source, myUid, myPasteTokenForOwner, byShareRef, bySharedRefId, byId) }
                ?: row.shareRef?.let { ref -> ownerUid?.let { bySharedRefId["$MAILBOX_PREFIX$it:$ref"] } }
                ?: row.legacyRef?.let { bySharedRefId[it] }
            hit?.let { claimed += it.id }
        }
        return claimed
    }

    /**
     * Every reference [match] could look a row of [rows] up by, so only those rows are read -- a phone
     * holds far more rows than any one chapter names.
     */
    fun lookups(rows: List<ClaimRow>, ownerUid: String?, myUid: String?, myPasteTokenForOwner: String?): ClaimLookups {
        val shareRefs = linkedSetOf<String>()
        val sharedRefIds = linkedSetOf<String>()
        val ids = linkedSetOf<Long>()
        rows.filter { !it.pending }.forEach { row ->
            row.sourceRef?.let { source ->
                when {
                    source.startsWith(MAILBOX_PREFIX) -> {
                        val body = source.removePrefix(MAILBOX_PREFIX)
                        val ref = body.substringAfter(':', "")
                        if (body.substringBefore(':', "") == myUid && ref.isNotEmpty()) shareRefs += ref else sharedRefIds += source
                    }
                    myPasteTokenForOwner != null && source.startsWith("$myPasteTokenForOwner.") ->
                        source.removePrefix("$myPasteTokenForOwner.").toLongOrNull()?.let(ids::add)
                    else -> sharedRefIds += source
                }
            }
            if (ownerUid != null) row.shareRef?.let { sharedRefIds += "$MAILBOX_PREFIX$ownerUid:$it" }
            row.legacyRef?.let(sharedRefIds::add)
        }
        return ClaimLookups(shareRefs, sharedRefIds, ids)
    }

    /**
     * A row the owner received from someone. From this phone: it is this phone's own row, known by
     * its `shareRef` or -- when it was pasted -- by its id under this phone's token. From anyone else:
     * this phone holds it, if at all, under the very same reference.
     */
    private fun fromSource(
        source: String,
        myUid: String?,
        myPasteTokenForOwner: String?,
        byShareRef: Map<String, LocalRow>,
        bySharedRefId: Map<String, LocalRow>,
        byId: Map<Long, LocalRow>
    ): LocalRow? {
        if (source.startsWith(MAILBOX_PREFIX)) {
            val body = source.removePrefix(MAILBOX_PREFIX)
            val uid = body.substringBefore(':', "")
            val ref = body.substringAfter(':', "")
            if (uid.isEmpty() || ref.isEmpty()) return null
            return if (uid == myUid) byShareRef[ref] else bySharedRefId[source]
        }
        if (myPasteTokenForOwner != null && source.startsWith("$myPasteTokenForOwner.")) {
            return source.removePrefix("$myPasteTokenForOwner.").toLongOrNull()?.let(byId::get)
        }
        return bySharedRefId[source]
    }
}
