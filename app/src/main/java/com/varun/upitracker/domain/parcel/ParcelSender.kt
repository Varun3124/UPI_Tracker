package com.varun.upitracker.domain.parcel

/** Who a pasted parcel's rows will land as coming from. */
sealed interface SenderChoice {

    /** A friend already in this person's list, and what put them forward. */
    data class Existing(val friendId: Long, val how: How) : SenderChoice

    /** Nobody in the list matches: a new person by this name is added when the rows are saved. */
    data class New(val name: String) : SenderChoice

    /** A parcel from an app before aliases, which names nobody. The reader has to say. */
    data object Unknown : SenderChoice

    enum class How {
        /** Earlier parcels carrying the same origin token were saved under this friend. */
        REMEMBERED,
        /** The sender's UPI ID is one this friend already pays from. */
        UPI_ID,
        /** The only friend with exactly the sender's alias. */
        NAME,
        /** The reader chose them, or the mailbox verified them. */
        PICKED
    }
}

/**
 * Works out who sent a pasted parcel, so the reader is not asked first.
 *
 * Strongest evidence first. The origin token is stable per writer and per reader, so whoever its
 * earlier parcels were saved under is who the reader already decided it was. A UPI ID is the next
 * best thing: this person's own SMS have already tied it to a friend. A name is only a suggestion,
 * and only when exactly one friend has it. Anything weaker becomes a new person, whom the reader can
 * still point at a friend instead before saving -- every row lands pending either way.
 *
 * Lookups are lambdas, the seam [ParcelPerspective] uses, so this stays pure and under test. Each
 * returns null for "no such friend", including a remembered one who has since been deleted.
 */
object ParcelSender {

    fun resolve(
        parcel: Parcel,
        rememberedFriendId: (originToken: String) -> Long?,
        friendByUpiId: (upiId: String) -> Long?,
        friendByName: (name: String) -> Long?
    ): SenderChoice {
        parcel.originToken?.let(rememberedFriendId)?.let { return SenderChoice.Existing(it, SenderChoice.How.REMEMBERED) }
        val alias = parcel.senderAlias ?: return SenderChoice.Unknown
        parcel.senderUpiId?.let(friendByUpiId)?.let { return SenderChoice.Existing(it, SenderChoice.How.UPI_ID) }
        friendByName(alias)?.let { return SenderChoice.Existing(it, SenderChoice.How.NAME) }
        return SenderChoice.New(alias)
    }
}
