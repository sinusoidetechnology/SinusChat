package pl.sinusoide.sinuschat.tor

import android.content.Intent
import android.os.IBinder
import org.torproject.jni.TorService
import pl.sinusoide.sinuschat.util.SafeMode

class SinusTorService : TorService() {

    private var blocked = false

    override fun onCreate() {
        blocked = ranInProcess || SafeMode.isDisabled(this, SafeMode.Part.TOR) ||
            !pl.sinusoide.sinuschat.util.PrivacySettings.isTorEnabled(this)
        if (blocked) {
            stopSelf()
            return
        }
        ranInProcess = true
        super.onCreate()
    }

    companion object {
        @Volatile private var ranInProcess = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (blocked) {
            stopSelf()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = if (blocked) null else super.onBind(intent)

    override fun onDestroy() {
        if (blocked) return
        super.onDestroy()
    }
}
