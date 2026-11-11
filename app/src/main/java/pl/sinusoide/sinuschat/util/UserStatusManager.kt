package pl.sinusoide.sinuschat.util

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import pl.sinusoide.sinuschat.network.ApiClient

object UserStatusManager {

    private const val TAG = "UserStatusDBG"

    private val thread = HandlerThread("sinuschat-heartbeat").apply { start() }
    private val handler = Handler(thread.looper)

    private const val HEARTBEAT_INTERVAL_MS = 15_000L
    private const val RETRY_INTERVAL_MS     = 5_000L
    private const val SEEN_PREFS = "sinuschat_activity_day"
    private const val KEY_SEEN = "seen"
    private const val SEEN_SAVE_EVERY_MS = 60_000L
    @Volatile private var seenSavedAt = 0L

    private var myHash: String = ""
    private var appContext: android.content.Context? = null

    @Volatile private var running = false
    @Volatile private var remote = false
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)

    val isActive: Boolean get() = active.get()

    val isRemoteActive: Boolean get() = remote

    fun takeActive(): Boolean {
        if (remote) return false
        val was = active.getAndSet(false)
        if (was) stopLoop()
        return was
    }

    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private fun hidden(): Boolean {
        val ctx = appContext ?: return false
        return PrivacySettings.isActivityStatusDisabled(ctx)
    }

    private fun startLoop() {
        val gen = generation.incrementAndGet()
        running = true
        handler.post(object : Runnable {
            override fun run() {
                if (!running || gen != generation.get()) return
                val ok = writeHeartbeat()
                if (!running || gen != generation.get()) return
                handler.postDelayed(this, if (ok) HEARTBEAT_INTERVAL_MS else RETRY_INTERVAL_MS)
            }
        })
    }

    private fun stopLoop() {
        running = false
        generation.incrementAndGet()
    }

    fun init(phoneHash: String, context: android.content.Context? = null) {
        myHash = phoneHash
        if (context != null) appContext = context.applicationContext
        if (hidden()) {
            Log.d(TAG, "init: ukrycie=true → jednorazowe czyszczenie, potem CISZA")
            clearActivityOnce()
        }
    }

    private fun ensureReady(context: android.content.Context?) {
        if (context != null && appContext == null) appContext = context.applicationContext
        if (myHash.isEmpty()) {
            val ctx = appContext ?: context ?: return
            myHash = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx)
        }
    }

    fun onForeground(context: android.content.Context? = null) {
        ensureReady(context)
        stopLoop()
        pl.sinusoide.sinuschat.network.WebSocketManager.resumePing()
        if (hidden()) {
            active.set(false)
            touchActiveDay()
            Log.d(TAG, "onForeground: ukrycie=true → bez statusu, tylko dzień użycia konta")
            return
        }
        active.set(true)
        startLoop()
    }

    fun onForegroundIfIdle(context: android.content.Context? = null) {
        if (active.get()) return
        onForeground(context)
    }

    fun setRemoteActive(context: android.content.Context?, on: Boolean) {
        ensureReady(context)
        if (remote == on) return
        remote = on
        if (on) {
            pl.sinusoide.sinuschat.network.WebSocketManager.resumePing()
            if (hidden()) {
                touchActiveDay()
                return
            }
            if (active.compareAndSet(false, true)) startLoop()
            return
        }
        if (pl.sinusoide.sinuschat.SinusApp.isForeground) return
        onBackground(null)
    }

    fun onBackground(context: android.content.Context? = null) {
        ensureReady(context)
        if (remote) return
        stopLoop()
        pl.sinusoide.sinuschat.network.WebSocketManager.stopPing()
        val wasActive = active.getAndSet(false)
        if (hidden()) {
            Log.d(TAG, "onBackground: ukrycie=true → CISZA")
            return
        }
        if (!wasActive) return
        handler.post { writeOfflineNow() }
    }

    fun onScreenUnlocked(context: android.content.Context? = null) {
        if (!pl.sinusoide.sinuschat.SinusApp.isForeground) {
            Log.d(TAG, "onScreenUnlocked: apka w tle → nie wznawiam pulsu")
            return
        }
        onForeground(context)
    }

    fun goInvisibleNow(context: android.content.Context? = null) {
        ensureReady(context)
        stopLoop()
        active.set(false)
        clearActivityOnce()
    }

    private fun clearActivityOnce() {
        val ctx = appContext ?: return
        Log.d(TAG, "clearActivityOnce: heartbeat=0, lastSeen=0 (raz), potem CISZA")
        handler.post {
            ApiClient.post("/api/heartbeat", body(heartbeatMs = 0L, lastSeenMs = 0L), ctx)
        }
    }

    private fun touchActiveDay() {
        val ctx = appContext ?: return
        val day = System.currentTimeMillis() / 86_400_000L
        val prefs = ctx.getSharedPreferences("sinuschat_activity_day", android.content.Context.MODE_PRIVATE)
        if (prefs.getLong("day", -1L) == day) return
        handler.post {
            if (ApiClient.post("/api/heartbeat", JSONObject().put("touchOnly", true), ctx) != null)
                prefs.edit().putLong("day", day).apply()
        }
    }

    fun resendAfterServerStart(context: android.content.Context?) {
        ensureReady(context)
        val ctx = appContext ?: return
        if (myHash.isEmpty() || hidden()) return
        if (active.get()) {
            handler.post { writeHeartbeat() }
            return
        }
        val seen = ctx.getSharedPreferences(SEEN_PREFS, android.content.Context.MODE_PRIVATE).getLong(KEY_SEEN, 0L)
        if (seen <= 0L) return
        handler.post { ApiClient.post("/api/heartbeat", body(0L, seen), ctx) }
    }

    private fun saveSeen(ctx: android.content.Context, at: Long) {
        seenSavedAt = at
        ctx.getSharedPreferences(SEEN_PREFS, android.content.Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, at).apply()
    }

    private fun body(heartbeatMs: Long, lastSeenMs: Long) = JSONObject()
        .put("heartbeatMs", heartbeatMs)
        .put("lastSeenMs", lastSeenMs)
        .put("last_seen_ms", lastSeenMs)

    private fun writeHeartbeat(): Boolean {
        ensureReady(null)
        val ctx = appContext ?: return false
        if (myHash.isEmpty()) return false
        if (hidden()) return true
        val now = System.currentTimeMillis()
        val ok = ApiClient.post("/api/heartbeat", body(now, now), ctx) != null
        if (!ok) Log.w(TAG, "heartbeat nie doszedł — ponowię za ${RETRY_INTERVAL_MS}ms")
        else if (now - seenSavedAt >= SEEN_SAVE_EVERY_MS) saveSeen(ctx, now)
        return ok
    }

    private fun writeOfflineNow() {
        ensureReady(null)
        val ctx = appContext ?: return
        if (myHash.isEmpty()) return
        val now = System.currentTimeMillis()
        saveSeen(ctx, now)
        ApiClient.post("/api/heartbeat", body(0L, now), ctx)
    }
}
