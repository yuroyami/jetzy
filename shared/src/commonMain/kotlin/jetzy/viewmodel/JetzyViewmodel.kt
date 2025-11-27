package jetzy.viewmodel

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jetzy.models.JetzyElement
import jetzy.p2p.P2pHandler
import jetzy.p2p.P2pMethod
import jetzy.p2p.P2pPeer
import jetzy.theme.NightMode
import jetzy.ui.Screen
import jetzy.ui.main.Operation
import jetzy.utils.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JetzyViewmodel(p2pHandlerProvider: Lazy<P2pHandler>): ViewModel() {

    val backstack = mutableStateListOf<Screen>(Screen.MainScreen)
    val currentScreen = snapshotFlow { backstack.lastOrNull() ?: Screen.MainScreen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.MainScreen)

    //TODO Sophisticatize
    fun navigateTo(screen: Screen) {
        backstack.add(screen)
    }

    val p2pHandler: P2pHandler by p2pHandlerProvider

    val nightMode = MutableStateFlow(NightMode.SYSTEM)

    val currentOperation = MutableStateFlow<Operation?>(null)
    val currentPeerPlatform = MutableStateFlow<Platform?>(null)
    val currentTransferMethod = MutableStateFlow<P2pMethod?>(null)

    val elementsToSend = mutableStateListOf<JetzyElement>()
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
}