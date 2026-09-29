package com.varun.upitracker.domain.declaration

import com.varun.upitracker.database.entity.DeclarationKind
import com.varun.upitracker.database.entity.DeclarationState
import com.varun.upitracker.database.entity.IouDeclaration
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two phones exchanging proposals and answers through a mailbox that delivers in any order, run
 * through hundreds of interleavings. Whatever happens, both phones must end up holding the same
 * checkpoint -- the same id, and amounts that are negatives of each other -- which is the whole point
 * of docs/declarations-design.md D3 and D4.
 *
 * Uses exactly what the repository uses on each phone: [DeclarationReceipt] for what arrives,
 * [DeclarationFlow] for what the user does, and [DeclarationSet] for what is in force.
 */
class DeclarationConvergenceTest {

    /** One phone's rows about the other person. [friendId] is its own id for them. */
    private class Phone(val friendId: Long) {
        val rows = linkedMapOf<String, IouDeclaration>()
        fun effective(): IouDeclaration? = DeclarationSet.effective(rows.values)
        fun liveIds(): Set<String> = DeclarationSet.live(rows.values).map { it.id }.toSet()
    }

    private class InFlight(val to: Phone, val proposal: DeclarationProposal? = null, val answer: DeclarationAnswer? = null)

    /** Something a user does once [ready] holds. It is simply never done if it never does. */
    private class Intent(val ready: () -> Boolean, val act: () -> InFlight?)

    private lateinit var alice: Phone
    private lateinit var bob: Phone

    private fun fresh() {
        alice = Phone(friendId = 7L)
        bob = Phone(friendId = 3L)
    }

    private fun other(phone: Phone) = if (phone === alice) bob else alice

    // --- what a user does ---------------------------------------------------------------------------

    private fun declareRow(on: Phone, id: String, asOf: Long, amount: Long) = IouDeclaration(
        id = id, friendId = on.friendId, kind = DeclarationKind.DECLARE, asOfEpoch = asOf, amountPaise = amount,
        proposedByMe = true, proposedEpoch = asOf, state = DeclarationState.OPEN
    )

    private fun propose(on: Phone, row: IouDeclaration): InFlight {
        on.rows[row.id] = row
        return InFlight(other(on), proposal = DeclarationMessages.outgoing(row, emptyList()))
    }

    private fun canAccept(on: Phone, id: String) =
        on.rows[id]?.let { !it.proposedByMe && it.kind != null && DeclarationFlow.canAnswer(it.state) } == true

    private fun accept(on: Phone, id: String): InFlight {
        val row = on.rows.getValue(id)
        on.rows[id] = row.copy(state = DeclarationFlow.next(row.state, DeclarationEvent.ACCEPTED)!!)
        return InFlight(other(on), answer = DeclarationAnswer(id, DeclarationVerdict.ACCEPT))
    }

    private fun withdraw(on: Phone, id: String): InFlight? {
        val row = on.rows.getValue(id)
        val next = DeclarationFlow.next(row.state, DeclarationEvent.WITHDRAWN) ?: return null
        on.rows[id] = row.copy(state = next)
        return InFlight(other(on), answer = DeclarationAnswer(id, DeclarationVerdict.WITHDRAW))
    }

    private fun deliver(message: InFlight) {
        val phone = message.to
        message.proposal?.let { proposal ->
            DeclarationReceipt.rowForProposal(phone.rows[proposal.id], phone.friendId, proposal)
                ?.let { phone.rows[it.id] = it }
        }
        message.answer?.let { answer ->
            DeclarationReceipt.applyAnswer(phone.rows[answer.proposalId], phone.friendId, answer, now = 0L)
                ?.let { phone.rows[it.id] = it }
        }
    }

    // --- the scheduler --------------------------------------------------------------------------------

    /**
     * Picks at random among every message in flight and each phone's next ready intent, until nothing
     * is left that can happen.
     */
    private fun simulate(seed: Int, scripts: List<Pair<Phone, List<Intent>>>) {
        val random = Random(seed)
        val inFlight = mutableListOf<InFlight>()
        val queues = scripts.map { (_, intents) -> intents.toMutableList() }
        while (true) {
            val choices = mutableListOf<() -> Unit>()
            inFlight.forEach { message -> choices += { inFlight.remove(message); deliver(message) } }
            queues.forEach { queue ->
                val next = queue.firstOrNull()
                if (next != null && next.ready()) choices += { queue.removeAt(0); next.act()?.let(inFlight::add) }
            }
            if (choices.isEmpty()) break
            choices[random.nextInt(choices.size)]()
        }
        assertTrue("every message was delivered", inFlight.isEmpty())
    }

    private fun assertAgree() {
        val a = alice.effective()
        val b = bob.effective()
        assertEquals(a?.id, b?.id)
        if (a != null && b != null) assertEquals(a.amountPaise, -b.amountPaise!!)
        assertEquals(alice.liveIds(), bob.liveIds())
    }

    // --- scenarios ------------------------------------------------------------------------------------

    @Test
    fun `an acceptance racing a withdrawal ends the same on both phones`() {
        var accepted = 0
        var withdrawn = 0
        repeat(400) { seed ->
            fresh()
            simulate(
                seed,
                listOf(
                    alice to listOf(
                        Intent({ true }) { propose(alice, declareRow(alice, "d1", 100L, 50_000L)) },
                        Intent({ alice.rows["d1"]?.state == DeclarationState.OPEN }) { withdraw(alice, "d1") }
                    ),
                    bob to listOf(Intent({ canAccept(bob, "d1") }) { accept(bob, "d1") })
                )
            )
            assertAgree()
            if (alice.effective() != null) accepted++ else withdrawn++
        }
        assertNotEquals("some runs end accepted", 0, accepted)
        assertNotEquals("some runs end withdrawn", 0, withdrawn)
    }

    @Test
    fun `crossing proposals both accepted settle on the later one`() {
        repeat(400) { seed ->
            fresh()
            simulate(
                seed,
                listOf(
                    alice to listOf(
                        Intent({ true }) { propose(alice, declareRow(alice, "a1", 100L, 50_000L)) },
                        Intent({ canAccept(alice, "b1") }) { accept(alice, "b1") }
                    ),
                    bob to listOf(
                        Intent({ true }) { propose(bob, declareRow(bob, "b1", 200L, -45_000L)) },
                        Intent({ canAccept(bob, "a1") }) { accept(bob, "a1") }
                    )
                )
            )
            assertAgree()
            assertEquals("b1", alice.effective()?.id)
            assertEquals(45_000L, alice.effective()?.amountPaise)
        }
    }

    @Test
    fun `an amendment and a revocation of the same agreement converge`() {
        repeat(400) { seed ->
            fresh()
            // Already agreed on both phones: Bob owes Alice 500.
            alice.rows["d1"] = declareRow(alice, "d1", 100L, 50_000L).copy(state = DeclarationState.ACCEPTED)
            bob.rows["d1"] = declareRow(bob, "d1", 100L, -50_000L).copy(proposedByMe = false, state = DeclarationState.ACCEPTED)

            val amendment = IouDeclaration(
                id = "a1", friendId = alice.friendId, kind = DeclarationKind.AMEND, targetId = "d1", asOfEpoch = 100L,
                amountPaise = 60_000L, deltaPaise = 10_000L, proposedByMe = true, proposedEpoch = 300L,
                state = DeclarationState.OPEN
            )
            val revocation = IouDeclaration(
                id = "r1", friendId = bob.friendId, kind = DeclarationKind.REVOKE, targetId = "d1",
                proposedByMe = true, proposedEpoch = 300L, state = DeclarationState.OPEN
            )
            simulate(
                seed,
                listOf(
                    alice to listOf(
                        Intent({ true }) { propose(alice, amendment) },
                        Intent({ canAccept(alice, "r1") }) { accept(alice, "r1") }
                    ),
                    bob to listOf(
                        Intent({ true }) { propose(bob, revocation) },
                        Intent({ canAccept(bob, "a1") }) { accept(bob, "a1") }
                    )
                )
            )
            assertAgree()
            // The revocation removes d1 for good; the amendment adds its replacement.
            assertEquals("a1", alice.effective()?.id)
        }
    }

    @Test
    fun `a withdrawal that overtakes its proposal leaves nothing to answer`() {
        repeat(400) { seed ->
            fresh()
            simulate(
                seed,
                listOf(
                    alice to listOf(
                        Intent({ true }) { propose(alice, declareRow(alice, "d1", 100L, 50_000L)) },
                        Intent({ true }) { withdraw(alice, "d1") }
                    )
                )
            )
            assertEquals(DeclarationState.WITHDRAWN, bob.rows["d1"]?.state)
            assertTrue("nothing left open on Bob's phone", !canAccept(bob, "d1"))
            assertAgree()
        }
    }

    @Test
    fun `a repeat delivery changes nothing`() {
        fresh()
        val message = propose(alice, declareRow(alice, "d1", 100L, 50_000L))
        deliver(message)
        val answer = accept(bob, "d1")
        deliver(message)
        deliver(answer)
        deliver(answer)
        assertEquals(DeclarationState.ACCEPTED, bob.rows["d1"]?.state)
        assertAgree()
    }

    @Test
    fun `an answer from the wrong friend is ignored`() {
        fresh()
        val message = propose(alice, declareRow(alice, "d1", 100L, 50_000L))
        deliver(message)
        // Someone else claiming to accept Alice's proposal to Bob.
        val forged = DeclarationReceipt.applyAnswer(alice.rows["d1"], friendId = 99L, answer = DeclarationAnswer("d1", DeclarationVerdict.ACCEPT), now = 0L)
        assertEquals(null, forged)
    }

    @Test
    fun `an acceptance after the link closed the proposal still counts, archived`() {
        fresh()
        val row = declareRow(alice, "d1", 100L, 50_000L).copy(state = DeclarationState.CLOSED)
        val applied = DeclarationReceipt.applyAnswer(row, alice.friendId, DeclarationAnswer("d1", DeclarationVerdict.ACCEPT), now = 5L)
        assertEquals(DeclarationState.ACCEPTED, applied?.state)
        assertEquals(true, applied?.archived)
    }
}
