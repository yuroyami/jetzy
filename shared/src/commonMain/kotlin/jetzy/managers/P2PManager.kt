package jetzy.managers

import androidx.annotation.CallSuper
import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode
import jetzy.p2p.P2pIoApi
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.Screen
import jetzy.ui.transfer.TransferScreenState
import jetzy.utils.PreferablyIO
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Base interface for all P2P transfer methods
 */
abstract class P2PManager {

    lateinit var viewmodel: JetzyViewmodel

    private val coroutineSupervisor = SupervisorJob()
    protected val coroutineScope = CoroutineScope(PreferablyIO + coroutineSupervisor)

    companion object {
        lateinit var platformCallback: P2pPlatformCallback
    }

    val transferProgress = MutableStateFlow(0f)
    val transferStatus = MutableStateFlow("")
    val isConnected  = MutableStateFlow(false)

    abstract val discoveryMode: P2pDiscoveryMode

    open val requiredPermissions: List<String> = listOf()

    /**
     * Initialize the manager and prepare for connections
     */
    @CallSuper
    open fun initialize(viewmodel: JetzyViewmodel) {
        this.viewmodel = viewmodel
        platformCallback.ensurePermissions(requiredPermissions)
    }

    @P2pIoApi
    fun beginTransfer() {
        viewmodel.navigateTo(
            Screen.TransferScreen, noWayToReturn = true
        )

        viewmodel.transferState.value = TransferScreenState(
            senderName = "EdgyBoi",
            receiverName = "CoolGuy",
            progress = 0f,
            completedCount = 0,
            totalCount = 1,
            speedLabel = "2.4MB/s",
            remainingLabel = "Remaining is...",
            isSender = viewmodel.currentOperation.value == P2pOperation.SEND
        )

        coroutineScope.launch {
            when (viewmodel.currentOperation.value) {
                P2pOperation.SEND -> sendFiles(viewmodel.elementsToSend)
                P2pOperation.RECEIVE -> receiveFiles()
                else -> throw Exception("What are we trying to do here?")
            }
        }
    }


    /**
     * Clean up resources and disconnect
     */
    abstract suspend fun cleanup()
    
    /**
     * Send files to the connected peer
     */
    abstract suspend fun sendFiles(files: List<JetzyElement>)
    
    /**
     * Receive files from the connected peer
     */
    abstract suspend fun receiveFiles()

}