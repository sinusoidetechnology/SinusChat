package pl.sinusoide.sinuschat.pclink

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.link.LinkProximity
import pl.sinusoide.sinuschat.util.LocaleManager

object PcProximity {

    private const val TAG = "PcProximity"
    private const val CHANNEL_ID = "sinuschat_pclink_nearby"
    private const val NOTIF_ID = 9411
    const val EXTRA_ASK = "pclink_ask_nearby"

    private val main = Handler(Looper.getMainLooper())
    private val active = HashMap<String, AdvertiseCallback>()
    @Volatile private var app: Context? = null
    @Volatile var setupKey: String? = null
    private var receiverOn = false
    private var lastState: String? = null
    private var asked = false
    private val rotate = Runnable { restartAll() }

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        if (app == null) app = c
        main.post {
            if (!receiverOn) {
                receiverOn = true
                ContextCompat.registerReceiver(c, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { refresh(context) }
                }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        refresh(c)
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun wanted(ctx: Context): List<PcLinkStore.Pc> =
        if (PcLinkServer.paused) emptyList()
        else runCatching { PcLinkStore.pcs(ctx).filter { it.proximity || it.key == setupKey } }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun state(ctx: Context): String {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return LinkFrame.PROXIMITY_UNSUPPORTED
        val a = adapter(ctx) ?: return LinkFrame.PROXIMITY_UNSUPPORTED
        if (!hasPermission(ctx)) return LinkFrame.PROXIMITY_PERMISSION
        if (!a.isEnabled) return LinkFrame.PROXIMITY_BLUETOOTH_OFF
        if (a.bluetoothLeAdvertiser == null) return LinkFrame.PROXIMITY_UNSUPPORTED
        return LinkFrame.PROXIMITY_ON
    }

    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        if (app == null) app = c
        main.post { apply(c) }
    }

    private fun apply(ctx: Context) {
        val want = wanted(ctx)
        val st = state(ctx)
        if (want.isEmpty() || st != LinkFrame.PROXIMITY_ON) {
            stopAll(ctx)
        } else {
            val keys = want.map { it.key }.toSet()
            active.keys.filter { it !in keys }.forEach { stop(ctx, it) }
            want.filter { it.key !in active }.forEach { start(ctx, it) }
            main.removeCallbacks(rotate)
            main.postDelayed(rotate, LinkProximity.msToNextSlot(System.currentTimeMillis()) + 1_500L)
        }
        if (want.isNotEmpty() && st == LinkFrame.PROXIMITY_PERMISSION && setupKey == null) askPermission(ctx) else cancelAsk(ctx)
        val reported = if (want.isEmpty()) LinkFrame.PROXIMITY_OFF else st
        if (reported != lastState) {
            lastState = reported
            publish(ctx)
        }
    }

    fun publish(ctx: Context) {
        val st = state(ctx)
        for (pc in runCatching { PcLinkStore.pcs(ctx) }.getOrDefault(emptyList())) {
            PcLinkServer.sendTo(pc.key, LinkFrame.ProximityState(if (pc.proximity) st else LinkFrame.PROXIMITY_OFF))
        }
    }

    fun stateFor(ctx: Context, pc: PcLinkStore.Pc?): String =
        if (pc?.proximity == true) state(ctx) else LinkFrame.PROXIMITY_OFF

    private fun restartAll() {
        val ctx = app ?: return
        stopAll(ctx)
        apply(ctx)
    }

    @SuppressLint("MissingPermission")
    private fun start(ctx: Context, pc: PcLinkStore.Pc) {
        val advertiser = adapter(ctx)?.bluetoothLeAdvertiser ?: return
        val key = runCatching { LinkProximity.key(PcLinkStore.identity(ctx), pc.keyBytes) }.getOrNull() ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(LinkProximity.COMPANY_ID, LinkProximity.payload(key, System.currentTimeMillis()))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "start: $errorCode")
                main.post { active.remove(pc.key) }
            }
        }
        try {
            advertiser.startAdvertising(settings, data, callback)
            active[pc.key] = callback
        } catch (e: Exception) {
            Log.w(TAG, "start: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stop(ctx: Context, key: String) {
        val callback = active.remove(key) ?: return
        try {
            adapter(ctx)?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (_: Exception) {}
    }

    private fun stopAll(ctx: Context) {
        main.removeCallbacks(rotate)
        active.keys.toList().forEach { stop(ctx, it) }
    }

    @SuppressLint("MissingPermission")
    private fun askPermission(ctx: Context) {
        if (asked) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val l = LocaleManager.wrap(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr != null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, l.getString(R.string.pclink_nearby_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, PcLinkActivity::class.java).putExtra(EXTRA_ASK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(l.getString(R.string.pclink_nearby_title))
            .setContentText(l.getString(R.string.pclink_nearby_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l.getString(R.string.pclink_nearby_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
        asked = true
    }

    private fun cancelAsk(ctx: Context) {
        if (!asked) return
        asked = false
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }
}
