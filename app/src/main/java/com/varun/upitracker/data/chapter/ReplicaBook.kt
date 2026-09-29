package com.varun.upitracker.data.chapter

import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterBalance
import com.varun.upitracker.database.entity.ChapterMember
import com.varun.upitracker.domain.chapter.ChapterSnapshot
import com.varun.upitracker.domain.chapter.ChapterSnapshotFormat
import com.varun.upitracker.domain.chapter.ReplicaBalances
import com.varun.upitracker.domain.chapter.ReplicaMath
import com.varun.upitracker.domain.chapter.ReplicaParty
import com.varun.upitracker.domain.parcel.ParcelActor

/**
 * What a copy of a friend's chapter adds up to on this phone, worked out from the owner's last
 * snapshot and nothing else. See docs/declarations-design.md S4.
 *
 * The counterpart of `ChapterMath` for chapters this phone does not own: its `chapter_balances` and
 * `chapter_members` rows are rebuilt from the owner's plan, so friend pages, the dashboard and every
 * agreement read a copy exactly as they read any chapter. Plain suspend calls with no transaction of
 * their own, for the reason given on [com.varun.upitracker.data.repository.ChapterRepository].
 */
class ReplicaBook(private val db: AppDatabase) {

    fun snapshotOf(chapter: Chapter): ChapterSnapshot? = chapter.snapshot?.let { ChapterSnapshotFormat.decode(it) }

    suspend fun recomputeInTransaction(chapter: Chapter) {
        val ownerFriendId = chapter.ownerFriendId ?: return
        db.chapterDao().deleteBalancesForChapter(chapter.id)
        db.chapterDao().deleteMembers(chapter.id)
        val snapshot = snapshotOf(chapter) ?: return

        val resolve = resolver(chapter.id, ownerFriendId)
        val balances = ReplicaMath.balances(snapshot.plan, resolve)
        if (balances.contributions.isNotEmpty()) {
            db.chapterDao().insertBalances(
                balances.contributions.map { (friendId, amount) -> ChapterBalance(chapter.id, friendId, amount) }
            )
        }
        val members = snapshot.members.map(resolve)
            .filterIsInstance<ReplicaParty.Friend>()
            .map { it.friendId }
            .toSet() + ownerFriendId
        db.chapterDao().addMembers(members.map { ChapterMember(chapter.id, it, chapter.createdEpoch) })
    }

    /** What the copy's plan does here, without writing anything. For the copy's own screen. */
    suspend fun balancesOf(chapter: Chapter): ReplicaBalances? {
        val ownerFriendId = chapter.ownerFriendId ?: return null
        val snapshot = snapshotOf(chapter) ?: return null
        return ReplicaMath.balances(snapshot.plan, resolver(chapter.id, ownerFriendId))
    }

    /**
     * Who each of the owner's people is here: the owner themselves, a friend linked with the account
     * the owner named, or the user's own mapping -- and otherwise nobody yet.
     */
    suspend fun resolver(chapterId: Long, ownerFriendId: Long): (ParcelActor) -> ReplicaParty {
        val mapped = db.chapterDao().peopleFor(chapterId).associate { it.personKey to it.friendId }
        val linked = db.mailboxDao().getAllLinks().associate { it.uid to it.friendId }
        return { actor -> ReplicaMath.resolve(actor, ownerFriendId, { linked[it] }, mapped) }
    }
}
