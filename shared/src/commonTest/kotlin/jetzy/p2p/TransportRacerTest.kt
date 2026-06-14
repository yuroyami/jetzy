package jetzy.p2p

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Happy-Eyeballs executor ([TransportRacer]): first-to-connect wins, every loser is torn down,
 * losers still mid-connect are cancelled, all-fail yields null. Pure coroutine logic on virtual
 * time — the device-validated half (real managers connecting over real radios) is out of scope here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportRacerTest {

    private fun match(tech: P2pTechnology, role: Role = Role.CLIENT) = TransportMatch(tech, role)
    private fun attempt(tech: P2pTechnology, delayMs: Long) = ConnectAttempt(match(tech), delayMs)

    @Test
    fun emptyAttempts_returnsNull() = runTest {
        val result = TransportRacer.race<String>(
            attempts = emptyList(),
            connect = { error("must not be called") },
            close = { error("must not be called") },
        )
        assertNull(result)
    }

    @Test
    fun singleSuccess_returnsThatWinner_andClosesNothing() = runTest {
        val closed = mutableListOf<String>()
        val result = TransportRacer.race(
            attempts = listOf(attempt(P2pTechnology.LocalNetworkMdns, 0)),
            connect = { "link-${it.technology.id}" },
            close = { closed += it },
        )
        assertEquals(P2pTechnology.LocalNetworkMdns, result?.match?.technology)
        assertEquals("link-local_network_mdns", result?.link)
        assertTrue(closed.isEmpty(), "the winning link must never be closed")
    }

    @Test
    fun fastestToConnectWins_evenWhenListedFirst_andTheSlowLoserIsCancelled() = runTest {
        val closed = mutableListOf<String>()
        // mDNS is attempt 0 but its connect takes 100ms; Hotspot is attempt 1 but connects in 10ms.
        val result = TransportRacer.race(
            attempts = listOf(
                attempt(P2pTechnology.LocalNetworkMdns, 0),
                attempt(P2pTechnology.HotspotLAN, 0),
            ),
            connect = { m ->
                when (m.technology) {
                    P2pTechnology.LocalNetworkMdns -> { delay(100); "slow" }
                    else -> { delay(10); "fast" }
                }
            },
            close = { closed += it },
        )
        assertEquals(P2pTechnology.HotspotLAN, result?.match?.technology)
        assertEquals("fast", result?.link)
        // The slow attempt was cancelled mid-connect (winner resolved at t=10), so it never
        // produced a link and nothing leaked.
        assertTrue(closed.isEmpty(), "a loser cancelled mid-connect produces no link to close")
    }

    @Test
    fun aLoserThatConnectsAfterTheWinner_isClosed() = runTest {
        val closed = mutableListOf<String>()
        // Both resolve at t=10; the first-listed attempt claims the win, the second's link must close.
        val result = TransportRacer.race(
            attempts = listOf(
                attempt(P2pTechnology.LocalNetworkMdns, 0),
                attempt(P2pTechnology.MultipeerConnectivity, 0),
            ),
            connect = { m -> delay(10); "link-${m.technology.id}" },
            close = { closed += it },
        )
        assertEquals(P2pTechnology.LocalNetworkMdns, result?.match?.technology)
        assertEquals(listOf("link-multipeer"), closed, "the losing link must be torn down exactly once")
    }

    @Test
    fun allAttemptsFail_returnsNull() = runTest {
        val closed = mutableListOf<String>()
        val result = TransportRacer.race<String>(
            attempts = listOf(
                attempt(P2pTechnology.LocalNetworkMdns, 0),
                attempt(P2pTechnology.HotspotLAN, 100),
            ),
            connect = { null },
            close = { closed += it },
        )
        assertNull(result)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun overallTimeout_returnsNull_whenNoAttemptConnectsInTime() = runTest {
        val result = TransportRacer.race(
            attempts = listOf(attempt(P2pTechnology.LocalNetworkMdns, 0)),
            overallTimeoutMs = 50,
            connect = { delay(10_000); "never" },
            close = { },
        )
        assertNull(result)
    }

    @Test
    fun drivenByScheduleOutput_safeTransportWinsBeforeDisruptiveEvenFires() = runTest {
        val closed = mutableListOf<String>()
        // schedule() staggers HotspotLAN (disruptive) AFTER LocalNetworkMdns (safe). Both connect
        // instantly, so the safe one — started first — must win and the disruptive one never opens.
        val schedule = TransportCoordinator.schedule(
            matches = listOf(match(P2pTechnology.HotspotLAN), match(P2pTechnology.LocalNetworkMdns)),
            stepMs = 250,
        )
        val result = TransportRacer.race(
            attempts = schedule,
            connect = { "link-${it.technology.id}" },
            close = { closed += it },
        )
        assertEquals(P2pTechnology.LocalNetworkMdns, result?.match?.technology)
        assertTrue(closed.isEmpty(), "the disruptive attempt should never have been started")
    }
}
