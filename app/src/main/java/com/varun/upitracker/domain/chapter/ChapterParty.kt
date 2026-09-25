package com.varun.upitracker.domain.chapter

import com.varun.upitracker.domain.iou.IouParty

/**
 * Someone a chapter can hold a balance for.
 *
 * Unlike [IouParty] there is no third case, and that is the whole point of the type. A chapter's
 * nets, its plan, every `chapter_balances` row and every row the chapter screen draws are all
 * guaranteed to name ME or a friend this database knows. Expressing that in the type turns a leg to
 * a shop or to someone unidentified into a compile error at the one place it is converted, instead
 * of an `else -> error(...)` at each of the dozen places it would otherwise be read.
 *
 * Legs are still *derived* in [IouParty] -- see [com.varun.upitracker.domain.iou.IouLegs], which is
 * the most delicate maths in the app and must never be duplicated.
 */
sealed interface ChapterParty {

    data object Me : ChapterParty

    data class Friend(val friendId: Long) : ChapterParty
}

/**
 * The chapter's view of a party, or null for someone it cannot hold a balance for.
 *
 * This is R13's "drop every leg whose debtor or creditor is not ME or a known friend", in one place:
 * a shop is spending rather than lending, and an unidentified person has nowhere for the debt to go.
 */
fun IouParty.toChapterParty(): ChapterParty? = when (this) {
    is IouParty.Me -> ChapterParty.Me
    is IouParty.Friend -> ChapterParty.Friend(friendId)
    is IouParty.Person, is IouParty.Counterparty -> null
}

/**
 * ME first, then friends by ascending id.
 *
 * The whole determinism story rests on this. Simplification has to reach the same plan whatever
 * order the transactions arrive in, so every tie is broken by position in this order and no step
 * ever iterates a map in insertion order.
 */
val CHAPTER_PARTY_ORDER: Comparator<ChapterParty> = Comparator { a, b ->
    fun key(party: ChapterParty): Long = when (party) {
        is ChapterParty.Me -> Long.MIN_VALUE
        is ChapterParty.Friend -> party.friendId
    }
    key(a).compareTo(key(b))
}
