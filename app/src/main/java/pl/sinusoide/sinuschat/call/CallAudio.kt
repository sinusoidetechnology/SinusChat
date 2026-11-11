package pl.sinusoide.sinuschat.call

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import pl.sinusoide.sinuschat.R

class CallAudio(context: Context, private val onChange: () -> Unit) {

    enum class Route { PHONE, SPEAKER, BLUETOOTH }

    companion object {
        fun icon(route: Route): Int = when (route) {
            Route.PHONE -> R.drawable.ic_normal_call
            Route.SPEAKER -> R.drawable.ic_speaker_call
            Route.BLUETOOTH -> R.drawable.ic_bluetooth_call
        }

        private val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    private val app = context.applicationContext
    private val am: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var scoOn = false
    private var fallback = Route.PHONE

    var route = Route.PHONE
        private set
    private var bluetooth: String? = null

    val hasBluetooth: Boolean get() = bluetooth != null
    val speakerOn: Boolean get() = route == Route.SPEAKER

    private val scan = Runnable { rescan() }
    private val lateScan = Runnable { rescan() }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = scheduleScan()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = scheduleScan()
    }

    fun start(speaker: Boolean) {
        if (started) return
        started = true
        fallback = if (speaker) Route.SPEAKER else Route.PHONE
        bluetooth = findBluetooth()?.let { nameOf(it) }
        route = if (bluetooth != null) Route.BLUETOOTH else fallback
        try { am?.registerAudioDeviceCallback(devices, main) } catch (_: Exception) {}
        apply()
        onChange()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(scan)
        main.removeCallbacks(lateScan)
        val am = am ?: return
        try { am.unregisterAudioDeviceCallback(devices) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else {
                stopSco(am)
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    fun select(target: Route) {
        if (target == Route.BLUETOOTH && !hasBluetooth) return
        if (target != Route.BLUETOOTH) fallback = target
        route = target
        if (started) apply()
        onChange()
    }

    fun toggleSpeaker() = setSpeaker(route != Route.SPEAKER)

    fun setSpeaker(on: Boolean) {
        when {
            on -> select(Route.SPEAKER)
            route == Route.SPEAKER -> select(if (hasBluetooth) Route.BLUETOOTH else Route.PHONE)
        }
    }

    fun preferSpeaker() {
        fallback = Route.SPEAKER
        if (route == Route.PHONE) select(Route.SPEAKER)
    }

    fun label(ctx: Context, r: Route = route): String = when (r) {
        Route.PHONE -> ctx.getString(R.string.call_route_phone)
        Route.SPEAKER -> ctx.getString(R.string.call_route_speaker)
        Route.BLUETOOTH -> bluetooth?.takeIf { it.isNotEmpty() } ?: ctx.getString(R.string.call_route_bluetooth)
    }

    private fun scheduleScan() {
        main.removeCallbacks(scan)
        main.removeCallbacks(lateScan)
        main.postDelayed(scan, 300)
        main.postDelayed(lateScan, 1500)
    }

    private fun rescan() {
        if (!started) return
        val found = findBluetooth()?.let { nameOf(it) }
        val had = bluetooth != null
        bluetooth = found
        when {
            found != null && !had -> { route = Route.BLUETOOTH; apply() }
            found == null && route == Route.BLUETOOTH -> { route = fallback; apply() }
        }
        onChange()
    }

    private fun isBluetooth(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun findBluetooth(): AudioDeviceInfo? {
        val am = am ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                am.availableCommunicationDevices.firstOrNull { isBluetooth(it.type) }
            else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isBluetooth(it.type) }
        } catch (_: Exception) { null }
    }

    private fun nameOf(device: AudioDeviceInfo): String =
        device.productName?.toString()?.trim().orEmpty()

    private fun apply() {
        val am = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val list = am.availableCommunicationDevices
                val target = when (route) {
                    Route.SPEAKER -> list.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    Route.BLUETOOTH -> list.firstOrNull { isBluetooth(it.type) }
                    Route.PHONE -> list.firstOrNull { it.type in WIRED }
                        ?: list.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (target != null) am.setCommunicationDevice(target) else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (route == Route.BLUETOOTH) {
                    am.isSpeakerphoneOn = false
                    if (!scoOn) { am.startBluetoothSco(); scoOn = true }
                    am.isBluetoothScoOn = true
                } else {
                    stopSco(am)
                    am.isSpeakerphoneOn = route == Route.SPEAKER
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun stopSco(am: AudioManager) {
        if (!scoOn) return
        scoOn = false
        try {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        } catch (_: Exception) {}
    }
}
