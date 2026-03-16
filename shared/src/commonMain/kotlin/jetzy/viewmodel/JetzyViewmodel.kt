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
import jetzy.p2p.P2pDiscoveryMode
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPlatformCallback
import jetzy.theme.NightMode
import jetzy.ui.Screen
import jetzy.utils.NavigationDsl
import jetzy.utils.Platform
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
        if (noWayToReturn) {
            // Clear everything and add only the new screen
            backstack.clear()
            backstack.add(screen)
        } else {
            // Check if we're already on this screen
            if (backstack.lastOrNull() == screen) {
                if (doRefresh) {
                    // Remove and re-add to trigger refresh
                    backstack.removeLast()
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

    fun proceedFromMainScreen(peerPlatform: Platform, operation: P2pOperation) {
        val manager = platformCallback.getSuitableP2pManager(peerPlatform) ?: return
        manager.initialize(viewmodel = this)

        p2pManager = manager

        navigateTo(
            screen = when (manager.discoveryMode) {
                P2pDiscoveryMode.PeerDiscovery -> Screen.PeerDiscoveryScreen
                P2pDiscoveryMode.QRCode -> Screen.QRDiscoveryScreen
            },
            doRefresh = true,
            noWayToReturn = true
        )
    }

    val nightMode = MutableStateFlow(NightMode.SYSTEM)

    val elementsToSend = mutableStateListOf<JetzyElement>().also { list ->
        if (BuildConfig.DEBUG) {
            repeat(10) {
                list.add(JetzyElement.Text("Hello World!!!"))
            }
        }
    }

    val file2Send = elementsToSend.filterAsStateFlow<JetzyElement.File>()
    val folders2Send = elementsToSend.filterAsStateFlow<JetzyElement.Folder>()
    val photos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Photo>()
    val videos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Video>()
    val texts2Send = elementsToSend.filterAsStateFlow<JetzyElement.Text>()

    fun resetEverything() {
        elementsToSend.clear()
        currentOperation.value = null
        currentPeerPlatform.value = null
        p2pManager = null

        navigateTo(
            Screen.MainScreen, doRefresh = true, noWayToReturn = true
        )
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