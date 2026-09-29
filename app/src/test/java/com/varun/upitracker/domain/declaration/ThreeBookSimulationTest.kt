package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.Chapter
import com.varun.upitracker.database.entity.ChapterShareMode
import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationPart
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.LedgerEffect
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.TransactionShare
import com.varun.upitracker.domain.chapter.ChapterMath
import com.varun.upitracker.domain.chapter.ChapterSnapshot
import com.varun.upitracker.domain.chapter.ChapterSnapshotFormat
import com.varun.upitracker.domain.chapter.ClaimMatcher
import com.varun.upitracker.domain.chapter.ClaimRow
import com.varun.upitracker.domain.chapter.LocalRow
import com.varun.upitracker.domain.chapter.ReplicaMath
import com.varun.upitracker.domain.chapter.SnapshotBuilder
import com.varun.upitracker.domain.chapter.SnapshotHint
import com.varun.upitracker.domain.chapter.SnapshotSource
import com.varun.upitracker.domain.chapter.TaggedTx
import com.varun.upitracker.domain.mailbox.MailboxIds
import com.varun.upitracker.domain.parcel.ParcelPerspective
import com.varun.upitracker.ledger.InMemoryLedger
import com.varun.upitracker.ledger.LedgerReplayer
import com.varun.upitracker.ledger.ListReplaySource
import com.varun.upitracker.ui.ActorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Three phones -- Alice, Bob and Dan -- built from nothing but the domain objects the app runs, walked
 * through agreements, late rows, amendments, absorptions and shared chapters. After every step each
 * pair's balances must be exact negatives of each other: the guarantee of docs/declarations-design.md
 * section 6, checked end to end rather than argued.
 *
 * What the repositories do with Room -- which parts an acceptance adds, what a snapshot claims, what a
 * hint sets -- is done here with the same pure objects they call ([DeclarationParts], [ClaimMatcher],
 * [ReplicaMath], [Absorption], [SnapshotBuilder]), so the wiring is as close as a JVM test can get.
 */
class ThreeBookSimulationTest {

    private class OwnChapter(val id: Long, val name: String, val members: MutableSet<Long>) {
        var shareId: String? = null
        var version = 0L
    }

    private class Copy(val id: Long, val ownerFriendId: Long, var snapshot: ChapterSnapshot)

    /** One person's phone. Every other person has their own id here, as they would on a real phone. */
    private class Book(val name: String, val uid: String) {
        val friendOf = mutableMapOf<String, Long>()
        val names = mutableMapOf<Long, String>()
        val linked = mutableSetOf<String>()
        val rows = mutableListOf<TaggedTx>()
        val own = mutableMapOf<Long, OwnChapter>()
        val copies = mutableMapOf<Long, Copy>()
        val mappings = mutableMapOf<Long, MutableMap<String, Long>>()
        val declarations = linkedMapOf<String, IouDeclaration>()
        val parts = mutableListOf<DeclarationPart>()
        var nextRowId = 1L
        var nextChapterId = 100L

        fun know(other: Book, friendId: Long, linkedWith: Boolean) {
            friendOf[other.uid] = friendId
            names[friendId] = other.name
            if (linkedWith) linked += other.uid
        }

        fun row(id: Long) = rows.first { it.transaction.id == id }

        fun replace(transaction: Transaction) {
            val index = rows.indexOfFirst { it.transaction.id == transaction.id }
            rows[index] = rows[index].copy(transaction = transaction)
        }

        fun contributions(chapterId: Long): Map<Long, Long> {
            own[chapterId]?.let { return ChapterMath.compute(rows.filter { it.transaction.chapterId == chapterId }).contributions }
            val copy = copies[chapterId] ?: return emptyMap()
            val mapped = mappings[chapterId].orEmpty()
            return ReplicaMath.balances(copy.snapshot.plan) { actor ->
                ReplicaMath.resolve(actor, copy.ownerFriendId, { uid -> friendOf[uid]?.takeIf { uid in linked } }, mapped)
            }.contributions
        }

        fun chapterIds(): Set<Long> = own.keys + copies.keys

        fun shareIdOf(chapterId: Long): String? = own[chapterId]?.shareId ?: copies[chapterId]?.snapshot?.shareId

        fun effective(friendId: Long): IouDeclaration? =
            DeclarationSet.effective(declarations.values.filter { it.friendId == friendId })

        /** The whole balance with [friendId]: the ledger the replay builds, plus every chapter (D7). */
        fun balance(friendId: Long): Long {
            val checkpoint = effective(friendId)
            val held = chapterIds()
            val openings = checkpoint?.let {
                val counted = parts.filter { part -> part.declarationId == it.id && part.chapterId in held }
                mapOf(friendId to Checkpoints.openingOf(it, counted))
            } ?: emptyMap()
            val ledger = InMemoryLedger { id -> row(id).transaction.dateEpoch }
            runBlocking { LedgerReplayer(ListReplaySource({ rows }, { openings }, ledger), ledger).replay(setOf(friendId)) }
            return ledger.net(friendId) + chapterIds().sumOf { contributions(it)[friendId] ?: 0L }
        }

        fun chapterShares(friendId: Long): List<ChapterShareOf> =
            chapterIds().mapNotNull { chapterId ->
                contributions(chapterId)[friendId]?.let { ChapterShareOf(chapterId, shareIdOf(chapterId), it) }
            }
    }

    private val alice = Book("Alice", "uidAlice")
    private val bob = Book("Bob", "uidBob")
    private val dan = Book("Dan", "uidDan")

    init {
        alice.know(bob, 20L, linkedWith = true)
        alice.know(dan, 30L, linkedWith = true)
        bob.know(alice, 11L, linkedWith = true)
        bob.know(dan, 31L, linkedWith = false)
        dan.know(alice, 12L, linkedWith = true)
        dan.know(bob, 22L, linkedWith = false)
    }

    // --- recording and passing transactions --------------------------------------------------------

    /** A party as [book] names them: "me", "shop", or someone's uid. */
    private fun actor(book: Book, party: String): Triple<String, Long?, String?> = when (party) {
        "me" -> Triple(ActorType.ME, null, null)
        "shop" -> Triple(ActorType.MERCHANT, null, "Shop")
        else -> book.friendOf.getValue(party).let { Triple(ActorType.FRIEND, it, book.names[it]) }
    }

    private fun record(
        book: Book,
        date: Long,
        amount: Long,
        payer: String,
        payee: String,
        split: List<Pair<String, Long>> = emptyList(),
        chapterId: Long? = null
    ): Long {
        val id = book.nextRowId++
        val (payerType, payerFriend, payerLabel) = actor(book, payer)
        val (payeeType, payeeFriend, payeeLabel) = actor(book, payee)
        val shares = split.map { (party, share) ->
            val (type, friend, label) = actor(book, party)
            TransactionShare(transactionId = id, side = "PAYER", participantType = type, friendId = friend, amountPaise = share, rawLabel = label)
        }
        val tx = Transaction(
            id = id, amountPaise = amount,
            payerActorType = payerType, payerFriendId = payerFriend, payerRawLabel = payerLabel,
            payeeActorType = payeeType, payeeFriendId = payeeFriend, payeeRawLabel = payeeLabel,
            dateEpoch = date, source = "MANUAL", ledgerEffect = LedgerEffect.DEBT,
            iouRecovery = IouRecovery.FROM_SECONDARY_PAYERS
        )
        book.rows += TaggedTx(tx, shares)
        // A row created straight into a chapter goes through the same absorption as a tag (D9).
        if (chapterId != null) tag(book, id, chapterId)
        return id
    }

    /** Sends [rowId] from [from] to [to] through the mailbox, landed and reviewed as the app does. */
    private fun send(from: Book, rowId: Long, to: Book): Long {
        var tx = from.row(rowId).transaction
        if (tx.shareRef == null) {
            tx = tx.copy(shareRef = MailboxIds.newRandomId())
            from.replace(tx)
        }
        val shares = from.row(rowId).shares
        val parcelRow = ParcelPerspective.flipForRecipient(
            transaction = tx,
            shares = shares,
            recipientFriendId = from.friendOf.getValue(to.uid),
            friendName = { from.names[it] },
            merchantName = { null },
            linkedUidOf = { friendId -> from.friendOf.entries.firstOrNull { it.value == friendId }?.key?.takeIf { it in from.linked && it in to.linked } }
        ).copy(sourceId = 0L, shareRef = tx.shareRef)
        val landed = ParcelPerspective.toLocalFromMailbox(
            row = parcelRow,
            senderFriendId = to.friendOf.getValue(from.uid),
            senderUid = from.uid,
            // The user picks who a name is on the review screen; here, by name.
            mapPerson = { name -> to.names.entries.firstOrNull { it.value == name }?.key },
            resolveLinked = { uid -> to.friendOf[uid]?.takeIf { uid in to.linked } },
            resolveShop = { null },
            carryUpiRefId = false
        )
        val id = to.nextRowId++
        to.rows += TaggedTx(
            landed.transaction.copy(id = id, isPending = false),
            landed.shares.map { it.copy(transactionId = id) }
        )
        return id
    }

    // --- chapters ---------------------------------------------------------------------------------

    private fun newChapter(book: Book, name: String): Long {
        val id = book.nextChapterId++
        book.own[id] = OwnChapter(id, name, mutableSetOf())
        return id
    }

    /** Moves a row into [chapterId] (or out, when null), absorbing what a checkpoint covers (D9). */
    private fun tag(book: Book, rowId: Long, chapterId: Long?) {
        val tagged = book.row(rowId)
        val touched = setOfNotNull(tagged.transaction.chapterId, chapterId)
        val before = touched.associateWith { book.contributions(it) }
        book.replace(tagged.transaction.copy(chapterId = chapterId))
        chapterId?.let { book.own.getValue(it).members += ChapterMath.friendsIn(tagged.transaction, tagged.shares) }
        val after = touched.associateWith { book.contributions(it) }

        val friends = (before.values + after.values).flatMap { it.keys }.toSet()
        val asOf = friends.mapNotNull { f -> book.effective(f)?.let { f to requireNotNull(it.asOfEpoch) } }.toMap()
        val absorbing = Absorption.absorbingFriends(listOf(tagged.transaction.dateEpoch), asOf)
        touched.forEach { chapter ->
            Absorption.deltas(before.getValue(chapter), after.getValue(chapter), absorbing).forEach { (friendId, delta) ->
                val checkpoint = requireNotNull(book.effective(friendId))
                val index = book.parts.indexOfFirst { it.declarationId == checkpoint.id && it.chapterId == chapter }
                if (index >= 0) {
                    book.parts[index] = book.parts[index].copy(amountPaise = book.parts[index].amountPaise + delta)
                } else {
                    book.parts += DeclarationPart(declarationId = checkpoint.id, chapterId = chapter, shareId = book.shareIdOf(chapter), amountPaise = delta)
                }
            }
        }
        touched.forEach { book.own[it]?.let { chapter -> chapter.version++ } }
    }

    /** The owner sends [chapterId] to [members] as it stands, and each applies it (S2-S8). */
    private fun publish(owner: Book, chapterId: Long, vararg members: Book) {
        val chapter = owner.own.getValue(chapterId)
        if (chapter.shareId == null) chapter.shareId = MailboxIds.newRandomId()
        chapter.version++
        owner.rows.filter { it.transaction.chapterId == chapterId && it.transaction.shareRef == null }
            .forEach { owner.replace(it.transaction.copy(shareRef = MailboxIds.newRandomId())) }
        val rows = owner.rows.filter { it.transaction.chapterId == chapterId }
        val receiving = members.filter { it.uid in owner.linked }.associate { owner.friendOf.getValue(it.uid) to it.uid }
        val source = SnapshotSource(
            chapter = Chapter(
                id = chapterId, name = chapter.name, createdEpoch = 0L, shareId = chapter.shareId,
                shareVersion = chapter.version, shareMode = ChapterShareMode.SHARED
            ),
            result = ChapterMath.compute(rows),
            memberIds = chapter.members,
            rows = rows,
            shareRefOf = { id -> requireNotNull(owner.row(id).transaction.shareRef) },
            friendName = { owner.names[it] },
            merchantName = { null },
            receivingUids = receiving,
            sentEpoch = 0L
        )
        members.forEach { member ->
            val friendId = owner.friendOf.getValue(member.uid)
            val hint = owner.effective(friendId)?.let { checkpoint ->
                SnapshotHint(checkpoint.id, -(owner.parts.firstOrNull { it.declarationId == checkpoint.id && it.chapterId == chapterId }?.amountPaise ?: 0L))
            }
            val snapshot = SnapshotBuilder.build(source, friendId, hint, pasteTokenForRecipient = null)
            val arrived = requireNotNull(ChapterSnapshotFormat.decode(ChapterSnapshotFormat.encode(snapshot), owner.uid, member.uid))
            receive(member, owner, arrived)
        }
    }

    private fun receive(member: Book, owner: Book, snapshot: ChapterSnapshot) {
        val ownerFriendId = member.friendOf.getValue(owner.uid)
        val copy = member.copies.values.firstOrNull { it.snapshot.shareId == snapshot.shareId }
            ?.also { it.snapshot = snapshot }
            ?: Copy(member.nextChapterId++, ownerFriendId, snapshot).also { member.copies[it.id] = it }

        // S5: claims, by reference only.
        val claimRows = snapshot.rows.zip(snapshot.extras) { row, extra -> ClaimRow(row.shareRef, row.legacyRef, extra.sourceRef, extra.pending) }
        val matched = ClaimMatcher.match(
            claimRows,
            member.rows.map { LocalRow(it.transaction.id, it.transaction.shareRef, it.transaction.sharedRefId) },
            owner.uid, member.uid, myPasteTokenForOwner = null
        )
        member.rows.toList().forEach { row ->
            val tx = row.transaction
            if (tx.id in matched && tx.chapterId == null) member.replace(tx.copy(chapterId = copy.id))
            if (tx.chapterId == copy.id && tx.id !in matched) member.replace(tx.copy(chapterId = null))
        }
        // D6: listed parts waiting for this chapter count from now on.
        member.parts.replaceAll { if (it.shareId == snapshot.shareId && it.chapterId == null) it.copy(chapterId = copy.id) else it }
        // S8: the owner's part in the agreement with this member.
        snapshot.hint?.let { hint ->
            val declaration = member.declarations[hint.declarationId]?.takeIf { it.friendId == ownerFriendId } ?: return@let
            val index = member.parts.indexOfFirst { it.declarationId == declaration.id && (it.chapterId == copy.id || it.shareId == snapshot.shareId) }
            if (index >= 0) {
                member.parts[index] = member.parts[index].copy(chapterId = copy.id, shareId = snapshot.shareId, amountPaise = hint.amountPaise)
            } else if (hint.amountPaise != 0L) {
                member.parts += DeclarationPart(declarationId = declaration.id, chapterId = copy.id, shareId = snapshot.shareId, amountPaise = hint.amountPaise)
            }
        }
    }

    // --- agreements -----------------------------------------------------------------------------------

    private var nextDeclaration = 1

    /** [proposer] proposes a DECLARE of its whole balance with [acceptor] as of [asOf]; [acceptor] accepts. */
    private fun declare(proposer: Book, acceptor: Book, asOf: Long): String {
        val friendId = proposer.friendOf.getValue(acceptor.uid)
        val id = "declaration-${nextDeclaration++}".padEnd(22, 'x').take(22)
        val row = IouDeclaration(
            id = id, friendId = friendId, kind = DeclarationKind.DECLARE, asOfEpoch = asOf,
            amountPaise = proposer.balance(friendId), proposedByMe = true, proposedEpoch = asOf,
            state = DeclarationState.OPEN
        )
        proposer.declarations[id] = row
        proposer.parts += DeclarationParts.forProposal(id, proposer.chapterShares(friendId))
        val listed = proposer.parts.filter { it.declarationId == id && it.shareId != null }.map { ProposalPart(it.shareId!!, it.amountPaise) }
        deliverAndAccept(proposer, acceptor, row, listed)
        return id
    }

    /** D10: [proposer] asks to add [delta] to the checkpoint in force; [acceptor] accepts. */
    private fun amend(proposer: Book, acceptor: Book, delta: Long) {
        val friendId = proposer.friendOf.getValue(acceptor.uid)
        val target = requireNotNull(proposer.effective(friendId))
        val id = "amendment-${nextDeclaration++}".padEnd(22, 'x').take(22)
        val row = IouDeclaration(
            id = id, friendId = friendId, kind = DeclarationKind.AMEND, targetId = target.id,
            asOfEpoch = target.asOfEpoch, amountPaise = target.amountPaise!! + delta, deltaPaise = delta,
            proposedByMe = true, proposedEpoch = 0L, state = DeclarationState.OPEN
        )
        proposer.declarations[id] = row
        deliverAndAccept(proposer, acceptor, row, emptyList())
    }

    private fun deliverAndAccept(proposer: Book, acceptor: Book, row: IouDeclaration, listed: List<ProposalPart>) {
        val proposal = requireNotNull(
            DeclarationMessages.decodeProposal(DeclarationMessages.encodeProposal(DeclarationMessages.outgoing(row, listed)))
        )
        val theirFriendId = acceptor.friendOf.getValue(proposer.uid)
        val received = requireNotNull(DeclarationReceipt.rowForProposal(acceptor.declarations[row.id], theirFriendId, proposal))
        acceptor.declarations[row.id] = received
        proposal.parts.forEach { part ->
            val here = acceptor.chapterIds().firstOrNull { acceptor.shareIdOf(it) == part.shareId }
            acceptor.parts += DeclarationParts.listed(row.id, part, here)
        }

        // The acceptor accepts, and completes it on its side...
        val accepted = received.copy(state = requireNotNull(DeclarationFlow.next(received.state, DeclarationEvent.ACCEPTED)))
        acceptor.declarations[row.id] = accepted
        acceptor.parts += DeclarationParts.onAcceptance(
            accepted, acceptor.parts.filter { it.declarationId == row.id }, acceptor.chapterShares(theirFriendId),
            acceptor.parts.filter { it.declarationId == accepted.targetId }
        )
        // ...then the ACCEPT reaches the proposer, which completes it on its side.
        val applied = requireNotNull(DeclarationReceipt.applyAnswer(proposer.declarations[row.id], row.friendId, DeclarationAnswer(row.id, DeclarationVerdict.ACCEPT), 0L))
        proposer.declarations[row.id] = applied
        proposer.parts += DeclarationParts.onAcceptance(
            applied, proposer.parts.filter { it.declarationId == row.id }, proposer.chapterShares(row.friendId),
            proposer.parts.filter { it.declarationId == applied.targetId }
        )
    }

    private fun assertAgree(a: Book, b: Book, expectedForA: Long? = null) {
        val fromA = a.balance(a.friendOf.getValue(b.uid))
        val fromB = b.balance(b.friendOf.getValue(a.uid))
        assertEquals("${a.name} and ${b.name} disagree", fromA, -fromB)
        expectedForA?.let { assertEquals("${a.name}'s figure for ${b.name}", it, fromA) }
    }

    // --- the story --------------------------------------------------------------------------------------

    @Test
    fun `three books stay agreed through agreements, late rows, absorptions and shared chapters`() {
        // 1. A dinner Alice paid, split with Bob, sent to him.
        val dinner = record(alice, 100L, 100_000L, "me", "shop", listOf("me" to 50_000L, bob.uid to 50_000L))
        send(alice, dinner, bob)
        assertAgree(alice, bob, 50_000L)

        // 2. A private chapter only Alice has: the rent, split with Bob.
        val flat = newChapter(alice, "Flat")
        record(alice, 110L, 300_000L, "me", "shop", listOf("me" to 150_000L, bob.uid to 150_000L), chapterId = flat)
        assertEquals(200_000L, alice.balance(20L))
        assertEquals(-50_000L, bob.balance(11L))

        // 3. They agree on Alice's whole figure, Flat included, as of 200.
        declare(alice, bob, asOf = 200L)
        assertAgree(alice, bob, 200_000L)

        // 4. After the checkpoint Bob pays 700 back, and sends it.
        val repaid = record(bob, 300L, 70_000L, "me", alice.uid)
        send(bob, repaid, alice)
        assertAgree(alice, bob, 130_000L)

        // 5. A cab from before the checkpoint, recorded late on both phones: nothing moves (D8)...
        val cab = record(alice, 150L, 40_000L, "me", "shop", listOf("me" to 20_000L, bob.uid to 20_000L))
        send(alice, cab, bob)
        assertAgree(alice, bob, 130_000L)
        // 6. ...until both agree to add it (D10).
        amend(alice, bob, 20_000L)
        assertAgree(alice, bob, 150_000L)

        // 7. Alice files the old dinner under Flat. It is covered by the checkpoint, so it is absorbed (D9).
        tag(alice, dinner, flat)
        assertAgree(alice, bob, 150_000L)

        // 8. Alice shares Flat. Bob's copy claims his dinner and takes the owner's hint (S5, S8).
        publish(alice, flat, bob)
        assertEquals(-200_000L, bob.contributions(bob.copies.keys.single())[11L])
        assertAgree(alice, bob, 150_000L)

        // 9. A bill after the checkpoint goes straight into Flat: it counts, on both phones.
        record(alice, 400L, 40_000L, "me", "shop", listOf("me" to 20_000L, bob.uid to 20_000L), chapterId = flat)
        publish(alice, flat, bob)
        assertAgree(alice, bob, 170_000L)

        // 10. Goa, with Dan too. Bob and Dan are not linked with each other.
        val goa = newChapter(alice, "Goa")
        record(alice, 500L, 900_000L, "me", "shop", listOf("me" to 300_000L, bob.uid to 300_000L, dan.uid to 300_000L), chapterId = goa)
        val bobsDinner = record(bob, 510L, 300_000L, "me", "shop", listOf("me" to 100_000L, alice.uid to 100_000L, dan.uid to 100_000L))
        tag(alice, send(bob, bobsDinner, alice), goa)
        val dansCab = record(dan, 520L, 60_000L, "me", "shop", listOf("me" to 30_000L, bob.uid to 30_000L))
        tag(alice, send(dan, dansCab, alice), goa)
        // Alice and Dan agree while Goa is still private to Alice.
        declare(alice, dan, asOf = 530L)
        assertAgree(alice, dan, 370_000L)

        publish(alice, goa, bob, dan)
        // Bob and Dan say who each other is on their own copies: nothing else can (S4).
        bob.mappings.getOrPut(bob.copies.values.single { it.snapshot.name == "Goa" }.id) { mutableMapOf() }["uid:uidDan"] = 31L
        dan.mappings.getOrPut(dan.copies.values.single { it.snapshot.name == "Goa" }.id) { mutableMapOf() }["uid:uidBob"] = 22L
        assertAgree(alice, bob, 300_000L)
        assertAgree(alice, dan, 370_000L)
        assertAgree(bob, dan)

        // 11. Now Bob proposes, listing both shared chapters from his copies; Alice adopts his figures (D6).
        declare(bob, alice, asOf = 600L)
        assertAgree(alice, bob, 300_000L)

        // 12. And the chapters keep going afterwards.
        record(alice, 700L, 20_000L, "me", "shop", listOf("me" to 10_000L, bob.uid to 10_000L), chapterId = flat)
        publish(alice, flat, bob)
        publish(alice, goa, bob, dan)
        assertAgree(alice, bob, 310_000L)
        assertAgree(alice, dan, 370_000L)
        assertAgree(bob, dan)
    }

    @Test
    fun `a copy that falls behind catches up whole`() {
        val flat = newChapter(alice, "Flat")
        record(alice, 100L, 100_000L, "me", "shop", listOf("me" to 50_000L, bob.uid to 50_000L), chapterId = flat)
        declare(alice, bob, asOf = 150L)
        publish(alice, flat, bob)
        assertAgree(alice, bob, 50_000L)

        // Two changes; the first snapshot never arrives.
        record(alice, 200L, 20_000L, "me", "shop", listOf("me" to 10_000L, bob.uid to 10_000L), chapterId = flat)
        record(alice, 300L, 40_000L, "me", "shop", listOf("me" to 20_000L, bob.uid to 20_000L), chapterId = flat)
        publish(alice, flat, bob)
        assertAgree(alice, bob, 80_000L)
    }

    @Test
    fun `a private chapter shared after the agreement is carried over by the hint`() {
        val flat = newChapter(alice, "Flat")
        val rent = record(alice, 100L, 300_000L, "me", "shop", listOf("me" to 150_000L, bob.uid to 150_000L), chapterId = flat)
        send(alice, rent, bob)
        // Bob holds the rent as an ordinary row; Alice holds it in Flat. They agree on 1,500 either way.
        declare(alice, bob, asOf = 200L)
        assertAgree(alice, bob, 150_000L)
        // Sharing Flat claims Bob's copy of the rent into it, and the hint says Flat was already in the figure.
        publish(alice, flat, bob)
        assertAgree(alice, bob, 150_000L)
    }
}
