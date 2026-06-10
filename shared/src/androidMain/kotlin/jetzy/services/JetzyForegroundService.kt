package jetzy.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import jetzy.MainActivity

/**
 * Sticky-notification service that keeps a P2P transfer alive while the user
 * backgrounds Jetzy. Started right before navigating to the QR / peer-discovery
 * screen and stopped from [P2PManager.cleanup][jetzy.managers.P2PManager.cleanup].
 *
 * Uses a partial wake lock so the device CPU stays awake during transfer; the
 * screen-on flag set by [MainActivity] only covers the foreground case.
 *
 * The notification is intentionally minimal — there's no progress bar yet because
 * we want to keep the manager UI as the source of truth. If we want to show
 * progress in the notification later, observe the manager's `transferProgress`
 * StateFlow from `onCreate` and update via `notify()`.
 */
class JetzyForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cachedNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        cachedNotification = buildNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = cachedNotification ?: buildNotification().also { cachedNotification = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires a service type when calling startForeground.
            // dataSync best matches a P2P file transfer; connectedDevice would also
            // fit but requires BLUETOOTH_CONNECT which we don't otherwise need.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        // NOT_STICKY: this service is meaningless without the in-process transfer that started
        // it. START_STICKY resurrected it after process death with no manager alive and no code
        // path that would ever stop it — a permanent "Jetzy is transferring" zombie notification
        // holding a fresh wakelock.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jetzy::TransferWakeLock").also {
                // No timeout: the old 30-minute cap silently lapsed under long transfers (multi-GB
                // over Bluetooth easily exceeds it) with no re-acquisition. Release is guaranteed
                // by onDestroy, which always runs — the service is stopped from cleanup(), the
                // viewmodel's onCleared(), or the OS itself, and NOT_STICKY closed the zombie path.
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        // The partial wakelock keeps the CPU alive but not the Wi-Fi radio: with the screen off
        // many devices drop Wi-Fi into power-save and throttle or park the link — exactly the
        // backgrounded-transfer case this service exists to protect.
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "Jetzy::TransferWifiLock").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jetzy is transferring")
            .setContentText("Tap to return to the transfer screen.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active transfers",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the file transfer running while Jetzy is in the background."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "jetzy_transfer"
        const val NOTIFICATION_ID = 4242

        fun start(context: Context) {
            val intent = Intent(context, JetzyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JetzyForegroundService::class.java))
        }
    }
}
