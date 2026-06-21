package jetzy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import jetzy.managers.LanMdnsP2PM
import jetzy.managers.LanWifiP2PM
import jetzy.managers.MpcP2PM
import jetzy.managers.P2PManager
import jetzy.managers.WifiAwareBridge
import jetzy.managers.WifiAwareP2PM
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.viewmodel.JetzyViewmodel
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

lateinit var viewmodel: JetzyViewmodel

/**
 * iOS has no foreground service. The honest, App-Store-safe way to survive a brief
 * background/lock mid-transfer is `beginBackgroundTask`: when the app is backgrounded during an
 * active transfer we request an execution assertion (~30s of grace, sometimes more), and release
 * it on return to foreground, on transfer end, or when the OS expiration handler fires. This does
 * NOT make multi-minute background transfers work — iOS will still suspend a generic-socket app —
 * but it converts a short glance-away / incoming-notification (previously a hard kill once the
 * peer's 8s stall watchdog fired) into a survivable interruption. Scoped to an active transfer so
 * idling on a menu never holds an assertion. Needs on-device validation.
 */
private object IosBackgroundGuard {
    private var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var didEnterBgObserver: NSObjectProtocol? = null
    private var willEnterFgObserver: NSObjectProtocol? = null

    fun start() {
        if (didEnterBgObserver != null) return // already armed
        val center = NSNotificationCenter.defaultCenter
        didEnterBgObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> beginTask() }
        willEnterFgObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> endTask() }
    }

    fun stop() {
        endTask()
        val center = NSNotificationCenter.defaultCenter
        didEnterBgObserver?.let { center.removeObserver(it) }
        willEnterFgObserver?.let { center.removeObserver(it) }
        didEnterBgObserver = null
        willEnterFgObserver = null
    }

    private fun beginTask() {
        if (bgTaskId != UIBackgroundTaskInvalid) return
        bgTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithName("jetzy-transfer") {
            endTask() // expiration handler — OS is reclaiming us; release the assertion now.
        }
    }

    private fun endTask() {
        if (bgTaskId != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
            bgTaskId = UIBackgroundTaskInvalid
        }
    }
}

/**
 * Run [block] on the main queue. async (not sync) so a call already on a background dispatcher
 * doesn't block waiting for the UI thread, and a call already on main still defers cleanly rather
 * than risking a re-entrant dispatch_sync deadlock.
 */
private fun onMainQueue(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

/**
 * Set from Swift (in `iOSApp.swift`) before `MainViewController()` is invoked. Holds
 * the [WifiAwareBridge] implementation — `WifiAwareBridgeImpl` — that wraps Apple's
 * Swift-only `WiFiAware` framework. Left null on iOS < 26 or when the chipset
 * doesn't support NAN, in which case the platform callback falls through to
 * the existing transports.
 */
@Suppress("unused")
var wifiAwareBridge: WifiAwareBridge? = null

/** Registers the foreground-drain observer for the share extension exactly once. */
private var sharedInboxObserverArmed = false

@Suppress("unused", "FunctionName")
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        parallelRendering = true
    }
) {
    AdamScreen(
        onViewmodel = {
            viewmodel = it

            // iOS share-extension intake: stage any files shared into Jetzy while it was closed,
            // and again on every return to foreground (a share can arrive while we're backgrounded).
            jetzy.utils.SharedInbox.drainInto(it)
            if (!sharedInboxObserverArmed) {
                sharedInboxObserverArmed = true
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationWillEnterForegroundNotification,
                    `object` = null,
                    queue = null,
                ) { _ -> jetzy.utils.SharedInbox.drainInto(viewmodel) }
            }

            viewmodel.platformCallback = object: P2pPlatformCallback {
                // mDNS is the platform-agnostic bootstrap — it finds any Jetzy peer on the shared
                // LAN regardless of OS, so we no longer ask the user to guess the peer's platform.
                override fun getDefaultP2pManager(): P2PManager? = LanMdnsP2PM()

                /**
                 * Per-host fallback ladder (peer-platform-agnostic). Best→worst: same-LAN mDNS,
                 * then MultipeerConnectivity for an iOS↔iOS pair with no infrastructure, then
                 * joining an Android-hosted hotspot via QR, then Wi-Fi Aware on iOS 26+ NAN chips.
                 */
                override fun getDefaultFallbackManagers(): List<() -> P2PManager?> = listOf(
                    { LanMdnsP2PM() },                                                     // same Wi-Fi, any OS
                    { MpcP2PM() },                                                          // iOS↔iOS, no infra
                    { LanWifiP2PM() },                                                      // join Android AP via QR
                    { wifiAwareBridge?.let { WifiAwareP2PM.create(it) } ?: LanWifiP2PM() }, // iOS 26 NAN
                )

                override fun getManagerForTechnology(technology: jetzy.p2p.P2pTechnology, role: jetzy.p2p.Role): P2PManager? =
                    when (technology) {
                        jetzy.p2p.P2pTechnology.MultipeerConnectivity -> MpcP2PM()
                        jetzy.p2p.P2pTechnology.LocalNetworkMdns -> LanMdnsP2PM()
                        jetzy.p2p.P2pTechnology.HotspotLAN -> LanWifiP2PM() // iOS joins the AP
                        jetzy.p2p.P2pTechnology.WiFiAware -> wifiAwareBridge?.let { WifiAwareP2PM.create(it) }
                        else -> null
                    }

                // iOS has no foreground service. These hooks scope two things to an active
                // transfer: the idle timer (screen stays awake from proceed/discovery until
                // cleanup — the old global isIdleTimerDisabled=true kept the phone awake even
                // idling on the menu) and a beginBackgroundTask grace assertion so a brief
                // background/lock no longer instantly kills the link (see IosBackgroundGuard).
                // UIApplication and IosBackgroundGuard's begin/endBackgroundTask are UIKit main-
                // thread-only APIs. cleanup() (which calls stopBackgroundService) runs on a
                // background dispatcher via tearDownManager, so calling these inline crashed UIKit's
                // main-thread assertion on every teardown path (Done / cancel / back). Marshal to the
                // main queue so the call site's thread no longer matters.
                override fun startBackgroundService() = onMainQueue {
                    UIApplication.sharedApplication.idleTimerDisabled = true
                    IosBackgroundGuard.start()
                }

                override fun stopBackgroundService() = onMainQueue {
                    UIApplication.sharedApplication.idleTimerDisabled = false
                    IosBackgroundGuard.stop()
                }
            }
        }
    )
}
