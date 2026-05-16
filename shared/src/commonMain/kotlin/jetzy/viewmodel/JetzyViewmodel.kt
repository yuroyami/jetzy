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
import jetzy.utils.Platform
import jetzy.utils.PreferablyIO
import jetzy.utils.platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JetzyViewmodel : ViewModel() {

    val backstack = mutableStateListOf<Screen>(Screen.MainScreen)
    val currentScreen = snapshotFlow { backstack.lastOrNull() ?: Screen.MainScreen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.MainScreen)

    var p2pManager: P2PManager? = null

    lateinit var platformCallback: P2pPlatformCallback

    val currentOperation = MutableStateFlow<P2pOperation?>(null)
    val currentPeerPlatform = MutableStateFlow<Platform?>(null)

    val isSender: Boolean
        get() = currentOperation.value == P2pOperation.SEND

    val peerPlatform: Platform
        get() = currentPeerPlatform.value ?: platform

    @NavigationDsl
    fun navigateTo(screen: Screen, doRefresh: Boolean = false, noWayToReturn: Boolean = false) {
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
        val peerPlatform: Platform,
    )

    val pendingProceed = MutableStateFlow<PendingProceed?>(null)

    /**
     * Index into [P2pPlatformCallback.getFallbackP2pManagers] for the current
     * peerPlatform. Bumped each time the user taps "try a different transport"
     * on the discovery screen; null means "we haven't started the ladder yet
     * (using the primary [P2pPlatformCallback.getSuitableP2pManager])".
     */
    val fallbackIndex = MutableStateFlow<Int?>(null)

    /**
     * Total number of fallback rungs available for the current pair, surfaced
     * for the UI to decide whether to show the "try a different transport"
     * affordance at all. 0 means "no ladder; primary only".
     */
    val fallbackCount = MutableStateFlow(0)

    fun proceedFromMainScreen(peerPlatform: Platform, operation: P2pOperation) {
        val manager = platformCallback.getSuitableP2pManager(peerPlatform) ?: return
        // Initialize early so the manager has a viewmodel ref while we're showing
        // the gate dialog (it needs `viewmodel` for `snacky` calls etc.).
        manager.initialize(viewmodel = this)

        fallbackIndex.value = null
        fallbackCount.value = platformCallback.getFallbackP2pManagers(peerPlatform).size

        if (manager.permissionRequirements.isEmpty()) {
            commitProceed(manager)
        } else {
            pendingProceed.value = PendingProceed(manager, operation, peerPlatform)
        }
    }

    /**
     * Tear down the current discovery manager and start the next one in the
     * fallback ladder, keeping the same operation + peer-platform + selected
     * files intact. The UI calls this from the "Try a different transport"
     * button on the peer-discovery screen.
     */
    fun switchToNextFallbackTransport() {
        val peer = currentPeerPlatform.value ?: return
        val ladder = platformCallback.getFallbackP2pManagers(peer)
        if (ladder.isEmpty()) return
        val next = ((fallbackIndex.value ?: -1) + 1) % ladder.size
        fallbackIndex.value = next

        val factory = ladder[next]
        val newManager = factory.invoke() ?: return
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
                    currentOperation.value ?: return@launch,
                    peer,
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
        currentPeerPlatform.value = null

        navigateTo(
            Screen.MainScreen, doRefresh = true, noWayToReturn = true
        )
    }

    /** Cancel discovery/QR screen without clearing selected files */
    fun cancelDiscovery() {
        tearDownManager()
        currentOperation.value = null
        currentPeerPlatform.value = null

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
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    var snack = SnackbarHostState()
    fun snacky(string: String, queue: Boolean = false) {
        if (!queue) snack.currentSnackbarData?.dismiss()
        viewModelScope.launch(Dispatchers.Main) {
            snack.showSnackbar(
                message = string,
                duration = SnackbarDuration.Short
            )
        }
    }
}