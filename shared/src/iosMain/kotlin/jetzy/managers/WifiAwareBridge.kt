package jetzy.managers

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Kotlin-facing protocol for the iOS Wi-Fi Aware bridge that lives in `iosApp/`
 * as Swift. We declare it `@ObjCName` so the Swift side can conform to it via
 * Apple's Swift↔Kotlin interop without needing Kotlin/Native to read the
 * Swift-only `WiFiAware` framework directly.
 *
 * The split is deliberate: anything in commonMain stays clean Kotlin; iOS-only
 * discovery (`WAPublisher`/`WASubscriber`) lives in Swift; the eventual byte
 * channels are handed back to [WifiAwareP2PM] as `NSInputStream`/`NSOutputStream`
 * so the rest of the protocol code (which already deals in streams via MPC)
 * doesn't care that the transport changed.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("JZWifiAwareBridge")
interface WifiAwareBridge {

    /**
     * Whether this device + iOS build can use Wi-Fi Aware right now. The Swift
     * side checks `if #available(iOS 26.0, *)` AND that the framework's
     * `WAPublishableService.allServices()` doesn't throw.
     */
    fun isAvailable(): Boolean

    /**
     * Begin publishing the "jetzy" service AND subscribing for peers. Symmetric
     * by design — both sides do both, like MPC. The bridge invokes [listener]
     * callbacks on the main queue.
     */
    fun startDiscovery(deviceName: String, listener: WifiAwareBridgeListener)

    /** Stop both publish and subscribe. Idempotent. */
    fun stopDiscovery()

    /**
     * Initiate a paired data session with the peer the bridge identified by
     * [peerId] (the same opaque string previously passed to
     * [WifiAwareBridgeListener.onPeerFound]). The bridge surfaces system pairing
     * UI if needed; on success it calls [WifiAwareBridgeListener.onConnected]
     * with NSInput/NSOutput streams ready to read/write.
     */
    fun connectToPeer(peerId: String)

    /** Tear down any active session and free resources. */
    fun cleanup()
}

/**
 * Counterpart callback protocol. The Swift bridge calls these as events happen.
 * All callbacks are dispatched on the main queue by convention; the Kotlin side
 * hops to background via the manager's `p2pScope`.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("JZWifiAwareBridgeListener")
interface WifiAwareBridgeListener {
    fun onPeerFound(peerId: String, peerName: String)
    fun onPeerLost(peerId: String)
    /** [input] / [output] are open and ready; the manager bridges them to Ktor channels. */
    fun onConnected(peerId: String, input: platform.Foundation.NSInputStream, output: platform.Foundation.NSOutputStream)
    fun onError(message: String)
}
