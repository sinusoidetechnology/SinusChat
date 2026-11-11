package pl.sinusoide.sinuschat.pclink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.util.LocaleManager

object PcProximitySetup {

    private const val CHANNEL_ID = "sinuschat_pclink_setup"
    private const val NOTIF_ID = 9412

    fun interface Listener {
        fun onDesktop(step: String, rssi: Int, decimeters: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile var pcKey: String? = null
        private set
    @Volatile var lastDesktop: String? = null
        private set
    @Volatile private var listener: Listener? = null
    @Volatile var phoneStep: String? = null

    fun attach(l: Listener?) {
        listener = l
    }

    fun active(): Boolean = pcKey != null

    fun send(step: String) {
        val key = pcKey ?: return
        PcLinkServer.sendTo(key, LinkFrame.ProximitySetup(step))
    }

    fun onFrame(ctx: Context, key: String, frame: LinkFrame.ProximitySetup) {
        val app = ctx.applicationContext
        main.post {
            when (frame.step) {
                LinkFrame.SETUP_START -> {
                    if (pcKey != key) phoneStep = null
                    pcKey = key
                    lastDesktop = null
                    if (listener == null) open(app)
                }
                LinkFrame.SETUP_CANCEL -> {
                    if (pcKey == key) {
                        listener?.onDesktop(frame.step, 0, 0)
                        finish(app)
                    }
                    return@post
                }
                else -> if (pcKey != key) return@post
            }
            if (frame.step != LinkFrame.SETUP_SIGNAL) lastDesktop = frame.step
            listener?.onDesktop(frame.step, frame.rssi, frame.decimeters)
        }
    }

    fun ready(ctx: Context) {
        PcProximity.setupKey = pcKey
        PcProximity.refresh(ctx)
        send(LinkFrame.SETUP_READY)
    }

    fun cancel(ctx: Context) {
        send(LinkFrame.SETUP_CANCEL)
        finish(ctx)
    }

    fun finish(ctx: Context) {
        phoneStep = null
        pcKey = null
        lastDesktop = null
        PcProximity.setupKey = null
        PcProximity.refresh(ctx)
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    fun complete(ctx: Context) {
        phoneStep = null
        pcKey = null
        lastDesktop = null
        PcProximity.setupKey = null
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    private fun open(ctx: Context) {
        val intent = Intent(ctx, ProximitySetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (pl.sinusoide.sinuschat.SinusApp.isForeground) {
            runCatching { ctx.startActivity(intent) }
            return
        }
        val l = LocaleManager.wrap(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr != null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, l.getString(R.string.prox_setup_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                }
            )
        }
        val pending = PendingIntent.getActivity(ctx, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(l.getString(R.string.prox_setup_notif_title))
            .setContentText(l.getString(R.string.prox_setup_notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l.getString(R.string.prox_setup_notif_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }
}
