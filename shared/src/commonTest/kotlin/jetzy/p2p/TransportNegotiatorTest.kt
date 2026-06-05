package jetzy.p2p

import jetzy.utils.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the transport-selection brain across the personas the owner called out: old & new
 * devices, old & new OSes, every platform pairing. Profiles use explicit capability masks so
 * the tests are independent of whatever OS runs them.
 */
class TransportNegotiatorTest {

    private fun mask(vararg techs: P2pTechnology): Long =
        techs.fold(0L) { acc, t -> acc or (1L shl t.capabilityBit) }

    // Modern Android with the Wi-Fi Aware chip.
    private fun modernAndroid(name: String = "Pixel") = CapabilityProfile(
        Platform.Android,
        mask(P2pTechnology.WiFiAware, P2pTechnology.WiFiDirect, P2pTechnology.NearbyConnections,
            P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN, P2pTechnology.BluetoothSpp),
        name,
    )
    // Older Android (no NAN chip / pre-8).
    private fun oldAndroid(name: String = "OldDroid") = CapabilityProfile(
        Platform.Android,
        mask(P2pTechnology.WiFiDirect, P2pTechnology.NearbyConnections,
            P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN, P2pTechnology.BluetoothSpp),
        name,
    )
    // iOS 26+ with Wi-Fi Aware.
    private fun modernIos(name: String = "iPhone") = CapabilityProfile(
        Platform.IOS,
        mask(P2pTechnology.WiFiAware, P2pTechnology.MultipeerConnectivity,
            P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN),
        name,
    )
    // Older iPhone (iOS 16, no Wi-Fi Aware).
    private fun oldIos(name: String = "OldPhone") = CapabilityProfile(
        Platform.IOS,
        mask(P2pTechnology.MultipeerConnectivity, P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN),
        name,
    )
    private fun pc(name: String = "Laptop") = CapabilityProfile(
        Platform.PC,
        mask(P2pTechnology.WiFiDirect, P2pTechnology.LocalNetworkMdns, P2pTechnology.LocalNetwork,
            P2pTechnology.HotspotLAN, P2pTechnology.BluetoothSpp),
        name,
    )

    @Test
    fun modernAndroidToModernIos_prefersWifiAware_androidHostsHotspot() {
        val a = modernAndroid(); val i = modernIos()
        assertEquals(
            listOf(P2pTechnology.WiFiAware, P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN),
            TransportNegotiator.negotiate(a, i).map { it.technology },
        )
        // Android is the only one that can create the AP → it hosts, iOS joins.
        assertEquals(Role.HOST, TransportNegotiator.negotiate(a, i).first { it.technology == P2pTechnology.HotspotLAN }.localRole)
        assertEquals(Role.CLIENT, TransportNegotiator.negotiate(i, a).first { it.technology == P2pTechnology.HotspotLAN }.localRole)
    }

    @Test
    fun oldAndroidToOldIos_fallsToMdnsThenHotspot() {
        assertEquals(
            listOf(P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN),
            TransportNegotiator.negotiate(oldAndroid(), oldIos()).map { it.technology },
        )
    }

    @Test
    fun iosToIos_excludesHotspot_sinceNeitherCanHost() {
        val order = TransportNegotiator.negotiate(modernIos("A"), modernIos("B")).map { it.technology }
        assertTrue(P2pTechnology.HotspotLAN !in order, "iOS cannot host a hotspot for another iOS")
        assertEquals(P2pTechnology.WiFiAware, order.first())
        assertTrue(P2pTechnology.MultipeerConnectivity in order)
    }

    @Test
    fun oldIosToOldIos_prefersMultipeer() {
        assertEquals(
            P2pTechnology.MultipeerConnectivity,
            TransportNegotiator.best(oldIos("A"), oldIos("B"))?.technology,
        )
    }

    @Test
    fun androidToPc_hotspotWorks_androidHosts() {
        val a = modernAndroid(); val p = pc()
        assertEquals(P2pTechnology.WiFiDirect, TransportNegotiator.best(a, p)?.technology)
        assertEquals(Role.HOST, TransportNegotiator.negotiate(a, p).first { it.technology == P2pTechnology.HotspotLAN }.localRole)
        assertEquals(Role.CLIENT, TransportNegotiator.negotiate(p, a).first { it.technology == P2pTechnology.HotspotLAN }.localRole)
    }

    @Test
    fun noSharedTransport_returnsEmpty() {
        val none = CapabilityProfile(Platform.Android, 0L, "x")
        assertTrue(TransportNegotiator.negotiate(none, modernIos()).isEmpty())
        assertNull(TransportNegotiator.best(none, modernIos()))
    }

    @Test
    fun rolesAreConsistentAndAntisymmetric() {
        val a = modernAndroid("Pixel"); val b = modernAndroid("Galaxy")
        val fromA = TransportNegotiator.negotiate(a, b).associate { it.technology to it.localRole }
        val fromB = TransportNegotiator.negotiate(b, a).associate { it.technology to it.localRole }
        assertEquals(fromA.keys, fromB.keys)
        for (tech in fromA.keys) {
            // Exactly one side hosts each transport — never both, never neither.
            assertTrue((fromA[tech] == Role.HOST) != (fromB[tech] == Role.HOST), "both/neither host ${tech.id}")
        }
    }

    @Test
    fun resultsAreOrderedByQualityDescending() {
        val matches = TransportNegotiator.negotiate(modernAndroid(), modernAndroid("Other"))
        val qualities = matches.map { it.technology.quality }
        assertEquals(qualities.sortedDescending(), qualities)
        assertEquals(P2pTechnology.WiFiAware, matches.first().technology)
    }

    // ── upgradeTarget: the "gear-shift" decision ──────────────────────────────

    @Test
    fun upgradeTarget_fromBootstrapHotspot_climbsToWifiAware() {
        // A modern Android↔iOS pair bootstraps on a hotspot (q=62) but both have Wi-Fi Aware (q=92).
        val ranked = TransportNegotiator.negotiate(modernAndroid(), modernIos())
        val target = TransportNegotiator.upgradeTarget(ranked, P2pTechnology.HotspotLAN)
        assertEquals(P2pTechnology.WiFiAware, target?.technology)
    }

    @Test
    fun upgradeTarget_whenAlreadyOnBest_isNull() {
        val ranked = TransportNegotiator.negotiate(modernAndroid(), modernIos())
        // WiFiAware is the top mutual transport here — nothing better to climb to.
        assertNull(TransportNegotiator.upgradeTarget(ranked, P2pTechnology.WiFiAware))
    }

    @Test
    fun upgradeTarget_unknownCurrentTransport_neverSuggests() {
        val ranked = TransportNegotiator.negotiate(modernAndroid(), modernIos())
        // technology == null → we can't rank ourselves, so we never blindly recommend a switch.
        assertNull(TransportNegotiator.upgradeTarget(ranked, null))
    }

    @Test
    fun upgradeTarget_picksBestStrictlyBetter_notJustNextRung() {
        // Bootstrapped on mDNS (q=80); the only thing strictly better mutual is WiFiAware (q=92).
        val ranked = TransportNegotiator.negotiate(modernAndroid(), modernIos())
        val target = TransportNegotiator.upgradeTarget(ranked, P2pTechnology.LocalNetworkMdns)
        assertEquals(P2pTechnology.WiFiAware, target?.technology)
    }

    @Test
    fun upgradeTarget_carriesTheRoleBothSidesAgreeOn() {
        // The upgrade target must come with this device's role, identical-but-mirrored on each peer.
        val a = modernAndroid(); val i = modernIos()
        val fromA = TransportNegotiator.upgradeTarget(TransportNegotiator.negotiate(a, i), P2pTechnology.HotspotLAN)
        val fromI = TransportNegotiator.upgradeTarget(TransportNegotiator.negotiate(i, a), P2pTechnology.HotspotLAN)
        assertEquals(P2pTechnology.WiFiAware, fromA?.technology)
        assertEquals(P2pTechnology.WiFiAware, fromI?.technology)
        // Wi-Fi Aware is symmetric (EITHER) → the tiebreak makes exactly one side HOST.
        assertTrue((fromA?.localRole == Role.HOST) != (fromI?.localRole == Role.HOST), "both/neither host the upgrade")
    }
}
