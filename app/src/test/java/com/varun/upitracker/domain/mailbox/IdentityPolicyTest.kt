package com.varun.upitracker.domain.mailbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting this wrong makes messages friends already sent unreadable, and says nothing about it. The
 * last test is the one worth breaking the build over.
 */
class IdentityPolicyTest {

    private val mine = "aaaa"
    private val other = "bbbb"
    private val lost = "cccc"

    @Test
    fun `a first run makes a key with nothing to warn about`() {
        assertEquals(IdentityDecision.Generate(replacesPublished = false), IdentityPolicy.decide(null, null, null))
    }

    @Test
    fun `a published key with no private copy anywhere is replaced, with a warning`() {
        assertEquals(IdentityDecision.Generate(replacesPublished = true), IdentityPolicy.decide(null, null, lost))
    }

    @Test
    fun `a reinstall takes the Drive copy`() {
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = false, replacesPublished = false),
            IdentityPolicy.decide(null, mine, mine)
        )
    }

    @Test
    fun `a reinstall whose Drive copy is not what friends encrypt to publishes it`() {
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = true, replacesPublished = false),
            IdentityPolicy.decide(null, mine, null)
        )
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = true, replacesPublished = true),
            IdentityPolicy.decide(null, mine, lost)
        )
    }

    @Test
    fun `a key only on this phone is copied to Drive`() {
        assertEquals(
            IdentityDecision.UseLocal(uploadToDrive = true, publish = false, replacesPublished = false),
            IdentityPolicy.decide(mine, null, mine)
        )
        assertEquals(
            IdentityDecision.UseLocal(uploadToDrive = true, publish = true, replacesPublished = false),
            IdentityPolicy.decide(mine, null, null)
        )
    }

    @Test
    fun `everything agreeing needs no work`() {
        assertEquals(
            IdentityDecision.UseLocal(uploadToDrive = false, publish = false, replacesPublished = false),
            IdentityPolicy.decide(mine, mine, mine)
        )
    }

    @Test
    fun `when phone and Drive disagree, the key friends encrypt to wins`() {
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = false, replacesPublished = false),
            IdentityPolicy.decide(mine, other, other)
        )
        assertEquals(
            IdentityDecision.UseLocal(uploadToDrive = true, publish = false, replacesPublished = false),
            IdentityPolicy.decide(mine, other, mine)
        )
    }

    @Test
    fun `when neither is what friends encrypt to, Drive wins so every install converges`() {
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = true, replacesPublished = true),
            IdentityPolicy.decide(mine, other, lost)
        )
        assertEquals(
            IdentityDecision.RestoreFromDrive(publish = true, replacesPublished = false),
            IdentityPolicy.decide(mine, other, null)
        )
    }

    @Test
    fun `no decision ever asks for a private key that does not exist`() {
        val choices = listOf(null, mine, other, lost)
        for (local in choices) for (drive in choices) for (published in choices) {
            val case = "local=$local drive=$drive published=$published"
            when (IdentityPolicy.decide(local, drive, published)) {
                is IdentityDecision.UseLocal -> assertTrue(case, local != null)
                is IdentityDecision.RestoreFromDrive -> assertTrue(case, drive != null)
                is IdentityDecision.Generate -> assertTrue(case, local == null && drive == null)
            }
        }
    }
}
