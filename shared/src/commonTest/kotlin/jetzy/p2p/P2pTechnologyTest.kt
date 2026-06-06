package jetzy.p2p

import jetzy.utils.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Registry invariants the whole negotiation rests on. The load-bearing one is hostAffinity
 * SYMMETRY: if `A.hostAffinity(a,b)` and `B.hostAffinity(b,a)` ever disagreed on the host, two
 * peers would both stand up a server (or neither would) and the connection would hang. The
 * negotiator assumes this everywhere, so it must hold for every transport × every platform pair.
 */
class P2pTechnologyTest {

    private fun mirror(h: HostAffinity): HostAffinity = when (h) {
        HostAffinity.LOCAL -> HostAffinity.REMOTE
        HostAffinity.REMOTE -> HostAffinity.LOCAL
        HostAffinity.EITHER -> HostAffinity.EITHER
        HostAffinity.NEITHER -> HostAffinity.NEITHER
    }

    @Test
    fun capabilityBitsAreUniqueAndInLongRange() {
        val bits = P2pTechnology.allMethods.map { it.capabilityBit }
        assertEquals(bits.size, bits.toSet().size, "duplicate capabilityBit → mask collision")
        assertTrue(bits.all { it in 0..63 }, "capabilityBit must fit a Long")
    }

    @Test
    fun hostAffinityIsSymmetricForEveryTransportAndPair() {
        for (tech in P2pTechnology.allMethods) {
            for (p1 in Platform.entries) {
                for (p2 in Platform.entries) {
                    assertEquals(
                        tech.hostAffinity(p1, p2),
                        mirror(tech.hostAffinity(p2, p1)),
                        "${tech.id}.hostAffinity not symmetric for $p1 <-> $p2",
                    )
                }
            }
        }
    }

    @Test
    fun toCapabilities_ignoresUnknownBits_andNeverCrashes() {
        with(P2pTechnology) {
            assertEquals(allMethods.toSet(), (-1L).toCapabilities()) // every bit set → only known techs
            assertEquals(emptySet(), 0L.toCapabilities())
        }
    }

    @Test
    fun displayNameIsNonBlankForEveryTransport() {
        assertTrue(P2pTechnology.allMethods.all { it.displayName.isNotBlank() })
    }

    @Test
    fun reservedTransportsWithNoManagerAreNeverAdvertised() {
        // No NearbyConnectionsP2PM / BLE manager exists; advertising them would let the negotiator
        // select a phantom transport. If you wire a manager and flip these true, delete this guard.
        for (p in Platform.entries) {
            assertFalse(P2pTechnology.NearbyConnections.isLocallyCapable(p), "NearbyConnections has no manager")
            assertFalse(P2pTechnology.Bluetooth.isLocallyCapable(p), "Bluetooth/BLE has no manager")
        }
    }

    @Test
    fun negotiatedRolesAreConsistentAcrossEveryPlatformPair() {
        val fullMask = P2pTechnology.allMethods.fold(0L) { acc, t -> acc or (1L shl t.capabilityBit) }
        val profiles = Platform.entries.map { CapabilityProfile(it, fullMask, "dev-${it.name}") }
        for (a in profiles) {
            for (b in profiles) {
                if (a.platform == b.platform) continue // identical keys are the documented degenerate tie
                val fromA = TransportNegotiator.negotiate(a, b).associate { it.technology to it.localRole }
                val fromB = TransportNegotiator.negotiate(b, a).associate { it.technology to it.localRole }
                assertEquals(fromA.keys, fromB.keys, "shared transport set differs for ${a.platform}/${b.platform}")
                for (t in fromA.keys) {
                    assertTrue(
                        (fromA[t] == Role.HOST) != (fromB[t] == Role.HOST),
                        "${t.id}: both or neither host for ${a.platform}/${b.platform}",
                    )
                }
            }
        }
    }
}
