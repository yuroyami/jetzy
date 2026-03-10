package jetzy.viewmodel

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jetzy.managers.P2PManager
import jetzy.managers.P2PManager.Companion.platformCallback
import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode
import jetzy.p2p.P2pHandler
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPeer
import jetzy.theme.NightMode
import jetzy.ui.Screen
import jetzy.utils.NavigationDsl
import jetzy.utils.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JetzyViewmodel(p2pHandlerProvider: Lazy<P2pHandler>): ViewModel() {

    val backstack = mutableStateListOf<Screen>(Screen.MainScreen)
    val currentScreen = snapshotFlow { backstack.lastOrNull() ?: Screen.MainScreen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.MainScreen)

    var p2pManager: P2PManager? = null

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
       platformCallback.getSuitableP2pManager(peerPlatform)?.let { manager ->
           p2pManager = manager

           when (manager.discoveryMode) {
               P2pDiscoveryMode.PeerDiscovery -> navigateTo(Screen.PeerDiscoveryScreen)
               P2pDiscoveryMode.QRCode -> navigateTo(Screen.QRDiscoveryScreen)
           }
       }
    }

    val p2pHandler: P2pHandler by p2pHandlerProvider

    val nightMode = MutableStateFlow(NightMode.SYSTEM)

    val currentOperation = MutableStateFlow<P2pOperation?>(null)
    val currentPeerPlatform = MutableStateFlow<Platform?>(null)
    //val currentTransferMethod = MutableStateFlow<P2pTechnology?>(null)

    val elementsToSend = mutableStateListOf<JetzyElement>()

    val file2Send = elementsToSend.filterAsStateFlow<JetzyElement.File>()
    val folders2Send = elementsToSend.filterAsStateFlow<JetzyElement.Folder>()
    val photos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Photo>()
    val videos2Send = elementsToSend.filterAsStateFlow<JetzyElement.Video>()
    val texts2Send = elementsToSend.filterAsStateFlow<JetzyElement.Text>()

    val elementsReceived = mutableStateListOf<JetzyElement>()

    val p2pPeers = mutableStateListOf<P2pPeer>()

    val userMode = MutableStateFlow<Boolean?>(null)

    /* Popups */

    val p2pInitialPopup = mutableStateOf(false)
    val p2pQRpopup = mutableStateOf(false)
    val p2pChoosePeerPopup = mutableStateOf(false)
    val p2pTransferPopup = mutableStateOf(false)

    /* P2P-related */

    val textPeer1 = mutableStateOf<String?>(null)
    val textPeer2 = mutableStateOf<String?>(null)
    val transferProgressPrimary = mutableFloatStateOf(0f) //the overall transfer
    val transferProgressSecondary = mutableFloatStateOf(0f) //the progress of transferring each file
    val transferStatusText = mutableStateOf("") //status of transfer

    fun clearOperation() {
        elementsToSend.clear()
        currentOperation.value = null
        currentPeerPlatform.value = null
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

    inline fun <reified T : JetzyElement> SnapshotStateList<JetzyElement>.filterAsStateFlow(): StateFlow<List<T>> {
        return snapshotFlow {
            filterIsInstance<T>()
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }
}