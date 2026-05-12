package jetzy.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
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
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jetzy::TransferWakeLock").also {
            // 30 minutes is well over the longest realistic transfer; the lock
            // is released either on the timeout or in onDestroy, whichever comes first.
            it.setReferenceCounted(false)
            it.acquire(30L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
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
