package jetzy.viewmodel

import Jetzy.shared.BuildConfig
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jetzy.managers.P2PManager
import jetzy.models.JetzyElement
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPlatformCallback
import jetzy.theme.NightMode
import jetzy.ui.Screen
import jetzy.utils.NavigationDsl
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

class JetzyViewmodel : ViewModel() {

    val backstack = mutableStateListOf<Screen>(Screen.MainScreen)
    val currentScreen = snapshotFlow { backstack.lastOrNull() ?: Screen.MainScreen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.MainScreen)

    var p2pManager: P2PManager? = null

    lateinit var platformCallback: P2pPlatformCallback

    val currentOperation = MutableStateFlow<P2pOperation?>(null)

    val isSender: Boolean
        get() = currentOperation.value == P2pOperation.SEND

    @NavigationDsl
    fun navigateTo(screen: Screen, doRefresh: Boolean = false, noWayToReturn: Boolean = false) {
        // Navigation mutates Compose snapshot state + the snackbar host, both of which expect the
        // main thread. Several callers (beginTransfer, fallback switch, resume) run on background
        // dispatchers, so marshal here. Main.immediate runs synchronously when already on main.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            // Dismiss any active snackbar when navigating
            snack.currentSnackbarData?.dismiss()

            if (noWayToReturn) {
                // Clear everything and add only the new screen
                backstack.clear()
                backstack.add(screen)
            } else {
                // Check if we're already on this screen
                if (backstack.lastOrNull() == screen) {
                    if (doRefresh) {
                        // Remove and re-add to trigger refresh
                        backstack.removeAt(backstack.lastIndex)
                        backstack.add(screen)
                    } else {
                        // else: do nothing, we're already there
                    }
                } else {
                    // Navigate to new screen
                    backstack.add(screen)
                }
            }
        }
    }

    /**
     * Central handler for system back, wired to NavDisplay's `onBack`. Leaf screens that own a
     * live [P2PManager] (discovery / QR / transfer) must tear it down — closing sockets, stopping
     * discovery/advertising + the foreground service, and purging unsaved temp files — instead of
     * silently popping the backstack and leaking the session. NavDisplay only invokes this while
     * the backstack has more than one entry, so the root (Main) screen still falls through to the
     * OS (exit app).
     */
    @NavigationDsl
    fun onSystemBack() {
        when (backstack.lastOrNull()) {
            Screen.PeerDiscoveryScreen,
            Screen.QRDiscoveryScreen,
            Screen.TransferScreen -> cancelDiscovery() // tears the manager down, returns to Main
            Screen.FilePickingScreen -> navigateTo(Screen.MainScreen, noWayToReturn = true)
            else -> if (backstack.size > 1) backstack.removeAt(backstack.lastIndex)
        }
    }

    /**
     * What's parked between the user tapping "Proceed" on the main screen and the
     * permission-gate dialog handing control back to us. The dialog reads the
     * manager's [permissionRequirements][jetzy.managers.P2PManager.permissionRequirements]
     * and only when every requirement is satisfied does the user confirm, at which
     * point [confirmPendingProceed] runs and we finally navigate.
     */
    data class PendingProceed(
        val manager: jetzy.managers.P2PManager,
        val operation: P2pOperation,
    )

    val pendingProceed = MutableStateFlow<PendingProceed?>(null)

    /**
     * Index into [P2pPlatformCallback.getDefaultFallbackManagers] for the current attempt. Bumped
     * each time the user taps "try a different transport" on the discovery screen; null means "we
     * haven't started the ladder yet (still on the primary [P2pPlatformCallback.getDefaultP2pManager])".
     */
    val fallbackIndex = MutableStateFlow<Int?>(null)

    /**
     * Total number of fallback rungs available for the current pair, surfaced
     * for the UI to decide whether to show the "try a different transport"
     * affordance at all. 0 means "no ladder; primary only".
     */
    val fallbackCount = MutableStateFlow(0)

    /**
     * Start a session with **no up-front Send/Receive or platform pick** — the gate-free home flow.
     * Direction is *derived* from intent (staged files ⇒ we offer to send; nothing staged ⇒ we
     * expect to receive); the wire's [jetzy.p2p.DirectionResolver] stays authoritative and may flip
     * it once both HELLOs are exchanged. The bootstrap transport is the platform-agnostic
     * [P2pPlatformCallback.getDefaultP2pManager] (mDNS), with the per-host ladder available behind
     * "Try a different transport". Setting [currentOperation] now keeps every isSender-driven
     * surface correct through discovery.
     */
    fun proceed() {
        val operation = if (elementsToSend.isNotEmpty()) P2pOperation.SEND else P2pOperation.RECEIVE
        currentOperation.value = operation

        val manager = platformCallback.getDefaultP2pManager() ?: return
        // Initialize early so the manager has a viewmodel ref while we're showing
        // the gate dialog (it needs `viewmodel` for `snacky` calls etc.).
        manager.initialize(viewmodel = this)

        fallbackIndex.value = null
        fallbackCount.value = platformCallback.getDefaultFallbackManagers().size

        if (manager.permissionRequirements.isEmpty()) {
            commitProceed(manager)
        } else {
            pendingProceed.value = PendingProceed(manager, operation)
        }
    }

    /**
     * Tear down the current discovery manager and start the next one in the
     * fallback ladder, keeping the same operation + peer-platform + selected
     * files intact. The UI calls this from the "Try a different transport"
     * button on the peer-discovery screen.
     */
    fun switchToNextFallbackTransport() {
        val ladder = platformCallback.getDefaultFallbackManagers()
        if (ladder.isEmpty()) return
        // B28: clamp, don't wrap. Modulo cycled 1→2→…→0→1 forever, so "try a different transport"
        // silently looped the user back through transports that already failed with no terminal
        // state. Stop at the last rung and say so.
        //
        // Also skip rungs that resolve to the transport we're already on: rung 0 of every ladder
        // is mDNS — the default that just found nothing — so the first tap used to "switch" to a
        // repeat of the failure, and hardware-dependent rungs duplicate on common devices (e.g.
        // WiFiDirect twice on a non-NAN Android). Skipped candidates were never initialized, so
        // dropping the reference is enough.
        val currentTechnology = p2pManager?.technology
        var next = (fallbackIndex.value ?: -1) + 1
        var newManager: P2PManager? = null
        while (next < ladder.size) {
            val candidate = ladder[next].invoke()
            if (candidate != null &&
                (candidate.technology == null || candidate.technology != currentTechnology)
            ) {
                newManager = candidate
                break
            }
            next++
        }
        if (newManager == null) {
            fallbackIndex.value = ladder.size // park at the end so further taps short-circuit
            snacky("No other transports to try. Make sure both devices are on the same Wi-Fi, or move them closer.")
            return
        }
        fallbackIndex.value = next
        newManager.initialize(viewmodel = this)

        val oldManager = p2pManager
        p2pManager = newManager

        viewModelScope.launch(PreferablyIO) {
            // Tear down the previous manager without dropping the operation/files state.
            runCatching { oldManager?.cleanup() }
            // If the new manager has permissions, route through the gate dialog;
            // otherwise navigate straight to the matching discovery screen.
            if (newManager.permissionRequirements.isEmpty()) {
                navigateTo(
                    screen = when (newManager.usesPeerDiscovery) {
                        true -> Screen.PeerDiscoveryScreen
                        false -> Screen.QRDiscoveryScreen
                    },
                    doRefresh = true,
                    noWayToReturn = true,
                )
            } else {
                pendingProceed.value = PendingProceed(
                    newManager,
                    currentOperation.value ?: P2pOperation.RECEIVE,
                )
            }
        }
    }

    /** Called by the permission-gate dialog once every requirement is satisfied. */
    fun confirmPendingProceed() {
        val pending = pendingProceed.value ?: return
        pendingProceed.value = null
        commitProceed(pending.manager)
    }

    /** Called when the user backs out of the gate dialog. */
    fun cancelPendingProceed() {
        val pending = pendingProceed.value ?: return
        pendingProceed.value = null
        if (pending.manager === p2pManager) {
            // Cancelling the gate mid-fallback-switch: the discovery screen is already bound to
            // this manager (switchToNextFallbackTransport assigns it before the gate resolves).
            // Tear down and return to Main like any other cancel — the old behavior cleaned the
            // manager but left the screen running on its corpse with the rung consumed.
            cancelDiscovery()
            return
        }
        viewModelScope.launch(PreferablyIO) {
            runCatching { pending.manager.cleanup() }
        }
    }

    private fun commitProceed(manager: jetzy.managers.P2PManager) {
        p2pManager = manager
        runCatching { platformCallback.startBackgroundService() }
        navigateTo(
            screen = when (manager.usesPeerDiscovery) {
                true -> Screen.PeerDiscoveryScreen
                false -> Screen.QRDiscoveryScreen
            },
            doRefresh = true,
            noWayToReturn = true
        )
    }

    val nightMode = MutableStateFlow(NightMode.SYSTEM)

    val elementsToSend = mutableStateListOf<JetzyElement>().also { list ->
        if (BuildConfig.DEBUG) {
            repeat(10) {
                //list.add(JetzyElement.Text("Hello World!!!"))
            }
        }
    }

    val file2Send = elementsToSend.filterAsStateFlow<JetzyElement.File>()
    val folders2Send = elementsToSend.filterAsStateFlow<JetzyElement.Folder>()
    val photos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Photo>()
    val videos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Video>()
    val texts2Send = elementsToSend.filterAsStateFlow<JetzyElement.Text>()

    fun resetEverything() {
        tearDownManager()
        elementsToSend.clear()
        currentOperation.value = null

        navigateTo(
            Screen.MainScreen, doRefresh = true, noWayToReturn = true
        )
    }

    /**
     * Abort a session that can't proceed past the handshake (protocol error, dropped connection,
     * or a resolved direction of NONE — neither side staged files). Returns to Main with a message
     * and *keeps* any staged files so the user can immediately retry. Used by [jetzy.managers
     * .P2PManager.beginTransfer]'s terminal branches, which otherwise would strand the user on the
     * "Connecting…" screen (it gates on the manifest, which never arrives in these cases).
     */
    fun abortSession(message: String) {
        snacky(message, queue = true) // queued so the imminent navigate-to-Main doesn't dismiss it
        cancelDiscovery()
    }

    /** Cancel discovery/QR screen without clearing selected files */
    fun cancelDiscovery() {
        tearDownManager()
        currentOperation.value = null

        navigateTo(
            Screen.MainScreen, doRefresh = true, noWayToReturn = true
        )
    }

    /** Release the active manager, closing sockets and deleting any un-saved received files. */
    private fun tearDownManager() {
        val manager = p2pManager ?: return
        p2pManager = null
        viewModelScope.launch(PreferablyIO) {
            runCatching { manager.cleanup() }
        }
    }

    /**
     * The host (activity/window) is going away for real — the last reliable signal to tear the
     * session down. Without this, swiping the task away mid-session left sockets open and the
     * Android foreground service running headless with its "transferring" notification until
     * force-stop ([P2PManager.cleanup] is the only thing that stops it).
     *
     * viewModelScope is already cancelled by the time onCleared runs, so the teardown rides a
     * detached scope; it's a short close-sockets/stop-service burst with no UI to return to.
     */
    override fun onCleared() {
        val manager = p2pManager
        p2pManager = null
        pendingProceed.value = null
        if (manager != null) {
            CoroutineScope(PreferablyIO).launch {
                runCatching { manager.cleanup() }
            }
        }
        super.onCleared()
    }

    /**
     * Restart the QR/discovery flow *without* dropping the session id or staged partials.
     * The same underlying [P2PManager] is kept so its receiverLedger survives; on the next
     * handshake the sender's QR will carry the same sessionId and the protocol will ACK
     * with RESUME, picking up exactly where the previous attempt stalled.
     */
    fun resumeDiscovery() {
        val manager = p2pManager ?: return
        viewModelScope.launch(PreferablyIO) {
            runCatching { manager.prepareForResume() }
            navigateTo(
                screen = when (manager.usesPeerDiscovery) {
                    true -> Screen.PeerDiscoveryScreen
                    false -> Screen.QRDiscoveryScreen
                },
                doRefresh = true,
                noWayToReturn = true,
            )
        }
    }

    inline fun <reified T : JetzyElement> SnapshotStateList<JetzyElement>.filterAsStateFlow(): StateFlow<List<T>> {
        return snapshotFlow {
            filterIsInstance<T>()
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    var snack = SnackbarHostState()
    private var snackbarJob: Job? = null
    /** [snacky] with a string resource — usable from non-composable contexts (picker callbacks). */
    fun snackyRes(res: StringResource, vararg args: Any, queue: Boolean = false) {
        if (!queue) {
            snack.currentSnackbarData?.dismiss()
            snackbarJob?.cancel()
        }
        snackbarJob = viewModelScope.launch(Dispatchers.Main) {
            snack.showSnackbar(message = getString(res, *args), duration = SnackbarDuration.Short)
        }
    }

    fun snacky(string: String, queue: Boolean = false) {
        if (!queue) {
            snack.currentSnackbarData?.dismiss()
            snackbarJob?.cancel()
        }
        snackbarJob = viewModelScope.launch(Dispatchers.Main) {
            snack.showSnackbar(
                message = string,
                duration = SnackbarDuration.Short
            )
        }
    }
}