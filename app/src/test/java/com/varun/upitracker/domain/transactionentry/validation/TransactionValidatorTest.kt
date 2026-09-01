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
}
