package com.varun.upitracker.domain.chapter

import com.varun.upitracker.domain.parcel.ParcelActor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/declarations-design.md S4 and S5: a member's balances from the owner's plan, and claims. */
class ReplicaAndClaimTest {

    // Bob's book: Alice (the owner) is friend 11, Dan is friend 31.
    private val alice = 11L
    private val danHere = 31L

    private fun resolve(linked: Map<String, Long> = emptyMap(), mapped: Map<String, Long> = emptyMap()): (ParcelActor) -> ReplicaParty =
        { ReplicaMath.resolve(it, ownerFriendId = alice, linkedFriendOf = linked::get, mapped = mapped) }

    private val plan = listOf(
        SnapshotPayment(ParcelActor.Me, ParcelActor.Sender, 330_000L),
        SnapshotPayment(ParcelActor.Linked("uidDan", "Dan"), ParcelActor.Sender, 170_000L),
        SnapshotPayment(ParcelActor.Linked("uidDan", "Dan"), ParcelActor.Me, 20_000L)
    )

    @Test
    fun `only payments between ME and someone placed here count`() {
        val balances = ReplicaMath.balances(plan, resolve(linked = mapOf("uidDan" to danHere)))
        assertEquals(mapOf(alice to -330_000L, danHere to 20_000L), balances.contributions)
        assertTrue(balances.unresolved.isEmpty())
    }

    @Test
    fun `someone nobody has placed is shown and not counted`() {
        val balances = ReplicaMath.balances(plan, resolve())
        assertEquals(mapOf(alice to -330_000L), balances.contributions)
        assertEquals(1, balances.unresolved.size)
        assertEquals(20_000L, balances.unresolved.single().amountPaise)
        assertEquals("uid:uidDan", balances.unresolved.single().person.key)
    }

    @Test
    fun `a hand-made mapping places them, by account or by name`() {
        assertEquals(20_000L, ReplicaMath.balances(plan, resolve(mapped = mapOf("uid:uidDan" to danHere))).contributions[danHere])
        val named = listOf(SnapshotPayment(ParcelActor.Person("  Dan "), ParcelActor.Me, 5_000L))
        assertEquals(5_000L, ReplicaMath.balances(named, resolve(mapped = mapOf("name:dan" to danHere))).contributions[danHere])
    }

    @Test
    fun `the person key ignores case and spacing in a name`() {
        assertEquals("name:dan", ReplicaMath.personKey(ParcelActor.Person(" Dan ")))
        assertEquals("uid:u1", ReplicaMath.personKey(ParcelActor.Linked("u1", "Dan")))
        assertEquals(null, ReplicaMath.personKey(ParcelActor.Sender))
    }

    // --- claims -----------------------------------------------------------------------------------

    private val mine = listOf(
        LocalRow(id = 1L, shareRef = "myDinner", sharedRefId = null),          // Bob's own, sent to Alice
        LocalRow(id = 2L, shareRef = null, sharedRefId = "mbx:uidAlice:hotel"), // Alice sent Bob the hotel
        LocalRow(id = 3L, shareRef = null, sharedRefId = "mbx:uidDan:cab"),     // Dan sent Bob the cab
        LocalRow(id = 4L, shareRef = null, sharedRefId = "pasteA.77"),          // Alice once pasted this to Bob
        LocalRow(id = 5L, shareRef = null, sharedRefId = null),                 // Bob pasted this to Alice as row 5
        LocalRow(id = 6L, shareRef = null, sharedRefId = null)                  // nothing in common with anything
    )

    private val rows = listOf(
        ClaimRow(shareRef = "a1", legacyRef = null, sourceRef = "mbx:uidBob:myDinner", pending = false),
        ClaimRow(shareRef = "hotel", legacyRef = null, sourceRef = null, pending = false),
        ClaimRow(shareRef = "a3", legacyRef = null, sourceRef = "mbx:uidDan:cab", pending = false),
        ClaimRow(shareRef = "a4", legacyRef = "pasteA.77", sourceRef = null, pending = false),
        ClaimRow(shareRef = "a5", legacyRef = null, sourceRef = "tokBob.5", pending = false)
    )

    @Test
    fun `every kind of reference finds its row, and nothing else is claimed`() {
        val claimed = ClaimMatcher.match(rows, mine, ownerUid = "uidAlice", myUid = "uidBob", myPasteTokenForOwner = "tokBob")
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), claimed)
    }

    @Test
    fun `a row still pending on the owner's phone is not claimed`() {
        val pending = rows.map { it.copy(pending = true) }
        assertTrue(ClaimMatcher.match(pending, mine, "uidAlice", "uidBob", "tokBob").isEmpty())
    }

    @Test
    fun `without the accounts only paste references match`() {
        val claimed = ClaimMatcher.match(rows, mine, ownerUid = null, myUid = null, myPasteTokenForOwner = "tokBob")
        // The dinner's source names Bob's account, which is unknown here; Dan's row matches as-is.
        assertEquals(setOf(3L, 4L, 5L), claimed)
    }

    @Test
    fun `lookups ask for exactly what could match`() {
        val lookups = ClaimMatcher.lookups(rows, "uidAlice", "uidBob", "tokBob")
        assertEquals(setOf("myDinner"), lookups.shareRefs)
        assertEquals(setOf(5L), lookups.ids)
        assertTrue("mbx:uidAlice:hotel" in lookups.sharedRefIds)
        assertTrue("mbx:uidDan:cab" in lookups.sharedRefIds)
        assertTrue("pasteA.77" in lookups.sharedRefIds)
    }

    @Test
    fun `a row is claimed at most once`() {
        val twice = listOf(rows[1], rows[1].copy(shareRef = "hotel"))
        assertEquals(setOf(2L), ClaimMatcher.match(twice, mine, "uidAlice", "uidBob", null))
    }

    // --- locating rows for the copy's screen -------------------------------------------------------

    @Test
    fun `every row is located without knowing either account`() {
        // What a frozen copy is left with once the link, and the owner's account with it, is gone.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ClaimMatcher.locate(rows, mine, "tokBob"))
    }

    @Test
    fun `a row still pending on the owner's phone is located all the same`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ClaimMatcher.locate(rows.map { it.copy(pending = true) }, mine, "tokBob"))
    }

    @Test
    fun `a row nobody here holds is located nowhere, and nothing twice`() {
        val strangers = listOf(
            ClaimRow(shareRef = "unknown", legacyRef = null, sourceRef = "mbx:uidEve:elsewhere", pending = false),
            rows[1],
            rows[1]
        )
        assertEquals(listOf(null, 2L, null), ClaimMatcher.locate(strangers, mine, "tokBob"))
    }
}
