package com.varun.upitracker.domain.declaration

/**
 * D9: sealing, applied to chapters.
 *
 * A row outside chapters that is dated at or before a checkpoint posts nothing for that friend (D8).
 * A chapter cannot be treated the same way row by row -- its plan is worked out over all its rows at
 * once, so no row has an effect of its own to leave out. What can be done is to catch the change as it
 * happens: when rows that are all dated at or before a friend's checkpoint move into, out of or within
 * a chapter, the difference that makes to the friend's contribution is added to the chapter's part in
 * the checkpoint. The opening shrinks by the same amount, and the balance with that friend does not
 * move -- the checkpoint already stood for those rows.
 *
 * The test is on dates alone, not on who is in the rows: moving an old row can reshuffle a plan and
 * change what someone *else* is owed, and that is still a change caused by something the checkpoint
 * with them already covered.
 */
object Absorption {

    /**
     * The friends for whom a change caused by rows dated [versionDates] is absorbed: everyone whose
     * checkpoint is at or after the latest of them. [versionDates] holds every version that moved --
     * a row before an edit and after it, and any refund carried along with it.
     */
    fun absorbingFriends(versionDates: Collection<Long>, asOfByFriend: Map<Long, Long>): Set<Long> {
        if (versionDates.isEmpty()) return emptySet()
        val latest = versionDates.max()
        return asOfByFriend.filterValues { it >= latest }.keys
    }

    /**
     * How much each of [friends]'s contribution moved from [before] to [after] -- one chapter's
     * `chapter_balances`, where a missing friend means zero. Unchanged friends are left out.
     */
    fun deltas(before: Map<Long, Long>, after: Map<Long, Long>, friends: Set<Long>): Map<Long, Long> =
        friends.associateWith { (after[it] ?: 0L) - (before[it] ?: 0L) }
            .filterValues { it != 0L }
}
