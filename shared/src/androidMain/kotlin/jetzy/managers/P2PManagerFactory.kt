package jetzy.managers

import android.content.Context
import jetzy.p2p.P2pMethod

actual object P2PManagerFactory {
    private lateinit var appContext: Context
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
    
    actual fun createManager(method: P2pMethod): P2PManager? {
        return when (method) {
            is P2pMethod.WiFiDirect -> WiFiDirectManager(appContext)
            is P2pMethod.NearbyConnections -> NearbyConnectionsManager(appContext)
            is P2pMethod.LocalNetwork -> LocalNetworkManager()
            is P2pMethod.Bluetooth -> null // TODO
            else -> null
        }
    }
}