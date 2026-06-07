package jetzy.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure Happy-Eyeballs schedule (HACK 3) + the disruption classification it relies on. */
class TransportCoordinatorTest {

    private fun m(t: P2pTechnology) = TransportMatch(t, Role.CLIENT)

    @Test
    fun schedule_safeFirst_disruptiveLast_staggered() {
        val matches = listOf(m(P2pTechnology.WiFiAware), m(P2pTechnology.LocalNetworkMdns), m(P2pTechnology.HotspotLAN))
        val s = TransportCoordinator.schedule(matches, stepMs = 250)
        assertEquals(
            listOf(P2pTechnology.WiFiAware, P2pTechnology.LocalNetworkMdns, P2pTechnology.HotspotLAN),
            s.map { it.match.technology },
        )
        assertEquals(listOf(0L, 250L, 500L), s.map { it.startDelayMs })
    }

    @Test
    fun schedule_movesDisruptiveAfterSafe_regardlessOfInputOrder() {
        // Hotspot (disruptive) listed first, but mDNS (safe) must race first so the hotspot only
        // fires if nothing clean connected — and never overlaps a safe bringup.
        val s = TransportCoordinator.schedule(listOf(m(P2pTechnology.HotspotLAN), m(P2pTechnology.LocalNetworkMdns)))
        assertEquals(P2pTechnology.LocalNetworkMdns, s.first().match.technology)
        assertEquals(0L, s.first().startDelayMs)
        assertEquals(P2pTechnology.HotspotLAN, s.last().match.technology)
    }

    @Test
    fun schedule_empty_isEmpty() {
        assertTrue(TransportCoordinator.schedule(emptyList()).isEmpty())
    }

    @Test
    fun schedule_respectsCustomStep() {
        val s = TransportCoordinator.schedule(
            listOf(m(P2pTechnology.WiFiAware), m(P2pTechnology.MultipeerConnectivity)),
            stepMs = 100,
        )
        assertEquals(listOf(0L, 100L), s.map { it.startDelayMs })
    }

    @Test
    fun disruptsLocalConnectivity_trueOnlyForHotspotAndWifiDirect() {
        assertTrue(P2pTechnology.HotspotLAN.disruptsLocalConnectivity)
        assertTrue(P2pTechnology.WiFiDirect.disruptsLocalConnectivity)
        assertFalse(P2pTechnology.WiFiAware.disruptsLocalConnectivity)
        assertFalse(P2pTechnology.LocalNetworkMdns.disruptsLocalConnectivity)
        assertFalse(P2pTechnology.MultipeerConnectivity.disruptsLocalConnectivity)
        assertFalse(P2pTechnology.BluetoothSpp.disruptsLocalConnectivity)
    }
}
