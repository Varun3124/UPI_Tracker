package com.varun.upitracker.domain.parcel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Working out who sent a pasted parcel, strongest evidence first. */
class ParcelSenderTest {

    private fun parcel(alias: String? = "Asha", upiId: String? = "asha@okhdfc") =
        Parcel(ParcelFormat.VERSION, "tok3n", emptyList(), senderAlias = alias, senderUpiId = upiId)

    private fun resolve(
        parcel: Parcel,
        remembered: Long? = null,
        byUpi: Long? = null,
        byName: Long? = null
    ) = ParcelSender.resolve(
        parcel = parcel,
        rememberedFriendId = { token -> remembered.takeIf { token == "tok3n" } },
        friendByUpiId = { upi -> byUpi.takeIf { upi == "asha@okhdfc" } },
        friendByName = { name -> byName.takeIf { name == "Asha" } }
    )

    @Test
    fun `where this sender's earlier parcels went outranks everything`() {
        assertEquals(
            SenderChoice.Existing(1L, SenderChoice.How.REMEMBERED),
            resolve(parcel(), remembered = 1L, byUpi = 2L, byName = 3L)
        )
    }

    @Test
    fun `a UPI ID outranks a name`() {
        assertEquals(SenderChoice.Existing(2L, SenderChoice.How.UPI_ID), resolve(parcel(), byUpi = 2L, byName = 3L))
    }

    @Test
    fun `a name is used when nothing stronger matches`() {
        assertEquals(SenderChoice.Existing(3L, SenderChoice.How.NAME), resolve(parcel(), byName = 3L))
    }

    @Test
    fun `nobody matching makes a new person by the alias`() {
        assertEquals(SenderChoice.New("Asha"), resolve(parcel()))
    }

    @Test
    fun `no UPI ID simply skips that step`() {
        assertEquals(SenderChoice.Existing(3L, SenderChoice.How.NAME), resolve(parcel(upiId = null), byUpi = 2L, byName = 3L))
    }

    @Test
    fun `a parcel from an older app names nobody, unless this sender was seen before`() {
        assertEquals(SenderChoice.Unknown, resolve(parcel(alias = null, upiId = null), byName = 3L))
        assertEquals(
            SenderChoice.Existing(1L, SenderChoice.How.REMEMBERED),
            resolve(parcel(alias = null, upiId = null), remembered = 1L)
        )
    }
}
