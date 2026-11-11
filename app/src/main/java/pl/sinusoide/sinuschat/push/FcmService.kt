package pl.sinusoide.sinuschat.push

import android.net.ConnectivityManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.sinusoide.sinuschat.messenger.MessageListenerService
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.util.LinkLog
import pl.sinusoide.sinuschat.util.MessageNotifier
import pl.sinusoide.sinuschat.util.NotificationPrefs

class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushManager.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (NotificationPrefs.pushMode(this) != NotificationPrefs.PushMode.FCM) return
        Log.d("FcmService", "FCM wake")
        if (message.priority != message.originalPriority) LinkLog.addThrottled(this, "fcm", "Google obniżył priorytet")
        val started = MessageListenerService.startChecked(this)
        WebSocketManager.forceReconnect()
        MessageListenerService.pollNow(this)
        if (backgroundDataBlocked()) {
            LinkLog.addThrottled(this, "fcm", "dane w tle wyłączone, wiadomość czeka na serwerze")
            MessageNotifier.showBackgroundDataOff(this)
            return
        }
        if (started) return
        LinkLog.addThrottled(this, "fcm", "usługa nie wystartowała z tła, sprawdzam kolejkę")
        val waiting = ApiClient.getObject("/api/messages/waiting", this)?.optInt("count", 0) ?: 0
        if (waiting > 0) MessageNotifier.showPendingSummary(this, waiting)
    }

    private fun backgroundDataBlocked(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm != null && cm.isActiveNetworkMetered &&
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Exception) { false }
}
