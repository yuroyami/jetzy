package jetzy.p2p

import jetzy.utils.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transfer direction is derived from intent (who staged files), not a user-picked mode. These
 * pin the three cases + the symmetry that lets both peers agree with no coordination round-trip.
 */
class DirectionResolverTest {

    private fun party(offering: Boolean, plat: Platform = Platform.Android, name: String = "Dev") =
        TransferParty(offeringFiles = offering, platform = plat, deviceName = name)

    @Test
    fun onlyLocalOffering_localSends() {
        assertEquals(TransferDirection.SEND, DirectionResolver.resolve(party(true), party(false)))
    }

    @Test
    fun onlyRemoteOffering_localReceives() {
        assertEquals(TransferDirection.RECEIVE, DirectionResolver.resolve(party(false), party(true)))
    }

    @Test
    fun neitherOffering_isNone_notAHang() {
        // The legacy bug: two "receivers" connect and wait forever. NONE lets the caller bail.
        assertEquals(TransferDirection.NONE, DirectionResolver.resolve(party(false), party(false)))
    }

    @Test
    fun bothOffering_resolvesDeterministicallyAndAntisymmetrically() {
        val a = party(true, Platform.Android, "Pixel")
        val b = party(true, Platform.IOS, "iPhone")
        val fromA = DirectionResolver.resolve(a, b)
        val fromB = DirectionResolver.resolve(b, a)
        // Exactly one side sends — never both, never neither.
        assertTrue((fromA == TransferDirection.SEND) != (fromB == TransferDirection.SEND), "both/neither send")
        assertTrue(fromA != TransferDirection.NONE && fromB != TransferDirection.NONE)
    }

    @Test
    fun bothOffering_isStableAcrossCalls() {
        val a = party(true, Platform.PC, "Laptop")
        val b = party(true, Platform.Android, "Tablet")
        assertEquals(DirectionResolver.resolve(a, b), DirectionResolver.resolve(a, b))
    }
}
