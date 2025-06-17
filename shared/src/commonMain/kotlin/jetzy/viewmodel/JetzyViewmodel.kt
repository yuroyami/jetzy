package jetzy.viewmodel

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import io.github.vinceglb.filekit.PlatformFile
import jetzy.p2p.P2pHandler
import jetzy.p2p.P2pPeer
import jetzy.screens.Operation
import jetzy.theme.NightMode
import jetzy.utils.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class JetzyViewmodel: ViewModel() {
    lateinit var p2pHandler: P2pHandler

    val nightMode = MutableStateFlow(NightMode.SYSTEM)
    var nav: NavController? = null

    val currentOperation = MutableStateFlow<Operation?>(null)
    val currentPeerPlatform = MutableStateFlow<Platform?>(null)

    val files = mutableStateListOf<PlatformFile>()
    val folders = mutableStateListOf<PlatformFile>()
    val photos = mutableStateListOf<PlatformFile>()
    val videos = mutableStateListOf<PlatformFile>()
    val texts = mutableStateListOf<String>()

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
        files.clear()
        folders.clear()
        photos.clear()
        videos.clear()
        texts.clear()
        currentOperation.value = null
        currentPeerPlatform.value = null
    }

    var snack = SnackbarHostState()
    fun snacky(string: String, queue: Boolean = true) {
        if (!queue) snack.currentSnackbarData?.dismiss()
        viewModelScope.launch(Dispatchers.Main) {
            snack.showSnackbar(
                message = string,
                duration = SnackbarDuration.Short
            )
        }
    }
}