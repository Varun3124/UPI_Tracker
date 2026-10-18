package com.varun.upitracker.domain.transactionentry.validation

import com.varun.upitracker.ui.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {

    private val validator = TransactionValidator()

    @Test
    fun validateActors_allowsMeToMe() {
        // ME -> ME is an account transfer; AccountTransferValidator owns its rules.
        val result = validator.validateActors(
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.ME,
            payerLabel = "Me",
            payeeLabel = "Me"
        )

        assertTrue(result.isValid)
    }

    @Test
    fun validateActors_stillRejectsSameNamedFriends() {
        val result = validator.validateActors(
            payerActorType = ActorType.FRIEND,
            payeeActorType = ActorType.FRIEND,
            payerLabel = "Alex",
            payeeLabel = "alex"
        )

        assertFalse(result.isValid)
        assertEquals("Payer and payee must be different", result.message)
    }

    @Test
    fun validateActors_allowsDistinctFriends() {
        val result = validator.validateActors(
            payerActorType = ActorType.FRIEND,
            payeeActorType = ActorType.FRIEND,
            payerLabel = "Alex",
            payeeLabel = "Sam"
        )

        assertTrue(result.isValid)
    }

    @Test
    fun validateActors_rejectsBlankLabels() {
        assertEquals(
            "Enter a payer",
            validator.validateActors(ActorType.FRIEND, ActorType.MERCHANT, "", "Shop").message
        )
        assertEquals(
            "Enter a payee",
            validator.validateActors(ActorType.ME, ActorType.MERCHANT, "Me", " ").message
        )
    }

    private fun row(key: String, label: String, amount: Long, primary: Boolean = false) =
        ShareValidationRow(key = key, label = label, amountPaise = amount, isPrimary = primary)

    @Test
    fun validateShares_rejectsTheSameFriendTwiceOnASide() {
        val result = validator.validateShares(
            amountPaise = 300L,
            payerActorType = ActorType.FRIEND,
            payeeActorType = ActorType.MERCHANT,
            payerRows = listOf(row("F:4", "Bob", 300L, primary = true), row("F:4", "Bob", 0L)),
            payeeRows = listOf(row("M:1", "Cafe", 300L, primary = true))
        )

        assertFalse(result.isValid)
        assertEquals("Bob is on the payer side twice", result.message)
    }

    @Test
    fun validateShares_catchesAPrimaryTypedByNameThatIsAlreadyBelow() {
        // A chapter's template leaves the primary blank with the members below it. Typed by hand,
        // the primary has no friend behind it until saving finds one, so only the name gives it away.
        val result = validator.validateShares(
            amountPaise = 300L,
            payerActorType = ActorType.ME,
            payeeActorType = ActorType.FRIEND,
            payerRows = listOf(row("ME", "Me", 300L, primary = true)),
            payeeRows = listOf(row("F:?", "bob", 150L, primary = true), row("F:4", "Bob", 150L))
        )

        assertEquals("Bob is on the payee side twice", result.message)
    }

    @Test
    fun validateShares_rejectsMeTwiceOnASide() {
        val result = validator.validateShares(
            amountPaise = 300L,
            payerActorType = ActorType.FRIEND,
            payeeActorType = ActorType.MERCHANT,
            payerRows = listOf(row("F:4", "Bob", 100L, primary = true), row("ME", "Me", 100L), row("ME", "Me", 100L)),
            payeeRows = listOf(row("M:1", "Cafe", 300L, primary = true))
        )

        assertEquals("You are on the payer side twice", result.message)
    }

    @Test
    fun validateShares_allowsTheSameFriendOnOppositeSides() {
        // Paid for it and also had a share of it: one person at both ends, which is fine.
        val result = validator.validateShares(
            amountPaise = 300L,
            payerActorType = ActorType.FRIEND,
            payeeActorType = ActorType.ME,
            payerRows = listOf(row("F:4", "Bob", 300L, primary = true)),
            payeeRows = listOf(row("ME", "Me", 200L, primary = true), row("F:4", "Bob", 100L))
        )

        assertTrue(result.isValid)
    }
}
