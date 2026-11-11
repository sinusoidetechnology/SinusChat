package pl.sinusoide.sinuschat.pclink

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
import android.util.Log
import androidx.core.app.NotificationCompat
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.LocaleManager

class PcLinkService : Service() {

    companion object {
        private const val TAG = "PcLinkService"
        private const val CHANNEL_ID = "sinuschat_pclink"
        private const val NOTIF_ID = 9410
        private const val ACTION_REFRESH = "pl.sinusoide.sinuschat.pclink.REFRESH"
        private const val ACTION_DISCONNECT = "pl.sinusoide.sinuschat.pclink.DISCONNECT"

        @Volatile var running = false
            private set

        @Volatile var startBlocked = false
            private set

        private fun wanted(ctx: Context): Boolean =
            PcLinkServer.connectedCount() > 0 ||
                (!PcLinkServer.paused && runCatching { PcLinkStore.pcs(ctx).isNotEmpty() }.getOrDefault(false))

        fun update(ctx: Context) {
            val app = ctx.applicationContext
            if (!wanted(app) && !running) return
            val intent = Intent(app, PcLinkService::class.java).setAction(ACTION_REFRESH)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                else app.startService(intent)
                startBlocked = false
            } catch (e: Exception) {
                startBlocked = true
                Log.w(TAG, "start: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIF_ID, build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground: ${e.message}")
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) {
            PcLinkServer.pause()
            shutdown()
            return START_NOT_STICKY
        }
        if (!wanted(this)) shutdown()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val ctx = LocaleManager.wrap(this)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.pclink_title), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun build(): Notification {
        val ctx = LocaleManager.wrap(this)
        val connected = PcLinkServer.connectedDevices(this)
        val title = when {
            connected.isEmpty() -> ctx.getString(R.string.pclink_title)
            connected.all { it.isWatch } -> ctx.getString(R.string.pclink_fg_title_watch)
            else -> ctx.getString(R.string.pclink_fg_title_pc)
        }
        val text = if (connected.isEmpty()) ctx.getString(R.string.pclink_fg_waiting)
        else connected.joinToString(", ") { pc ->
            pc.name.ifBlank { ctx.getString(if (pc.isWatch) R.string.pclink_default_watch else R.string.pclink_default_name) }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PcLinkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, PcLinkService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, ctx.getString(R.string.pclink_fg_disconnect), disconnect)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
