package jetz.common.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import jetz.common.p2p.P2pPeer
import jetz.common.picking.MPFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ComposeViewmodel: ViewModel() {

    val files = mutableStateListOf<MPFile<Any>>()

    val userMode = MutableStateFlow<Boolean?>(null)

    val mainScope = CoroutineScope(Dispatchers.Main)
    val ioScope = CoroutineScope(Dispatchers.IO)

    var snack = SnackbarHostState()

    /* Popups */

    val p2pInitialPopup = mutableStateOf(false)
    val p2pQRpopup = mutableStateOf(false)
    val p2pChoosePeerPopup = mutableStateOf(false)
    val p2pTransferPopup = mutableStateOf(false)

    /* P2P-related */
    val p2pPeers = mutableStateListOf<P2pPeer>()
    val textPeer1 = mutableStateOf<String?>(null)
    val textPeer2 = mutableStateOf<String?>(null)
    val transferProgressPrimary = mutableFloatStateOf(0f) //the overall transfer
    val transferProgressSecondary = mutableFloatStateOf(0f) //the progress of transferring each file
    val transferStatusText = mutableStateOf("") //status of transfer

    fun snacky(string: String) {
        mainScope.launch {
            snack.showSnackbar(
                message = string,
                duration = SnackbarDuration.Short
            )
        }
    }
}