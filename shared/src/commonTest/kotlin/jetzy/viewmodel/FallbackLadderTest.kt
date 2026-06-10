package jetzy.viewmodel

import jetzy.managers.P2PManager
import jetzy.p2p.P2pPlatformCallback
import jetzy.p2p.P2pTechnology
import jetzy.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The "Try a different transport" ladder state machine: B28's clamp (no modulo wrap), the
 * skip-current-transport rule (rung 0 is mDNS — the default that just failed), duplicate-rung
 * skipping, and the gate-cancel-mid-switch recovery. All pure viewmodel state — the managers
 * are inert fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FallbackLadderTest {

    private class FakeManager(override val technology: P2pTechnology?) : P2PManager()

    private class FakeCallback(
        private val ladder: List<() -> P2PManager?>,
    ) : P2pPlatformCallback {
        override fun getDefaultP2pManager(): P2PManager? = null
        override fun getDefaultFallbackManagers(): List<() -> P2PManager?> = ladder
    }

    @BeforeTest
    fun setUp() {
        // navigateTo/snacky launch on Dispatchers.Main.immediate.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vmWith(ladder: List<() -> P2PManager?>, current: P2pTechnology?): JetzyViewmodel {
        val vm = JetzyViewmodel()
        vm.platformCallback = FakeCallback(ladder)
        vm.p2pManager = FakeManager(current).also { it.initialize(vm) }
        return vm
    }

    @Test
    fun firstSwitch_skipsTheRungMatchingTheCurrentTransport() {
        val vm = vmWith(
            ladder = listOf(
                { FakeManager(P2pTechnology.LocalNetworkMdns) }, // rung 0 == current → must skip
                { FakeManager(P2pTechnology.WiFiDirect) },
            ),
            current = P2pTechnology.LocalNetworkMdns,
        )
        vm.switchToNextFallbackTransport()
        assertEquals(P2pTechnology.WiFiDirect, vm.p2pManager?.technology)
        assertEquals(1, vm.fallbackIndex.value)
    }

    @Test
    fun duplicateRungs_areSkippedOnTheNextSwitch() {
        // Non-NAN Android ladder shape: WiFiDirect appears twice back-to-back.
        val vm = vmWith(
            ladder = listOf(
                { FakeManager(P2pTechnology.LocalNetworkMdns) },
                { FakeManager(P2pTechnology.WiFiDirect) },
                { FakeManager(P2pTechnology.WiFiDirect) },
                { FakeManager(P2pTechnology.BluetoothSpp) },
            ),
            current = P2pTechnology.LocalNetworkMdns,
        )
        vm.switchToNextFallbackTransport()
        assertEquals(P2pTechnology.WiFiDirect, vm.p2pManager?.technology)
        vm.switchToNextFallbackTransport()
        assertEquals(
            P2pTechnology.BluetoothSpp, vm.p2pManager?.technology,
            "the second WiFiDirect rung is a repeat of what just failed and must be skipped",
        )
    }

    @Test
    fun exhaustedLadder_parksAtTheEnd_neverWraps() {
        val vm = vmWith(
            ladder = listOf({ FakeManager(P2pTechnology.LocalNetworkMdns) }),
            current = P2pTechnology.LocalNetworkMdns,
        )
        val before = vm.p2pManager
        vm.switchToNextFallbackTransport() // only rung == current → exhausted immediately
        assertSame(before, vm.p2pManager, "exhaustion must not replace the live manager")
        assertEquals(1, vm.fallbackIndex.value, "index parks at ladder.size")
        vm.switchToNextFallbackTransport() // B28: a further tap must not modulo-wrap to rung 0
        assertSame(before, vm.p2pManager)
        assertEquals(1, vm.fallbackIndex.value)
    }

    @Test
    fun cancellingTheGateMidSwitch_returnsToMainInsteadOfStrandingTheScreen() {
        val vm = vmWith(ladder = emptyList(), current = P2pTechnology.LocalNetworkMdns)
        // Simulate the switch flow having assigned the new manager and parked the gate.
        val pendingManager = vm.p2pManager!!
        vm.pendingProceed.value = JetzyViewmodel.PendingProceed(pendingManager, jetzy.p2p.P2pOperation.RECEIVE)
        vm.cancelPendingProceed()
        assertEquals(null, vm.p2pManager, "the manager bound to the live screen must be torn down")
        assertEquals(Screen.MainScreen, vm.backstack.lastOrNull(), "and the user routed back to Main")
    }
}
