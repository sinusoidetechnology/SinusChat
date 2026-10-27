package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import pl.sinusoide.sinuschat.MainActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.DisappearingCoordinator
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.UserStatusManager

class MessageListenerService : Service() {

    companion object {
        const val TAG = "MsgListener"
        private const val RETRY_FIELD = "_retry"
        private const val MAX_RETRIES = 4
        const val FG_CHANNEL_ID  = "sinuschat_fg_listener"
        const val FG_NOTIF_ID    = 9001
        const val ACTION_POLL_NOW = "pl.sinusoide.sinuschat.POLL_NOW"
        const val ACTION_REFRESH_NOTIF = "pl.sinusoide.sinuschat.REFRESH_NOTIF"
        const val ACTION_MODE_CHANGED = "pl.sinusoide.sinuschat.PUSH_MODE_CHANGED"
        private const val EXTRA_NO_FG = "no_fg"
        const val POLL_INTERVAL_MS = 45_000L
        const val FAST_POLL_MS     = 7_000L
        const val TTL_SWEEP_MS     = 5 * 60 * 1000L

        private const val FCM_LINGER_MS    = 6_000L
        private const val FCM_BG_LINGER_MS = 20_000L
        private const val RING_HOLD_MS     = 60_000L
        private const val IDLE_CHECK_MS    = 2_000L
        private const val WAKE_BURST_MS    = 60_000L

        @Volatile var currentlyOpenConversationId: String? = null

        @Volatile var isRunning = false

        fun start(ctx: Context) { startChecked(ctx) }

        fun startChecked(ctx: Context): Boolean = try {
            launch(ctx, Intent(ctx, MessageListenerService::class.java))
            true
        } catch (e: Exception) { Log.e(TAG, "start failed", e); false }

        fun pollNow(ctx: Context) {
            try {
                launch(ctx, Intent(ctx, MessageListenerService::class.java).setAction(ACTION_POLL_NOW))
            } catch (e: Exception) { Log.w(TAG, "pollNow: ${e.message}") }
        }

        fun modeChanged(ctx: Context) {
            try {
                launch(ctx, Intent(ctx, MessageListenerService::class.java).setAction(ACTION_MODE_CHANGED))
            } catch (e: Exception) { Log.w(TAG, "modeChanged: ${e.message}") }
        }

        fun onDemand(ctx: Context): Boolean =
            pl.sinusoide.sinuschat.util.NotificationPrefs.pushMode(ctx) ==
                pl.sinusoide.sinuschat.util.NotificationPrefs.PushMode.FCM

        private fun launch(ctx: Context, intent: Intent) {
            if (onDemand(ctx)) {
                try {
                    ctx.startService(Intent(intent).putExtra(EXTRA_NO_FG, true))
                    return
                } catch (_: IllegalStateException) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun housekeeping(ctx: Context) {
            if (pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(ctx) &&
                pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() == null) return
            val n = DisappearingMessages.sweepExpired(ctx)
            if (n > 0) Log.d(TAG, "TTL: usunięto $n wiadomości")
            pl.sinusoide.sinuschat.util.AutoDelete.check(ctx)
            pl.sinusoide.sinuschat.util.BackupRunner.checkDue(ctx)
            try { MessageRepository.markUndeliveredExpired(ctx) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, MessageListenerService::class.java)) }
            catch (e: Exception) { Log.e(TAG, "stop failed", e) }
        }

        fun syncPeerKey(ctx: Context, hash: String, key: String) {
            if (hash.isEmpty() || key.isEmpty()) return
            if (pl.sinusoide.sinuschat.util.PeerKeyCache.getKey(ctx, hash) == key) return
            if (E2EManager.computeSharedSecret(ctx, key) == null) return
            if (pl.sinusoide.sinuschat.util.PeerKeyCache.saveKey(ctx, hash, key)) {
                pl.sinusoide.sinuschat.crypto.SignalProtocol.onPeerIdentityChanged(ctx, hash)
            }
        }

        fun syncPeerKeys(ctx: Context, conversations: org.json.JSONArray) {
            for (i in 0 until conversations.length()) {
                val c = conversations.optJSONObject(i) ?: continue
                val hash = c.optStringOrNull("other_hash") ?: continue
                val key = c.optStringOrNull("e2e_public_key") ?: continue
                syncPeerKey(ctx, hash, key)
            }
        }

        fun triggerAckCheck(ctx: Context) {
            if (pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(ctx) &&
                pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() == null) return
            AppExecutor.io {
                try {
                    val prefs = ctx.getSharedPreferences("sinuschat_api", Context.MODE_PRIVATE)
                    val token = prefs.getString("jwt_token", null) ?: return@io
                    val arr = pl.sinusoide.sinuschat.network.ApiClient.getArray("/api/conversations", ctx)
                        ?: return@io
                    syncPeerKeys(ctx, arr)
                    val myHash = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx)
                    val db = pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(ctx).messageDao()
                    for (i in 0 until arr.length()) {
                        val conv = arr.getJSONObject(i)
                        val convId    = conv.optString("id")
                        if (pl.sinusoide.sinuschat.util.BlockList.isBlockedByThem(ctx, conv.optString("other_hash"))) continue
                        val otherRead = conv.optLong("other_read_up_to", 0)
                        val otherDel  = conv.optLong("other_delivered_up_to", 0)
                        if (otherRead > 0) db.markReadUpTo(convId, myHash, otherRead)
                        if (otherDel  > 0) db.markDeliveredUpTo(convId, myHash, otherDel)
                    }
                } catch (e: Exception) { Log.e(TAG, "triggerAckCheck: ${e.message}") }
            }
        }
    }

    private var myPhoneHash = ""
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lastNotifTime = mutableMapOf<String, Long>()
    private val notifiedIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 300
    }

    private val ttlRunnable = object : Runnable {
        override fun run() {
            if (!dbWritable()) {
                mainHandler.postDelayed(this, TTL_SWEEP_MS)
                return
            }
            AppExecutor.io { housekeeping(this@MessageListenerService) }
            mainHandler.postDelayed(this, TTL_SWEEP_MS)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollPendingMessages()
            val next = if (currentlyOpenConversationId != null) FAST_POLL_MS else POLL_INTERVAL_MS
            mainHandler.postDelayed(this, next)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF   -> UserStatusManager.onBackground()
                android.content.Intent.ACTION_USER_PRESENT -> UserStatusManager.onScreenUnlocked()
                android.content.Intent.ACTION_SCREEN_ON    -> { }
            }
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var inForeground = false
    private var lastStartId = 0

    @Volatile private var lastWakeMs = 0L
    @Volatile private var ringHoldUntil = 0L
    private var seenVisible = false
    private var hiddenAt = 0L
    private val pollsInFlight = java.util.concurrent.atomic.AtomicInteger()

    private val inbox = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!onDemand(this@MessageListenerService)) return
            val now = android.os.SystemClock.elapsedRealtime()
            val visible = pl.sinusoide.sinuschat.SinusApp.isForeground
            if (visible) {
                seenVisible = true
                hiddenAt = 0L
                leaveForeground()
                releaseWakeLock()
            } else if (seenVisible && hiddenAt == 0L) hiddenAt = now
            val needed = visible ||
                pl.sinusoide.sinuschat.call.CallActivity.activeConversationId != null ||
                pl.sinusoide.sinuschat.call.GroupCallSession.current != null ||
                pl.sinusoide.sinuschat.call.WalkieTalkie.session != null ||
                now < ringHoldUntil ||
                pollsInFlight.get() > 0 ||
                now - lastWakeMs < FCM_LINGER_MS ||
                (hiddenAt != 0L && now - hiddenAt < FCM_BG_LINGER_MS)
            if (needed) {
                mainHandler.postDelayed(this, IDLE_CHECK_MS)
                return
            }
            Log.d(TAG, "Tryb Google: nic więcej do odebrania — usługa kończy")
            leaveForeground()
            stopSelfResult(lastStartId)
        }
    }

    @Volatile private var online = true
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val netCheck = Runnable { refreshOnline() }

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        online = isOnlineNow(cm)
        if (online) catchUpMedia()
        pl.sinusoide.sinuschat.call.WalkieTalkie.Background.update(this, pl.sinusoide.sinuschat.util.NetworkSettings.isOnWifi(this))
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = schedule()
            override fun onLost(network: android.net.Network) = schedule()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = schedule()
            private fun schedule() {
                mainHandler.removeCallbacks(netCheck)
                mainHandler.postDelayed(netCheck, 1_500L)
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (e: Exception) {
            Log.w(TAG, "networkCallback: ${e.message}")
        }
    }

    private fun refreshOnline() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val now = isOnlineNow(cm)
        if (now) catchUpMedia()
        pl.sinusoide.sinuschat.call.WalkieTalkie.Background.update(this, now && pl.sinusoide.sinuschat.util.NetworkSettings.isOnWifi(this))
        if (now == online) return
        online = now
        updateForegroundNotification()
    }

    private fun isOnlineNow(cm: android.net.ConnectivityManager): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val recentOffers = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannels()
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        watchNetwork()
    }

    private fun goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FG_NOTIF_ID, buildFgNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(FG_NOTIF_ID, buildFgNotification())
            }
            inForeground = true
        } catch (e: Exception) { Log.w(TAG, "startForeground: ${e.message}") }
    }

    private fun leaveForeground() {
        if (!inForeground) return
        try {
            androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { Log.w(TAG, "stopForeground: ${e.message}") }
        inForeground = false
    }

    private fun applyMode() {
        if (onDemand(this)) {
            lastWakeMs = android.os.SystemClock.elapsedRealtime()
            cancelWakeAlarms()
            if (pl.sinusoide.sinuschat.SinusApp.isForeground) {
                leaveForeground()
                releaseWakeLock()
            } else {
                acquireWakeLock(WAKE_BURST_MS)
            }
            mainHandler.removeCallbacks(idleCheck)
            mainHandler.postDelayed(idleCheck, IDLE_CHECK_MS)
        } else {
            if (!inForeground) goForeground()
            acquireWakeLock()
        }
    }

    private fun cancelWakeAlarms() {
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        for ((code, action) in listOf(42 to null, 43 to ACTION_POLL_NOW)) {
            val i = Intent(applicationContext, MessageListenerService::class.java)
            if (action != null) i.action = action
            val pi: PendingIntent? = PendingIntent.getForegroundService(this, code, i, flags)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
    }

    private fun acquireWakeLock(timeoutMs: Long = 0L) {
        try {
            val wl = wakeLock ?: getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sinuschat:listener")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            if (timeoutMs > 0) wl.acquire(timeoutMs) else wl.acquire()
        } catch (e: Exception) { Log.w(TAG, "wakeLock: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        myPhoneHash = SetupActivity.getUserPhoneHash(this)
        lastStartId = startId

        if (intent?.getBooleanExtra(EXTRA_NO_FG, false) != true) goForeground()
        applyMode()
        if (intent?.action == ACTION_MODE_CHANGED && onDemand(this)) {
            pl.sinusoide.sinuschat.network.NtfyManager.stopListening()
        }

        if (intent?.action == ACTION_REFRESH_NOTIF) {
            updateForegroundNotification()
            return sticky()
        }

        if (intent?.action == ACTION_POLL_NOW && myPhoneHash.isNotEmpty()) {
            if (!WebSocketManager.isConnected()) WebSocketManager.connect(this)
            pollPendingMessages()
            MessageRepository.retryPendingOutgoing(this, myPhoneHash)
            mainHandler.removeCallbacks(pollRunnable)
            val next = if (currentlyOpenConversationId != null) FAST_POLL_MS else POLL_INTERVAL_MS
            mainHandler.postDelayed(pollRunnable, next)
            return sticky()
        }

        if (myPhoneHash.isEmpty()) {
            stopSelf(); return START_NOT_STICKY
        }

        if (!ApiClient.isAuthenticated(this)) {
            Log.w(TAG, "Brak JWT token — próba auto-rejestracji na OVH")
            ApiClient.autoRegisterIfNeeded(this) { success ->
                if (success) {
                    Log.d(TAG, "Auto-rejestracja OK — startuję serwis")
                    startListeningAndPolling()
                } else {
                    Log.e(TAG, "Auto-rejestracja NIE POWIODŁA SIĘ — serwis w trybie degraded")
                }
            }
            return sticky()
        }

        startListeningAndPolling()
        scheduleWatchdog()
        return sticky()
    }

    private fun sticky() = if (onDemand(this)) START_NOT_STICKY else START_STICKY

    private fun scheduleWatchdog() {
        try {
            if (myPhoneHash.isEmpty() || onDemand(this)) return
            val i = Intent(applicationContext, MessageListenerService::class.java)
                .setAction(ACTION_POLL_NOW)
            scheduleWake(servicePendingIntent(43, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT), 15 * 60_000L)
        } catch (e: Exception) { Log.w(TAG, "watchdog: ${e.message}") }
    }

    private fun startListeningAndPolling() {
        WebSocketManager.onMessage = { json -> handleIncoming(json) }
        WebSocketManager.onReconnected = {
            pollPendingMessages()
            MessageRepository.retryPendingOutgoing(this, myPhoneHash)
        }
        WebSocketManager.connect(this)
        MessageRepository.retryPendingOutgoing(this, myPhoneHash)
        pollPendingMessages()
        AppExecutor.io { pl.sinusoide.sinuschat.crypto.SignalProtocol.ensureKeysUploaded(this) }
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        mainHandler.removeCallbacks(ttlRunnable)
        mainHandler.post(ttlRunnable)

        if (onDemand(this)) return
        pl.sinusoide.sinuschat.network.NtfyManager.startListening(this) { kind ->
            if (kind.trim() == "call") WebSocketManager.forceReconnect()
            else if (!WebSocketManager.isConnected()) WebSocketManager.reconnectNow()
            pollPendingMessages()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pl.sinusoide.sinuschat.call.WalkieTalkie.Background.stop()
        mainHandler.removeCallbacks(flushResend)
        flushResend.run()
        inbox.shutdown()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        netCallback?.let { cb ->
            try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            netCallback = null
        }
        try { pl.sinusoide.sinuschat.network.NtfyManager.stopListening() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        WebSocketManager.disconnect()

        scheduleRestart(2_000L)
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        if (UserStatusManager.takeActive() && !pl.sinusoide.sinuschat.util.PrivacySettings.isActivityStatusDisabled(this)) {
            val ctx = this
            Thread {
                try {
                    pl.sinusoide.sinuschat.network.ApiClient.post("/api/heartbeat",
                        org.json.JSONObject()
                            .put("heartbeatMs", 0L)
                            .put("lastSeenMs", System.currentTimeMillis()),
                        ctx)
                } catch (_: Exception) {}
            }.apply { isDaemon = false; start(); join(3000) }
        }

        scheduleRestart(1_000L)
    }

    private fun servicePendingIntent(reqCode: Int, intent: Intent, extraFlags: Int): android.app.PendingIntent {
        val flags = extraFlags or android.app.PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.app.PendingIntent.getForegroundService(this, reqCode, intent, flags)
        else
            android.app.PendingIntent.getService(this, reqCode, intent, flags)
    }

    private fun scheduleWake(pi: android.app.PendingIntent, delayMs: Long) {
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            try { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {}
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        try {
            if (onDemand(this) || !SetupActivity.isSetupDone(this) ||
                SetupActivity.getUserPhoneHash(this).isEmpty()) return
            val restart = Intent(applicationContext, MessageListenerService::class.java)
            scheduleWake(servicePendingIntent(42, restart,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT), delayMs)
        } catch (e: Exception) { Log.w(TAG, "scheduleRestart: ${e.message}") }
    }

    private fun dbWritable(): Boolean =
        !pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(this) ||
        pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() != null

    private fun handleWhileDbLocked(json: JSONObject): Boolean {
        val type = json.optString("type")
        val kind = json.optString("kind")

        if (type.startsWith("call_") || kind.startsWith("call_")) return true

        if (type in setOf("presence", "typing", "typing_stop", "pong")) return false

        pl.sinusoide.sinuschat.util.LockedInbox.add(this, json.toString())

        val isMessage = type == "message" || (type.isEmpty() && json.has("payloadB64"))
        if (isMessage && (kind.ifEmpty { "msg" } == "msg" || kind == "group_attach")) {
            AppExecutor.io { notifyWithoutDb(json) }
        }
        return false
    }

    private fun notifyWithoutDb(json: JSONObject) {
        try {
            val kind       = json.optString("kind", "msg")
            val convId     = json.optString("conversationId", "")
            val msgId      = json.optString("id", json.optString("messageId", ""))
            val createdAt  = json.optLong("createdAt", System.currentTimeMillis())
            val payloadB64 = json.optString("payloadB64", "")
            if (convId.isEmpty() || payloadB64.isEmpty()) return
            val payload = JSONObject(String(Base64.decode(payloadB64, Base64.NO_WRAP), Charsets.UTF_8))
            val sender = verifiedSender(json, payload, kind, convId) ?: return
            if (sender == myPhoneHash) return
            if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, sender)) return

            val entity: MessageEntity = when {
                kind == "group_attach" -> MessageEntity(
                    id = msgId, conversationId = convId, senderHash = sender, text = "",
                    timestampMs = createdAt, type = "attachment")
                payload.has("skm") || pl.sinusoide.sinuschat.crypto.SignalProtocol.isV2(payload) -> MessageEntity(
                    id = msgId, conversationId = convId, senderHash = sender,
                    text = getString(R.string.new_message), timestampMs = createdAt,
                    type = MessageEntity.TYPE_TEXT)
                GroupRepository.isGroup(convId) && !payload.has("enc") -> return
                GroupRepository.isGroup(convId) && payload.has("enc") -> MessageEntity(
                    id = msgId, conversationId = convId, senderHash = sender,
                    text = pl.sinusoide.sinuschat.crypto.GroupKeyManager.decryptFromMember(
                        this, convId, sender, payload.optString("enc"))
                        ?: getString(R.string.new_message),
                    timestampMs = createdAt, type = MessageEntity.TYPE_TEXT)
                else -> {
                    val secret = E2EManager.getSharedSecretCached(this, sender)
                    decryptPayload(convId, msgId, sender, payload, secret, createdAt)
                        ?.let { e ->
                            if (e.viewOnce) e.copy(text = getString(R.string.view_once_sent), type = MessageEntity.TYPE_TEXT)
                            else e
                        }
                        ?: MessageEntity(id = msgId, conversationId = convId, senderHash = sender,
                            text = getString(R.string.new_message), timestampMs = createdAt,
                            type = MessageEntity.TYPE_TEXT)
                }
            }
            maybeNotify(convId, sender, entity)
            if (msgId.isNotEmpty()) pl.sinusoide.sinuschat.util.LockedInbox.markNotified(this, msgId)
        } catch (e: Exception) {
            Log.w(TAG, "notifyWithoutDb: ${e.message}")
        }
    }

    private fun handleIncoming(frame: JSONObject) {
        lastWakeMs = android.os.SystemClock.elapsedRealtime()
        val json = if (frame.optString("kind") == "sealed" || frame.optString("type") == "sealed") {
            val eph = frame.optBoolean("eph", false)
            val opened = pl.sinusoide.sinuschat.crypto.SealedSender.unseal(this, frame)
            if (opened == null && !eph && E2EManager.myPrivateKeyBytes(this) == null) {
                val tries = frame.optInt(RETRY_FIELD, 0)
                if (tries < MAX_RETRIES) {
                    Log.w(TAG, "Koperta czeka — klucze chwilowo niedostępne")
                    pl.sinusoide.sinuschat.util.LinkLog.add(this, "sealed", "klucze chwilowo niedostępne, próba ${tries + 1}")
                    pl.sinusoide.sinuschat.util.LockedInbox.add(this, JSONObject(frame.toString()).put(RETRY_FIELD, tries + 1).toString())
                    mainHandler.postDelayed({ pollPendingMessages() }, 15_000L * (tries + 1))
                    return
                }
            }
            if (!eph) frame.optString("id").takeIf { it.isNotEmpty() }?.let { queueSealedAck(it) }
            opened ?: run {
                Log.w(TAG, "Koperty nie da się otworzyć — pominięta")
                return
            }
        } else frame
        val sealedKind = json.optString("kind")
        if (json.optBoolean("sealed", false) && (sealedKind == "typing" || sealedKind == "typing_stop")) {
            val conv = json.optString("conversationId")
            val from = json.optString("senderHash")
            if (isPartner(conv, from) && !pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, from)) {
                WebSocketManager.dispatchTyping(conv, from, sealedKind == "typing")
            }
            return
        }
        if (json.optString("type") == "peer_key") {
            val hash = json.optString("userHash")
            if (Regex("[a-f0-9]{64}").matches(hash)) syncPeerKey(this, hash, json.optString("e2ePublicKey"))
            return
        }
        if (!dbWritable()) {
            if (!handleWhileDbLocked(json)) return
        }
        when (json.optString("type")) {
            "group_created" -> {
                val gid = json.optString("groupId")
                val gname = pl.sinusoide.sinuschat.crypto.GroupMeta.open(this, gid, json.optString("name"), getString(R.string.group))
                if (gid.isNotEmpty()) {
                    AppExecutor.main { sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")) }
                    if (pl.sinusoide.sinuschat.util.NotificationPrefs.groupAddedEnabled(this))
                        showNotification(gid, "", getString(R.string.notif_added_to_group_format, gname))
                }
                return
            }
            "group_added" -> {
                val gid = json.optString("groupId")
                val gname = pl.sinusoide.sinuschat.crypto.GroupMeta.open(this, gid, json.optString("name"), getString(R.string.group))
                AppExecutor.main { sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")) }
                if (!json.optBoolean("selfJoin", false) &&
                    pl.sinusoide.sinuschat.util.NotificationPrefs.groupAddedEnabled(this))
                    showNotification(gid, "", getString(R.string.notif_added_to_group_format, gname))
                return
            }
            "group_member_added", "group_member_left", "group_member_removed",
            "group_updated", "group_admin_changed" -> {
                val gid = json.optString("groupId")
                val sys = pl.sinusoide.sinuschat.util.GroupSystemMessages
                when (json.optString("type")) {
                    "group_member_added" -> {
                        sys.memberJoined(this, gid, json.optString("newMember"))
                        pl.sinusoide.sinuschat.util.GroupRoster.onJoined(this, gid, json.optString("newMember"))
                    }
                    "group_member_left" -> {
                        if (!json.optBoolean("silent", false)) sys.memberLeft(this, gid, json.optString("userHash"))
                        pl.sinusoide.sinuschat.util.GroupRoster.onLeft(this, gid, json.optString("userHash"))
                    }
                    "group_member_removed" -> {
                        sys.memberRemoved(this, gid, json.optString("removed"))
                        pl.sinusoide.sinuschat.util.GroupRoster.onLeft(this, gid, json.optString("removed"))
                    }
                    "group_admin_changed" ->
                        sys.adminChanged(this, gid, json.optString("userHash"),
                            json.optBoolean("isAdmin", false))
                    "group_updated" -> {
                        val by = json.optString("by")
                        if (by.isNotEmpty()) insertGroupChangedBy(gid, by) else sys.settingsChanged(this, gid)
                    }
                }
                val evt = json.optString("type")
                if ((evt == "group_member_left" || evt == "group_member_removed") && gid.isNotEmpty())
                    pl.sinusoide.sinuschat.crypto.GroupKeyManager.markRotationNeeded(this, gid)
                if (json.optString("type") == "group_admin_changed" || json.optString("type") == "group_updated") {
                    AppExecutor.main {
                        sendBroadcast(android.content.Intent(ChatActivity.GROUP_SETTINGS_CHANGED)
                            .setPackage(packageName).putExtra("groupId", gid))
                    }
                }
                AppExecutor.main { sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")) }
                return
            }
            "unblocked" -> {
                val by = json.optString("by")
                if (by.isNotEmpty()) {
                    pl.sinusoide.sinuschat.util.BlockList.clearBlockedByThem(this, by)
                    AppExecutor.main {
                        sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS"))
                    }
                }
                return
            }
        }
        val msgType = json.optString("type")
        val msgKind = json.optString("kind")
        if (msgType == "call_offer" || msgKind == "call_offer") {
            val convId   = json.optString("conversationId")
            val fromHash = json.optString("fromHash").takeIf { it.isNotEmpty() }
                ?: json.optString("senderHash").takeIf { it.isNotEmpty() }
                ?: json.optStringOrNull("from") ?: ""
            val isVideo  = json.optBoolean("isVideo", false)
            val offerSDP = json.optString("sdpDescription")
            android.util.Log.d("CallFlow", "call_offer: conv=${convId.take(8)} from=${fromHash.take(8)} sdpLen=${offerSDP.length} keys=${json.keys().asSequence().joinToString()}")
            if (convId.isNotEmpty() && fromHash.isNotEmpty()) {
                if (pl.sinusoide.sinuschat.call.CallActivity.activeConversationId == convId) return
                if (pl.sinusoide.sinuschat.call.GroupCallSession.current?.ended == false) {
                    val busyName = pl.sinusoide.sinuschat.util.ContactCache.getName(this, fromHash) ?: getString(R.string.caller_unknown)
                    WebSocketManager.send(org.json.JSONObject().put("type", "call_reject")
                        .put("conversationId", convId).put("reason", "busy"))
                    AppExecutor.main {
                        pl.sinusoide.sinuschat.call.CallNotifier.showMissedCall(this, convId, fromHash, busyName, isVideo)
                    }
                    return
                }
                if (isNewChatBlocked(convId, fromHash)) {
                    android.util.Log.d("CallFlow", "call_offer od nowej osoby — blokada nowych rozmów")
                    return
                }
                val offerKey = convId + ":" + offerSDP.hashCode()
                synchronized(recentOffers) {
                    val now = System.currentTimeMillis()
                    recentOffers.entries.removeAll { now - it.value > 60_000 }
                    if (recentOffers.containsKey(offerKey)) {
                        android.util.Log.d("CallFlow", "call_offer powtórzony — pomijam")
                        return
                    }
                    recentOffers[offerKey] = now
                }
                ringHoldUntil = android.os.SystemClock.elapsedRealtime() + RING_HOLD_MS
                if (!pl.sinusoide.sinuschat.SinusApp.isForeground) mainHandler.post { if (!inForeground) goForeground() }
                pl.sinusoide.sinuschat.call.CallNotifier.clearCancelled(convId)
                val fromName = pl.sinusoide.sinuschat.util.ContactCache.getName(this, fromHash) ?: getString(R.string.caller_unknown)
                AppExecutor.main {
                    pl.sinusoide.sinuschat.call.CallNotifier.showIncomingCall(
                        this, convId, fromHash, fromName, isVideo, offerSDP
                    )
                }
            }
            return
        }

        if (msgType == "call_end" || msgType == "call_reject" ||
            msgKind == "call_end" || msgKind == "call_reject") {
            val convId = json.optString("conversationId")
            if (json.optString("reason") == "account_deleted" && !GroupRepository.isGroup(convId)) {
                convId.split("_").firstOrNull { it.isNotEmpty() && it != myPhoneHash }
                    ?.let { pl.sinusoide.sinuschat.util.BlockList.markDeleted(this, it) }
            }
            ringHoldUntil = 0L
            AppExecutor.main {
                pl.sinusoide.sinuschat.call.CallNotifier.callerHungUp(this, convId)
                pl.sinusoide.sinuschat.call.CallNotifier.cancel(this)
                pl.sinusoide.sinuschat.call.CallNotifier.markCancelled(convId)
                val i = android.content.Intent(pl.sinusoide.sinuschat.call.IncomingCallActivity.ACTION_CALL_CANCELLED)
                    .setPackage(packageName)
                if (convId.isNotEmpty()) i.putExtra("conversationId", convId)
                sendBroadcast(i)
            }
            return
        }

        if (msgType == "call_answer" || msgKind == "call_answer" ||
            msgType == "call_ice"    || msgKind == "call_ice") {
            return
        }

        val isAck = json.optString("type") == "ack" || json.optString("kind") == "ack"
        if (isAck) {
            val convId      = json.optString("conversationId")
            val queued = json.optString("payloadB64").takeIf { it.isNotEmpty() }?.let {
                runCatching { JSONObject(String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)) }.getOrNull()
            }
            val deliveredUp = queued?.optLong("deliveredUpTo", 0) ?: json.optLong("deliveredUpTo", 0)
            val readUp      = queued?.optLong("readUpTo", 0) ?: json.optLong("readUpTo", 0)
            val goneUp      = queued?.optLong("disappearUpTo", 0) ?: 0L
            val from = json.optString("fromHash").ifEmpty { json.optString("senderHash") }
            val foreign = from.isNotEmpty() && !GroupRepository.isGroup(convId) && !isPartner(convId, from)
            val smallGroup = GroupRepository.isGroup(convId) &&
                pl.sinusoide.sinuschat.util.GroupReceipts.isSmall(this, convId)
            if (convId.isNotEmpty() && !foreign && !smallGroup) {
                Log.d(TAG, "Otrzymano ACK: conv=$convId del=$deliveredUp read=$readUp")
                if (deliveredUp > 0) MessageRepository.applyDeliveryAck(this, convId, myPhoneHash, deliveredUp)
                if (readUp > 0)      MessageRepository.applyReadAck(this, convId, myPhoneHash, readUp)
                if (goneUp > readUp) MessageRepository.applyDisappearAck(this, convId, myPhoneHash, goneUp)
            }
            return
        }

        val kind        = json.optString("kind", "msg")
        val senderHash  = json.optString("senderHash", "")
        val convId      = json.optString("conversationId", "")
        val payloadB64  = json.optString("payloadB64", "")
        val msgId       = json.optString("id", json.optString("messageId", ""))
        val createdAt   = json.optLong("createdAt", System.currentTimeMillis())

        if (convId.isEmpty()) return
        if (!json.optBoolean("sealed", false) && !GroupRepository.isGroup(convId)) MessageRepository.noteUnsealed(convId)

        val payload: JSONObject = if (payloadB64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(payloadB64, Base64.NO_WRAP)
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) { Log.e(TAG, "payloadB64 decode fail", e); return }
        } else json

        val sender = verifiedSender(json, payload, kind, convId) ?: run {
            Log.w(TAG, "Odrzucono ramkę „$kind\" — nadawca się nie zgadza")
            return
        }

        if (kind == "resend_req") {
            AppExecutor.io { handleResendRequest(convId, sender, payload) }
            return
        }

        pollsInFlight.incrementAndGet()
        val task = Runnable {
            try { processPayload(convId, msgId, kind, sender, payload, createdAt) }
            catch (e: Throwable) { Log.e(TAG, "processPayload: ${e.message}") }
            finally { pollsInFlight.decrementAndGet() }
        }
        try { inbox.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { AppExecutor.io { task.run() } }
    }

    private fun verifiedSender(json: JSONObject, payload: JSONObject, kind: String, convId: String): String? {
        val server = json.optString("senderHash").takeIf { it.isNotEmpty() && it != "sealed" } ?: return null
        val claimed = payload.optString("senderHash").takeIf { it.isNotEmpty() && it != "sealed" }
        if (claimed != null && claimed != server) return null
        if (kind == "expiry_notice") return server.takeIf { it == myPhoneHash }
        if (!GroupRepository.isGroup(convId) && !isPartner(convId, server)) return null
        return server
    }

    private fun isPartner(convId: String, hash: String): Boolean {
        if (hash.isEmpty() || hash == myPhoneHash) return false
        if (convId == MessageRepository.conversationId(myPhoneHash, hash)) return true
        val arr = pl.sinusoide.sinuschat.util.ConversationCache.load(this) ?: return false
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.optString("id") == convId) return c.optStringOrNull("other_hash") == hash
        }
        return false
    }

    private fun processPayload(
        convId: String,
        msgId: String,
        kind: String,
        senderHash: String,
        payload: JSONObject,
        createdAt: Long
    ) {
        if (senderHash == myPhoneHash && kind != "expiry_notice") {
            if (msgId.isNotEmpty()) MessageRepository.markStatusBlocking(this, msgId, MessageEntity.STATUS_SENT)
            return
        }

        if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, senderHash)) {
            Log.d(TAG, "Pominięto ${kind} od zablokowanego ${senderHash.take(8)}")
            return
        }

        if (isNewChatBlocked(convId, senderHash)) {
            Log.d(TAG, "Pominięto ${kind} od nowej osoby — blokada nowych rozmów")
            return
        }

        pl.sinusoide.sinuschat.util.BlockList.clearBlockedByThem(this, senderHash)
        pl.sinusoide.sinuschat.util.BlockList.clearDeleted(this, senderHash)

        when (kind) {
            "group_attach" -> {
                if (!GroupRepository.isGroup(convId)) return
                val ts = createdAt
                val metaJson = openGroupContent(convId, msgId, senderHash, payload, "encAttach", ts) ?: return
                try {
                    val meta = JSONObject(metaJson)
                    val entity = MessageEntity(
                        id = msgId, conversationId = convId, senderHash = senderHash,
                        text = meta.optString("caption"), timestampMs = ts,
                        status = MessageEntity.STATUS_DELIVERED,
                        type = MessageEntity.TYPE_ATTACHMENT,
                        fileId = meta.optString("fileId"),
                        fileKey = meta.optString("fileKey"),
                        attachmentName = meta.optString("fileName"),
                        albumId = meta.optString("albumId").takeIf { it.isNotEmpty() },
                        replyToId = meta.optString("replyToId").takeIf { it.isNotEmpty() },
                        replyToText = meta.optString("replyToText").takeIf { it.isNotEmpty() }
                    )
                    pl.sinusoide.sinuschat.util.VideoThumbs.saveFromPayload(this, convId, msgId, meta.optString("fileName"), meta)
                    MessageRepository.insertIncomingMessageBlocking(this, entity)
                    dropCaptionCopy(entity)
                    MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
                    if (currentlyOpenConversationId == convId) {
                        MessageRepository.publishRead(this, convId, myPhoneHash, ts)
                    }
                    maybeNotify(convId, senderHash, entity)
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd parsowania metadanych załącznika grupowego", e)
                }
                return
            }
            "group_key" -> {
                if (senderHash.isNotEmpty()) {
                    val ok = if (payload.has("skdm") || payload.has("skdm1"))
                        pl.sinusoide.sinuschat.crypto.SignalProtocol.processDistribution(this, convId, senderHash, payload)
                    else pl.sinusoide.sinuschat.crypto.GroupKeyManager.receiveMemberKey(
                        this, convId, senderHash, payload)
                    if (ok) {
                        val drained = pl.sinusoide.sinuschat.crypto.GroupKeyManager.drainPendingFor(
                            this, convId, senderHash)
                        for (item in drained) {
                            val pendingMsgId = item.id
                            val text = item.text
                            val replyRaw = item.reply
                            val entity = try {
                                val j = JSONObject(text)
                                if (j.has("fileId")) {
                                    pl.sinusoide.sinuschat.util.VideoThumbs.saveFromPayload(
                                        this, convId, pendingMsgId, j.optString("fileName"), j)
                                    MessageEntity(
                                        id = pendingMsgId, conversationId = convId,
                                        senderHash = senderHash, text = "",
                                        timestampMs = item.timestamp,
                                        status = MessageEntity.STATUS_DELIVERED,
                                        type = MessageEntity.TYPE_ATTACHMENT,
                                        fileId = j.optString("fileId"),
                                        fileKey = j.optString("fileKey"),
                                        attachmentName = j.optString("fileName"),
                                        albumId = j.optString("albumId").takeIf { it.isNotEmpty() }
                                    )
                                } else throw Exception(getString(R.string.not_attachment))
                            } catch (e: Exception) {
                                val (replyId, replyText) = groupReply(replyRaw)
                                val tr = pl.sinusoide.sinuschat.util.Translations.parse(replyRaw)
                                MessageEntity(
                                    id = pendingMsgId, conversationId = convId,
                                    senderHash = senderHash, text = text,
                                    timestampMs = item.timestamp,
                                    status = MessageEntity.STATUS_DELIVERED,
                                    type = MessageEntity.TYPE_TEXT,
                                    replyToId = replyId,
                                    replyToText = replyText,
                                    trOrig = tr?.orig, trFrom = tr?.from, trTo = tr?.to
                                )
                            }
                            MessageRepository.insertIncomingMessageBlocking(this, entity)
                            maybeNotify(convId, senderHash, entity)
                        }
                        if (drained.isNotEmpty()) {
                            val maxTs = drained.maxOf { it.timestamp }
                            MessageRepository.publishDelivered(this, convId, myPhoneHash, maxTs)
                            if (currentlyOpenConversationId == convId) MessageRepository.publishRead(this, convId, myPhoneHash, maxTs)
                        }
                    }
                }
                return
            }
            "group_key_request" -> {
                val requester = senderHash
                if (requester.isNotEmpty() && requester != myPhoneHash) {
                    pl.sinusoide.sinuschat.crypto.GroupKeyManager.respondToKeyRequest(
                        this, convId, myPhoneHash, requester, payload.optBoolean("v2", false))
                }
                return
            }
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.KIND_KEY -> {
                pl.sinusoide.sinuschat.crypto.ProfileCrypto.onKeyUpdate(this, senderHash, payload)
                return
            }
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.KIND_KEY_REQUEST -> {
                if (!GroupRepository.isGroup(convId)) pl.sinusoide.sinuschat.crypto.ProfileCrypto.onKeyRequest(this, senderHash)
                return
            }
            "reaction" -> {
                val targetId = payload.optString("targetMessageId")
                if (targetId.isEmpty()) return
                if (pl.sinusoide.sinuschat.crypto.SignalProtocol.isV2(payload)) {
                    val r = pl.sinusoide.sinuschat.crypto.SignalProtocol.decrypt(this, senderHash, payload)
                    if (r is pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Ok) {
                        val emoji = r.bytes.toString(Charsets.UTF_8).ifEmpty { null }
                        MessageRepository.applyReactionPatchBlocking(this, convId, targetId, senderHash, emoji)
                    }
                    return
                }
                val encEmoji = payload.optString("encryptedEmoji")
                E2EManager.getSharedSecretForUser(this, senderHash) { secret ->
                    val emoji = if (secret != null) E2EManager.decrypt(encEmoji, secret) else null
                    MessageRepository.applyReactionPatchBlocking(this, convId, targetId, senderHash, emoji)
                }
            }
            "pin" -> {
                val targetId = payload.optString("targetMessageId")
                if (targetId.isEmpty()) return
                MessageRepository.applyPinPatchBlocking(this, convId, targetId, payload.optBoolean("pinned", false))
            }
            "delete" -> {
                val targetId = payload.optString("targetMessageId")
                if (targetId.isEmpty()) return
                MessageRepository.applyDeletePatchBlocking(this, convId, senderHash, targetId)
            }
            pl.sinusoide.sinuschat.util.GroupReceipts.KIND -> {
                pl.sinusoide.sinuschat.util.GroupReceipts.onReceipt(this, myPhoneHash, senderHash, payload)
            }
            "expiry_notice" -> {
                val isGroup   = payload.optBoolean("isGroup", convId.startsWith("g_"))
                val total     = payload.optInt("total", 1)
                val delivered = payload.optInt("delivered", 0)
                val original  = payload.optString("originalId")
                MessageRepository.onMessageExpired(this, convId, original, isGroup, delivered, total)
            }
            "disappearing_update" -> {
                val newTtl     = payload.optLong("ttlMs", 0L)
                val newTrigger = payload.optString("trigger", DisappearingMessages.TRIGGER_READ)
                val fromName   = ContactCache.getName(this, senderHash) ?: getString(R.string.chat_partner)
                val isGroup    = GroupRepository.isGroup(convId)

                val action = payload.optString("action", DisappearingCoordinator.ACTION_PROPOSE)

                when {
                    isGroup -> {
                        DisappearingMessages.setTtl(this, convId, newTtl, newTrigger)
                        insertDisappearingSysMsg(convId, getString(
                            R.string.disappearing_update_sys_msg,
                            DisappearingMessages.fullLabel(this, newTtl, newTrigger)))
                    }

                    newTtl <= 0L -> {
                        DisappearingMessages.clearAwaitingConsent(this, convId)
                        DisappearingMessages.clearPending(this, convId)
                        if (DisappearingMessages.getTtl(this, convId) <= 0L) return
                        DisappearingMessages.setTtl(this, convId, 0L, newTrigger)
                        insertDisappearingSysMsg(convId, getString(R.string.disappearing_off))
                    }

                    action == DisappearingCoordinator.ACTION_ACCEPT -> {
                        DisappearingMessages.clearAwaitingConsent(this, convId)
                        val unchanged = newTtl == DisappearingMessages.getTtl(this, convId) &&
                                        newTrigger == DisappearingMessages.getTrigger(this, convId)
                        if (unchanged) return
                        DisappearingMessages.setTtl(this, convId, newTtl, newTrigger)
                        insertDisappearingSysMsg(convId, getString(
                            R.string.disappearing_enabled_sys_msg,
                            DisappearingMessages.label(this, newTtl)))
                    }

                    action == DisappearingCoordinator.ACTION_DECLINE -> {
                        DisappearingMessages.clearAwaitingConsent(this, convId)
                        insertDisappearingSysMsg(convId,
                            getString(R.string.disappearing_declined_by_sys_msg, fromName))
                    }

                    else -> {
                        if (newTtl == DisappearingMessages.getTtl(this, convId) &&
                            newTrigger == DisappearingMessages.getTrigger(this, convId)) return

                        DisappearingMessages.setPending(this, convId, newTtl, newTrigger, fromName)
                        insertDisappearingSysMsg(convId, getString(
                            R.string.disappearing_proposed_sys_msg, fromName,
                            DisappearingMessages.fullLabel(this, newTtl, newTrigger)))

                        if (currentlyOpenConversationId == convId) {
                            sendBroadcast(Intent("pl.sinusoide.sinuschat.DISAPPEARING_REQUEST")
                                .setPackage(packageName)
                                .putExtra("conversationId", convId)
                                .putExtra("otherName", fromName)
                                .putExtra("ttlMs", newTtl)
                                .putExtra("trigger", newTrigger))
                        }
                    }
                }
            }
            "key_hint" -> {
                ApiClient.getObject("/api/user/$senderHash", this)?.optStringOrNull("e2e_public_key")
                    ?.let { syncPeerKey(this, senderHash, it) }
                return
            }
            pl.sinusoide.sinuschat.util.AccountMove.KIND -> {
                if (pl.sinusoide.sinuschat.util.AccountMove.onMoved(this, myPhoneHash, senderHash, payload)) {
                    AppExecutor.main {
                        sendBroadcast(Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(packageName))
                    }
                }
                return
            }
            "edit" -> {
                val targetId = payload.optString("targetMessageId")
                if (targetId.isEmpty()) return
                if (pl.sinusoide.sinuschat.crypto.SignalProtocol.isV2(payload)) {
                    val r = pl.sinusoide.sinuschat.crypto.SignalProtocol.decrypt(this, senderHash, payload)
                    if (r is pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Ok) MessageRepository.applyEditPatchBlocking(
                        this, convId, senderHash, targetId, r.bytes.toString(Charsets.UTF_8))
                    return
                }
                val encText = payload.optString("encryptedText")
                E2EManager.getSharedSecretForUser(this, senderHash) { secret ->
                    val newText = if (secret != null) E2EManager.decrypt(encText, secret) else null
                    if (newText != null) MessageRepository.applyEditPatchBlocking(this, convId, senderHash, targetId, newText)
                }
            }
            ViewOnceManager.KIND_OPENED -> {
                val targetId = payload.optString("targetMessageId")
                if (targetId.isEmpty()) return
                ViewOnceManager.markOpenedLocally(this, convId, targetId)
            }
            "screenshot_attempt" -> {
                val entity = MessageEntity(
                    id = "${msgId}_sys",
                    conversationId = convId,
                    senderHash = "",
                    text = getString(R.string.peer_screenshot_attempt),
                    timestampMs = createdAt,
                    type = "sys_screenshot",
                    isSystemMessage = true,
                    status = MessageEntity.STATUS_READ
                )
                MessageRepository.insertIncomingMessageBlocking(this, entity)
            }
            else -> {
                if (GroupRepository.isGroup(convId)) {
                    val ts = createdAt
                    val decrypted = openGroupContent(convId, msgId, senderHash, payload, "enc", ts) ?: return
                    val side = pl.sinusoide.sinuschat.crypto.GroupKeyManager.openReply(this, convId, senderHash, payload)
                    val capOf = side?.let { runCatching { JSONObject(it).optString("capOf") }.getOrNull() }?.takeIf { it.isNotEmpty() }
                    if (capOf != null && hasCaption(convId, capOf)) {
                        MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
                        return
                    }
                    val (replyId, replyText) = groupReply(side)
                    val tr = pl.sinusoide.sinuschat.util.Translations.parse(side)
                    val entity = MessageEntity(
                        id = msgId,
                        conversationId = convId,
                        senderHash = senderHash,
                        text = decrypted,
                        timestampMs = ts,
                        status = MessageEntity.STATUS_DELIVERED,
                        type = MessageEntity.TYPE_TEXT,
                        replyToId = replyId,
                        replyToText = replyText,
                        trOrig = tr?.orig, trFrom = tr?.from, trTo = tr?.to
                    )
                    MessageRepository.insertIncomingMessageBlocking(this, entity)
                    MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
                    if (currentlyOpenConversationId == convId) {
                        MessageRepository.publishRead(this, convId, myPhoneHash, ts)
                    }
                    maybeNotify(convId, senderHash, entity)
                    return
                }
                if (pl.sinusoide.sinuschat.crypto.SignalProtocol.isV2(payload)) {
                    when (val r = pl.sinusoide.sinuschat.crypto.SignalProtocol.decrypt(this, senderHash, payload)) {
                        is pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Ok -> {
                            val inner = runCatching { JSONObject(r.bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
                            val entity = decryptPayload(convId, msgId, senderHash, payload, null, createdAt, inner) ?: return
                            deliverIncoming(convId, senderHash, entity)
                        }
                        pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Duplicate -> {}
                        pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Failed -> onUndecryptable(convId, msgId, senderHash, createdAt, "v2")
                        pl.sinusoide.sinuschat.crypto.SignalProtocol.Opened.Unavailable ->
                            if (!retryLater(convId, msgId, kind, senderHash, payload, createdAt))
                                onUndecryptable(convId, msgId, senderHash, createdAt, "v2 store")
                    }
                    return
                }
                if (E2EManager.myPrivateKeyBytes(this) == null) {
                    if (!retryLater(convId, msgId, kind, senderHash, payload, createdAt))
                        onUndecryptable(convId, msgId, senderHash, createdAt, "v1 keys")
                    return
                }
                E2EManager.getSharedSecretForUser(this, senderHash) { cached ->
                    AppExecutor.io {
                        var secret = cached
                        if (!opens(payload, secret)) {
                            E2EManager.fetchAndCacheKey(this, senderHash)
                                ?.let { E2EManager.computeSharedSecret(this, it) }
                                ?.let { secret = it }
                        }
                        if (!opens(payload, secret)) {
                            onUndecryptable(convId, msgId, senderHash, createdAt, "v1")
                            return@io
                        }
                        val finalEntity = decryptPayload(convId, msgId, senderHash, payload, secret, createdAt)
                            ?: return@io
                        deliverIncoming(convId, senderHash, finalEntity)
                    }
                }
            }
        }
    }

    private fun hasCaption(convId: String, attachmentId: String): Boolean {
        val local = listOf(attachmentId, attachmentId + "_" + myPhoneHash.take(16))
        return local.any { id ->
            MessageRepository.getByIdBlocking(this, id)
                ?.let { it.conversationId == convId && it.type == MessageEntity.TYPE_ATTACHMENT && it.text.isNotBlank() } == true
        }
    }

    private fun dropCaptionCopy(entity: MessageEntity) {
        if (entity.type != MessageEntity.TYPE_ATTACHMENT || entity.text.isBlank()) return
        val suffix = "_" + myPhoneHash.take(16)
        val base = entity.id.removeSuffix(suffix)
        for (id in listOf(base + MessageRepository.CAPTION_SUFFIX, base + MessageRepository.CAPTION_SUFFIX + suffix)) {
            MessageRepository.getByIdBlocking(this, id)
                ?.takeIf { it.conversationId == entity.conversationId && it.type == MessageEntity.TYPE_TEXT }
                ?.let { MessageRepository.deleteWithMediaBlocking(this, it.id) }
        }
    }

    private fun deliverIncoming(convId: String, senderHash: String, entity: MessageEntity) {
        MessageRepository.insertIncomingMessageBlocking(this, entity)
        dropCaptionCopy(entity)
        val maxTs = entity.timestampMs
        MessageRepository.publishDelivered(this, convId, myPhoneHash, maxTs)
        if (currentlyOpenConversationId == convId) {
            MessageRepository.publishRead(this, convId, myPhoneHash, maxTs)
        }
        maybeNotify(convId, senderHash, entity)
    }

    private fun groupReply(raw: String?): Pair<String?, String?> {
        val j = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null to null
        val id = j.optString("id").takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null to null
        return id to j.optString("text").take(100).ifEmpty { null }
    }

    private fun openGroupContent(
        convId: String, msgId: String, senderHash: String, payload: JSONObject, field: String, ts: Long
    ): String? {
        val gkm = pl.sinusoide.sinuschat.crypto.GroupKeyManager
        val skm = payload.optString("skm")
        if (skm.isNotEmpty()) {
            return when (val r = pl.sinusoide.sinuschat.crypto.SignalProtocol.groupDecrypt(this, senderHash, skm)) {
                is pl.sinusoide.sinuschat.crypto.SignalProtocol.GroupOpened.Ok -> r.bytes.toString(Charsets.UTF_8)
                pl.sinusoide.sinuschat.crypto.SignalProtocol.GroupOpened.NoKey -> {
                    gkm.queuePendingMessage(this, convId, msgId, senderHash, skm, ts, v2 = true,
                        reply = payload.optString(gkm.REPLY_SKM))
                    gkm.requestKeyFrom(this, convId, myPhoneHash, senderHash, v2 = true)
                    MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
                    null
                }
                pl.sinusoide.sinuschat.crypto.SignalProtocol.GroupOpened.Duplicate -> null
                pl.sinusoide.sinuschat.crypto.SignalProtocol.GroupOpened.Failed -> {
                    MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
                    null
                }
            }
        }
        val enc = payload.optString(field)
        if (enc.isEmpty()) return null
        val decrypted = gkm.decryptFromMember(this, convId, senderHash, enc)
        if (decrypted == null) {
            gkm.queuePendingMessage(this, convId, msgId, senderHash, enc, ts, reply = payload.optString(gkm.REPLY_ENC))
            gkm.requestKeyFrom(this, convId, myPhoneHash, senderHash)
            MessageRepository.publishDelivered(this, convId, myPhoneHash, ts)
        }
        return decrypted
    }

    private fun decryptPayload(
        convId: String,
        msgId: String,
        senderHashHint: String,
        payload: JSONObject,
        secret: ByteArray?,
        createdAt: Long,
        plainInner: JSONObject? = null
    ): MessageEntity? {
        val plain = plainInner != null
        val encryptedContent = payload.optString("encryptedContent")
        val innerJson: JSONObject? = plainInner ?: if (encryptedContent.isNotEmpty() && secret != null) {
            val dec = E2EManager.decrypt(encryptedContent, secret)
            if (dec != null) runCatching { JSONObject(dec) }.getOrNull() else null
        } else null
        if (innerJson == null && payload.optString("encryptedText").isEmpty()) return null
        val src = innerJson ?: payload
        val senderHash = senderHashHint.takeIf { it.isNotEmpty() && it != "sealed" } ?: return null
        val innerSender = innerJson?.optString("senderHash")?.takeIf { it.isNotEmpty() }
        if (innerSender != null && innerSender != senderHash) return null
        innerJson?.optString("upk")?.takeIf { it.isNotEmpty() }?.let { pl.sinusoide.sinuschat.crypto.ProfileCrypto.receiveKey(this, senderHash, it) }
        val origId = innerJson?.optString("origId")?.takeIf {
            it.isNotEmpty() && pl.sinusoide.sinuschat.util.ResendRequests.take(this, it, convId, senderHash)
        }
        val type = src.optString("type", payload.optString("type", "text"))
        val ts   = createdAt
        val viewOnce = src.optBoolean("viewOnce", false)
        val expiresAtMs = if (src.has("expiresAtMs")) src.getLong("expiresAtMs") else null
        var text = ""; var fileId: String? = null; var fileKey: String? = null; var attachmentName: String? = null
        if (type == "attachment") {
            fileId = src.optString("fileId").takeIf { it.isNotEmpty() }
            attachmentName = src.optString("attachmentName")
            val encCaption = src.optString("encryptedCaption")
            text = when {
                plain -> src.optString("caption")
                encCaption.isNotEmpty() && secret != null -> E2EManager.decrypt(encCaption, secret).orEmpty()
                else -> ""
            }
            val encKey = src.optString("encryptedFileKey")
            fileKey = when {
                plain -> src.optString("fileKey").takeIf { it.isNotEmpty() }
                encKey.isNotEmpty() && secret != null -> E2EManager.decrypt(encKey, secret)
                else -> encKey
            }
        } else if (type == MessageEntity.TYPE_VOICE) {
            fileId = src.optString("fileId").takeIf { it.isNotEmpty() }
            attachmentName = src.optString("attachmentName").takeIf { it.isNotEmpty() } ?: "0s"
            val encVoiceKey = src.optString("encryptedFileKey")
            fileKey = when {
                plain -> src.optString("fileKey").takeIf { it.isNotEmpty() }
                encVoiceKey.isNotEmpty() && secret != null -> E2EManager.decrypt(encVoiceKey, secret)
                else -> null
            }
            if (fileId == null) return null
        } else {
            val encText = src.optString("encryptedText")
            text = when {
                encText.isNotEmpty() && secret != null -> E2EManager.decrypt(encText, secret) ?: getString(R.string.error_cant_decrypt)
                encText.isNotEmpty() -> getString(R.string.no_e2e_key)
                else -> src.optString("text", "")
            }
        }
        val capOf = src.optString("capOf").takeIf { it.isNotEmpty() && type == MessageEntity.TYPE_TEXT }
        if (capOf != null && hasCaption(convId, capOf)) return null
        val replyToId = src.optString("replyToId").takeIf { it.isNotEmpty() }
        val encReply  = src.optString("encryptedReplyToText")
        val replyToText = when {
            plain -> src.optString("replyToText").takeIf { it.isNotEmpty() }
            encReply.isNotEmpty() && secret != null -> E2EManager.decrypt(encReply, secret)
            else -> null
        }
        val encTr = src.optString("encryptedTr")
        val tr = if (type != MessageEntity.TYPE_TEXT) null else when {
            plain -> pl.sinusoide.sinuschat.util.Translations.parse(src.optJSONObject("tr"))
            encTr.isNotEmpty() && secret != null -> pl.sinusoide.sinuschat.util.Translations.parse(E2EManager.decrypt(encTr, secret))
            else -> null
        }
        val keyCreatedAt = E2EManager.getKeyCreatedAt(this)
        if (keyCreatedAt > 0 && ts < keyCreatedAt) return null
        val entity = MessageEntity(id = origId ?: msgId.ifEmpty { "${senderHash}_$ts" }, conversationId = convId,
            senderHash = senderHash, text = text, timestampMs = ts, status = MessageEntity.STATUS_DELIVERED,
            type = type, fileId = fileId, fileKey = fileKey, attachmentName = attachmentName,
            replyToId = replyToId, replyToText = replyToText, expiresAtMs = expiresAtMs, viewOnce = viewOnce,
            albumId = src.optString("albumId").takeIf { it.isNotEmpty() && type == MessageEntity.TYPE_ATTACHMENT },
            trOrig = tr?.orig, trFrom = tr?.from, trTo = tr?.to)
        if (type == MessageEntity.TYPE_ATTACHMENT && !viewOnce) {
            pl.sinusoide.sinuschat.util.VideoThumbs.saveFromPayload(this, convId, entity.id, attachmentName.orEmpty(), src)
        }
        return entity
    }

    private fun opens(payload: JSONObject, secret: ByteArray?): Boolean {
        val probe = payload.optString("encryptedContent").ifEmpty { payload.optString("encryptedText") }
        if (probe.isEmpty()) return true
        return secret != null && E2EManager.decrypt(probe, secret) != null
    }

    private val resendQueue = HashMap<String, Pair<String, MutableList<String>>>()
    private val flushResend = Runnable {
        val batch = synchronized(resendQueue) { resendQueue.toMap().also { resendQueue.clear() } }
        if (batch.isNotEmpty()) AppExecutor.io {
            for ((convId, entry) in batch) {
                MessageRepository.requestResendBlocking(this, convId, myPhoneHash, entry.first, entry.second)
            }
        }
    }

    private fun retryLater(
        convId: String, msgId: String, kind: String, senderHash: String, payload: JSONObject, createdAt: Long
    ): Boolean {
        val tries = payload.optInt(RETRY_FIELD, 0)
        if (tries >= MAX_RETRIES) return false
        payload.put(RETRY_FIELD, tries + 1)
        val frame = JSONObject()
            .put("type", "message").put("kind", kind).put("senderHash", senderHash)
            .put("conversationId", convId).put("id", msgId).put("createdAt", createdAt).put("sealed", true)
            .put("payloadB64", Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        pl.sinusoide.sinuschat.util.LinkLog.add(this, "decrypt", "klucze chwilowo niedostępne, próba ${tries + 1}")
        pl.sinusoide.sinuschat.util.LockedInbox.add(this, frame.toString())
        mainHandler.postDelayed({ pollPendingMessages() }, 15_000L * (tries + 1))
        return true
    }

    private fun onUndecryptable(convId: String, msgId: String, senderHash: String, createdAt: Long, path: String) {
        pl.sinusoide.sinuschat.util.LinkLog.add(this, "undecryptable", path + if (msgId.contains(MessageRepository.RESEND_MARK)) " (resend)" else "")
        MessageRepository.publishDelivered(this, convId, myPhoneHash, createdAt)
        if (msgId.isEmpty() || GroupRepository.isGroup(convId)) return
        if (msgId.contains(MessageRepository.RESEND_MARK)) return
        if (!pl.sinusoide.sinuschat.util.ResendRequests.remember(this, msgId, convId, senderHash)) return
        val keyCreatedAt = E2EManager.getKeyCreatedAt(this)
        if (keyCreatedAt <= 0 || createdAt >= keyCreatedAt) {
            MessageRepository.insertIncomingMessageBlocking(this, MessageEntity(
                id = msgId, conversationId = convId, senderHash = senderHash,
                text = getString(R.string.msg_waiting_resend), timestampMs = createdAt,
                status = MessageEntity.STATUS_DELIVERED, type = MessageEntity.TYPE_TEXT))
        }
        synchronized(resendQueue) {
            resendQueue.getOrPut(convId) { senderHash to mutableListOf() }.second.add(msgId)
        }
        mainHandler.removeCallbacks(flushResend)
        mainHandler.postDelayed(flushResend, 1_500L)
    }

    private val sealedAcks = ArrayList<String>()
    private val flushSealedAcks = Runnable {
        val ids = synchronized(sealedAcks) { ArrayList(sealedAcks).also { sealedAcks.clear() } }
        if (ids.isNotEmpty()) AppExecutor.io {
            for (chunk in ids.chunked(500)) {
                ApiClient.post("/api/sealed/ack", JSONObject().put("ids", org.json.JSONArray(chunk)), this)
            }
        }
    }

    private fun queueSealedAck(id: String) {
        synchronized(sealedAcks) { sealedAcks.add(id) }
        mainHandler.removeCallbacks(flushSealedAcks)
        mainHandler.postDelayed(flushSealedAcks, 1_000L)
    }

    private val resentAt = HashMap<String, Long>()

    private fun handleResendRequest(convId: String, fromHash: String, payload: JSONObject) {
        if (fromHash.isEmpty() || GroupRepository.isGroup(convId)) return
        if (!isPartner(convId, fromHash)) return
        if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, fromHash)) return
        val ids = payload.optJSONArray("ids") ?: return
        val key = E2EManager.fetchAndCacheKey(this, fromHash) ?: return
        val secret = E2EManager.computeSharedSecret(this, key) ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until minOf(ids.length(), 100)) {
            val id = ids.optString(i).takeIf { it.isNotEmpty() } ?: continue
            val msg = MessageRepository.getByIdBlocking(this, id) ?: continue
            if (msg.conversationId != convId || msg.senderHash != myPhoneHash || msg.isSystemMessage) continue
            if (msg.status == MessageEntity.STATUS_READ || now - msg.timestampMs > 3 * 24 * 3600_000L) continue
            val fresh = synchronized(resentAt) {
                if (now - (resentAt[id] ?: 0L) < 10 * 60_000L) false
                else { resentAt[id] = now; true }
            }
            if (!fresh) continue
            Log.d(TAG, "Ponowna wysyłka — rozmówca ma nowy klucz")
            MessageRepository.resendBlocking(this, msg, secret, fromHash)
        }
    }

    private fun insertGroupChangedBy(groupId: String, byHash: String) {
        pl.sinusoide.sinuschat.util.GroupSystemMessages.insert(this, groupId, getString(
            R.string.group_settings_changed_by_sys,
            pl.sinusoide.sinuschat.util.GroupSystemMessages.nameOf(this, byHash)))
    }

    private fun insertDisappearingSysMsg(convId: String, text: String) {
        MessageRepository.insertIncomingMessageBlocking(this, MessageEntity(
            id = "sys_disappearing_${System.currentTimeMillis()}",
            conversationId = convId, senderHash = "",
            text = text,
            timestampMs = System.currentTimeMillis(),
            type = "sys_disappearing_change",
            isSystemMessage = true, status = MessageEntity.STATUS_READ
        ))
    }

    private fun pollPendingMessages() {
        if (!ApiClient.isAuthenticated(this)) return
        if (!dbWritable()) {
            Log.d(TAG, "Polling pominięty: baza zamknięta (kolejka na serwerze zostaje nietknięta)")
            return
        }
        pollsInFlight.incrementAndGet()
        AppExecutor.io {
            try {
                for (raw in pl.sinusoide.sinuschat.util.LockedInbox.drain(this)) {
                    try { handleIncoming(JSONObject(raw)) } catch (_: Exception) {}
                }
                val arr = ApiClient.getArray("/api/messages/pending", this) ?: return@io
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    handleIncoming(item)
                }
                applyConversationAcks()
            } catch (e: Exception) {
                Log.e(TAG, "polling error", e)
            } finally {
                pollsInFlight.decrementAndGet()
            }
        }
    }

    private fun applyConversationAcks() {
        val arr = ApiClient.getArray("/api/conversations", this) ?: return
        syncPeerKeys(this, arr)
        for (i in 0 until arr.length()) {
            val conv   = arr.getJSONObject(i)
            val convId = conv.optString("id")

            val otherDelUp  = conv.optLong("other_delivered_up_to", 0)
            val otherReadUp = conv.optLong("other_read_up_to", 0)
            val cutOffByThem = pl.sinusoide.sinuschat.util.BlockList.isBlockedByThem(this, conv.optString("other_hash"))
            if (otherDelUp  > 0 && !cutOffByThem) MessageRepository.applyDeliveryAck(this, convId, myPhoneHash, otherDelUp)
            if (otherReadUp > 0 && !cutOffByThem) MessageRepository.applyReadAck(this, convId, myPhoneHash, otherReadUp)

            val myDelUp  = conv.optLong("delivered_up_to", 0)
            if (myDelUp  > 0) MessageRepository.publishDelivered(this, convId, myPhoneHash, myDelUp)
        }
    }

    private fun autoFetchMedia(entity: MessageEntity) {
        if (entity.type != MessageEntity.TYPE_ATTACHMENT || entity.viewOnce || entity.isSystemMessage) return
        if (entity.senderHash.isEmpty() || entity.senderHash == myPhoneHash) return
        if (entity.fileId.isNullOrEmpty() || entity.fileKey.isNullOrEmpty()) return
        if (!pl.sinusoide.sinuschat.util.NetworkSettings.canDownloadNow(this)) return
        AttachmentDownloader.fetch(this, entity, auto = true)
    }

    private var lastMediaCatchUp = 0L

    private fun catchUpMedia() {
        if (!pl.sinusoide.sinuschat.util.NetworkSettings.canDownloadNow(this)) return
        val now = System.currentTimeMillis()
        if (now - lastMediaCatchUp < 60_000L) return
        lastMediaCatchUp = now
        if (pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(this) &&
            pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() == null) return
        val me = myPhoneHash
        if (me.isEmpty()) { lastMediaCatchUp = 0L; return }
        AppExecutor.io {
            val recent = try {
                pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).messageDao()
                    .getRecentIncomingAttachments(me, now - 24 * 3600_000L)
            } catch (_: Exception) { emptyList() }
            for (m in recent) {
                if (!pl.sinusoide.sinuschat.util.MediaManager.isDownloaded(this, m.conversationId, m.id, m.attachmentName ?: "file")) autoFetchMedia(m)
            }
        }
    }

    private fun maybeNotify(convId: String, senderHash: String, entity: MessageEntity) {
        autoFetchMedia(entity)
        if (entity.id.isNotEmpty() && pl.sinusoide.sinuschat.util.LockedInbox.consumeNotified(this, entity.id)) return
        if (currentlyOpenConversationId == convId) return

        val mutePrefs = getSharedPreferences("sinuschat_mute", MODE_PRIVATE)
        val mutedUntil = try {
            mutePrefs.getLong("mute_$convId", 0L)
        } catch (e: ClassCastException) {
            if (mutePrefs.getBoolean("mute_$convId", false)) Long.MAX_VALUE else 0L
        }
        if (mutedUntil == Long.MAX_VALUE || mutedUntil > System.currentTimeMillis()) return
        if (!pl.sinusoide.sinuschat.util.NotificationPrefs.allowMessage(this, convId)) return
        if (!pl.sinusoide.sinuschat.messenger.GroupRepository.isGroup(convId) &&
            pl.sinusoide.sinuschat.util.NotificationPrefs.ignoreRequests(this) &&
            isConversationRequest(convId)) return

        val isSystem = entity.isSystemMessage
        val hasOwnContent = entity.type == "attachment" || entity.type == MessageEntity.TYPE_VOICE
        if (!isSystem && !hasOwnContent && entity.text.isBlank()) return

        if (isSystem) return
        if (entity.id.isNotEmpty()) synchronized(notifiedIds) { if (notifiedIds.put(entity.id, true) != null) return }

        val now = System.currentTimeMillis()
        val burst = now - (lastNotifTime[convId] ?: 0) < 1000
        lastNotifTime[convId] = now

        val preview = when (entity.type) {
            "attachment"              -> getString(R.string.attachment_prefix)
            MessageEntity.TYPE_VOICE  -> getString(R.string.voice_message)
            else                      -> entity.text
        }

        AppExecutor.main {
            pl.sinusoide.sinuschat.util.MessageNotifier.show(this, convId, senderHash, preview, silent = burst,
                messageId = entity.id)
        }
    }

    private fun isNewChatBlocked(convId: String, fromHash: String): Boolean {
        if (GroupRepository.isGroup(convId) || fromHash.isEmpty() || fromHash == myPhoneHash) return false
        if (!pl.sinusoide.sinuschat.util.PrivacySettings.isBlockNewEnabled(this)) return false
        return fromHash !in pl.sinusoide.sinuschat.util.LocalChats.contacts(this, myPhoneHash)
    }

    private fun isConversationRequest(convId: String): Boolean {
        if (getSharedPreferences("sinuschat_accepted_convs", MODE_PRIVATE).getBoolean(convId, false)) return false
        return try {
            val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(this)
            me.isNotEmpty() && pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this)
                .messageDao().countFromSender(convId, me) == 0
        } catch (_: Exception) { false }
    }

    private fun showNotification(convId: String, otherHash: String, preview: String) {
        pl.sinusoide.sinuschat.util.MessageNotifier.show(this, convId, otherHash, preview)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return

        mgr.createNotificationChannel(
            NotificationChannel(FG_CHANNEL_ID, getString(R.string.notif_channel_bg),
                NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.notif_channel_bg_desc)
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
        )
        pl.sinusoide.sinuschat.call.CallNotifier.createChannel(this)
        pl.sinusoide.sinuschat.util.MessageNotifier.createChannel(this)
    }

    private fun updateForegroundNotification() {
        if (!inForeground) return
        try {
            val notif = buildFgNotification()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(FG_NOTIF_ID, notif)
        } catch (e: Exception) { Log.w(TAG, "updateForegroundNotification failed", e) }
    }

    private fun buildFgNotification(): Notification {
        val wrappedCtx = LocaleManager.wrap(this)
        
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setContentTitle("SinusChat")
            .setContentText(wrappedCtx.getString(when {
                !online -> R.string.notif_bg_offline
                onDemand(this) -> R.string.notif_bg_checking
                else -> R.string.notif_bg_listening
            }))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(openApp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        }
        return b.build()
    }
}
