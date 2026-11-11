package pl.sinusoide.sinuschat.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import pl.sinusoide.sinuschat.messenger.MessageListenerService
import pl.sinusoide.sinuschat.util.AppExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WebSocketManager {

    private const val TAG              = "WebSocket"
    private const val WS_URL           = "wss://api.sinusoide.pl/ws"
    private const val PING_INTERVAL_MS = 30_000L
    private const val PING_INTERVAL_ACTIVE_MS = 10_000L
    private const val RECONNECT_MIN_MS = 1_000L
    private const val RECONNECT_MAX_MS = 30_000L

    private val mainHandler   = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private val authed        = AtomicBoolean(false)
    @Volatile private var lastPongMs = 0L
    private const val PONG_WAIT_MS = 15_000L
    private const val KICK_CHECK_TRIES = 6
    private const val KICK_CHECK_DELAY_MS = 10_000L
    private val active        = AtomicBoolean(false)
    private val connecting    = AtomicBoolean(false)
    private var appCtx: Context? = null
    private var reconnectDelay = RECONNECT_MIN_MS
    private var netCallbackRegistered = false

    private val outbox = ArrayDeque<String>()

    var onMessage: ((JSONObject) -> Unit)? = null

    var onTypingCallback: ((conversationId: String, senderHash: String, typing: Boolean) -> Unit)? = null

    fun dispatchTyping(conversationId: String, senderHash: String, typing: Boolean) {
        AppExecutor.main { onTypingCallback?.invoke(conversationId, senderHash, typing) }
        pl.sinusoide.sinuschat.pclink.PcLinkHub.onTyping(conversationId, senderHash, typing)
    }
    var onPresenceCallback: ((userHash: String, heartbeatMs: Long, lastSeenMs: Long) -> Unit)? = null

    private val callEventBuffer = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    var onCallEvent: ((JSONObject) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                while (callEventBuffer.isNotEmpty()) {
                    callEventBuffer.poll()?.let { buffered ->
                        mainHandler.post { value.invoke(buffered) }
                    }
                }
            } else {
                callEventBuffer.clear()
            }
        }

    var onReconnected: (() -> Unit)? = null

    private fun buildClient(context: Context?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
        if (context != null && pl.sinusoide.sinuschat.util.PrivacySettings.isTorEnabled(context)) {
            builder.proxy(
                java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", pl.sinusoide.sinuschat.tor.TorManager.socksPort)
                )
            )
        }
        return builder.build()
    }

    private val pingTask = object : Runnable {
        override fun run() {
            val sent = android.os.SystemClock.elapsedRealtime()
            send(JSONObject().put("type", "ping"))
            mainHandler.postDelayed({
                if (authed.get() && lastPongMs < sent && inUse()) {
                    Log.d(TAG, "ping: brak odpowiedzi serwera, łączę ponownie")
                    forceReconnect()
                }
            }, PONG_WAIT_MS)
            val isChatOpen = MessageListenerService.currentlyOpenConversationId != null
            val next = if (isChatOpen) PING_INTERVAL_ACTIVE_MS else PING_INTERVAL_MS
            mainHandler.postDelayed(this, next)
        }
    }

    @Volatile private var stopKicked = false
    @Volatile private var serverBoot = 0L
    @Volatile private var serverCaps: Set<String> = emptySet()

    fun serverHas(cap: String): Boolean = cap in serverCaps

    private fun inUse() = pl.sinusoide.sinuschat.SinusApp.isForeground ||
        pl.sinusoide.sinuschat.util.UserStatusManager.isRemoteActive

    fun isConnected() = authed.get()

    fun stopPing() {
        mainHandler.removeCallbacks(pingTask)
        if (authed.get()) sendBackground(true)
    }

    fun resumePing() {
        mainHandler.removeCallbacks(pingTask)
        if (authed.get()) {
            sendBackground(false)
            mainHandler.post(pingTask)
        }
    }

    private fun sendBackground(on: Boolean) {
        try { socket?.send(JSONObject().put("type", "bg").put("on", on).toString()) } catch (_: Exception) {}
    }

    fun keepAlive() {
        if (!authed.get()) {
            if (!connecting.get()) mainHandler.post { doConnect() }
            return
        }
        val sent = android.os.SystemClock.elapsedRealtime()
        send(JSONObject().put("type", "ping"))
        mainHandler.postDelayed({
            if (authed.get() && lastPongMs < sent) {
                Log.d(TAG, "keepAlive: brak odpowiedzi serwera, łączę ponownie")
                forceReconnect()
            }
        }, PONG_WAIT_MS)
    }

    fun connect(context: Context) {
        appCtx = context.applicationContext
        registerNetworkCallback()
        if (!active.compareAndSet(false, true)) {
            if (!authed.get()) reconnectNow()
            return
        }
        reconnectDelay = RECONNECT_MIN_MS
        doConnect()
    }

    fun disconnect() {
        active.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "service stopped")
        socket = null
        authed.set(false)
        Log.d(TAG, "Disconnected")
    }

    fun reset() {
        disconnect()
        stopKicked = false
    }

    fun clearKick(context: Context) {
        stopKicked = false
        connect(context)
    }

    fun reconnectNow() {
        if (stopKicked) return
        if (!active.get()) return
        if (authed.get()) return
        if (connecting.get()) return
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectDelay = RECONNECT_MIN_MS
        mainHandler.post { doConnect() }
    }

    private val reconnectRunnable = Runnable { doConnect() }

    private val staleSockets: MutableSet<WebSocket> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun dropQueuedCall(conversationId: String) {
        synchronized(outbox) {
            outbox.removeAll { frame ->
                val m = runCatching { JSONObject(frame) }.getOrNull()
                m != null && m.optString("type").startsWith("call_") && m.optString("conversationId") == conversationId
            }
        }
    }

    private fun routeCallEvent(msg: JSONObject) {
        val handler = onCallEvent
        val t = msg.optString("type")
        val ending = t == "call_end" || t == "call_reject"
        val conv = msg.optString("conversationId")
        val activeConv = pl.sinusoide.sinuschat.call.CallActivity.activeConversationId
        val forActive = conv.isEmpty() || activeConv == null || activeConv == conv
        if (handler != null && (!ending || forActive)) {
            handler.invoke(msg)
        } else if (ending) {
            onMessage?.invoke(msg)
        } else {
            if (t == "call_answer" || t == "call_ice") {
                if (callEventBuffer.size > 100) callEventBuffer.clear()
                callEventBuffer.add(msg)
                Log.d(TAG, "Buforuję $t (CallActivity nie gotowe)")
            } else {
                Log.d(TAG, "Pomijam $t (brak aktywnego połączenia)")
            }
        }
    }

    fun send(json: JSONObject): Boolean {
        val text = json.toString()
        val ok = socket?.send(text) ?: false
        if (!ok) {
            synchronized(outbox) {
                if (outbox.size > 50) outbox.removeFirst()
                outbox.addLast(text)
            }
            if (active.get() && !authed.get()) reconnectNow()
        }
        return ok
    }

    private fun flushOutbox() {
        val s = socket ?: return
        synchronized(outbox) {
            while (outbox.isNotEmpty()) {
                val frame = outbox.removeFirst()
                if (!s.send(frame)) { outbox.addFirst(frame); break }
            }
        }
    }

    private val netCheckRunnable = Runnable {
        if (active.get() && !authed.get() && !connecting.get()) {
            Log.d(TAG, "netCheck: brak połączenia po zmianie sieci → łączę")
            doConnect()
        }
    }

    private fun registerNetworkCallback() {
        if (netCallbackRegistered) return
        val ctx = appCtx ?: return
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        mainHandler.removeCallbacks(netCheckRunnable)
                        mainHandler.postDelayed(netCheckRunnable, 3_000L)
                    }
                }
            )
            netCallbackRegistered = true
        } catch (e: Exception) { Log.w(TAG, "networkCallback: ${e.message}") }
    }

    private fun doConnect() {
        val ctx = appCtx ?: run { Log.w(TAG, "doConnect: appCtx == null"); return }
        val token = ApiClient.getToken(ctx) ?: run {
            Log.w(TAG, "doConnect: brak tokenu JWT — uruchamiam auto-rejestrację")
            AppExecutor.io {
                ApiClient.autoRegisterIfNeeded(ctx) { ok ->
                    Log.d(TAG, "auto-rejestracja: $ok")
                    if (ok) { connecting.set(false); reconnectNow() }
                }
            }
            return
        }

        if (!connecting.compareAndSet(false, true)) { Log.d(TAG, "doConnect: już łączę"); return }
        if (authed.get()) { connecting.set(false); return }
        Log.d(TAG, "doConnect[v5]: łączę z $WS_URL")

        socket?.let { old ->
            staleSockets.add(old)
            if (staleSockets.size > 20) staleSockets.clear()
            old.close(1000, "reconnect")
        }
        val req = Request.Builder().url(WS_URL).build()
        socket = buildClient(appCtx).newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val authFrame = JSONObject().put("type", "auth").put("token", token)
                    .put("caps", org.json.JSONArray().put("call_ack").put("gcall_ack").put(pl.sinusoide.sinuschat.call.CallCrypto.CAP))
                    .toString()
                val ok = webSocket.send(authFrame)
                Log.d(TAG, "Open → auth wysłane=$ok, tokenLen=${token.length}, HTTP=${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "device_kicked" -> {
                        if (webSocket != socket) {
                            Log.w(TAG, "device_kicked na starym sockecie — ignoruję (własny reconnect)")
                            runCatching { webSocket.close(1000, "superseded") }
                            return
                        }
                        Log.w(TAG, "device_kicked — sprawdzam na serwerze")
                        authed.set(false)
                        runCatching { webSocket.close(1000, "device_kicked") }
                        AppExecutor.io { confirmKick(ctx, 0) }
                    }
                    "auth_ok" -> {
                        authed.set(true)
                        connecting.set(false)
                        reconnectDelay = RECONNECT_MIN_MS
                        mainHandler.removeCallbacks(pingTask)
                        if (inUse()) mainHandler.post(pingTask)
                        else sendBackground(true)
                        flushOutbox()
                        Log.d(TAG, "Authenticated as ${msg.optString("hash")}")
                        serverCaps = msg.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                        val boot = msg.optLong("boot", 0L)
                        if (boot > 0L && boot != serverBoot) {
                            serverBoot = boot
                            pl.sinusoide.sinuschat.util.UserStatusManager.resendAfterServerStart(ctx)
                        }
                        AppExecutor.main { onReconnected?.invoke() }
                    }
                    "auth_error" -> {
                        Log.e(TAG, "Auth error — token nieważny, kasuję i ponawiam rejestrację")
                        authed.set(false)
                        ApiClient.clearToken(ctx)
                        AppExecutor.main {
                            ApiClient.autoRegisterIfNeeded(ctx) { ok -> if (ok) reconnectNow() }
                        }
                    }
                    "pong" -> { lastPongMs = android.os.SystemClock.elapsedRealtime() }
                    "typing" -> {
                        val typingConvId = msg.optString("conversationId")
                        val senderHash = msg.optString("senderHash")
                        if (typingConvId.isNotEmpty()) dispatchTyping(typingConvId, senderHash, true)
                    }
                    "typing_stop" -> {
                        val typingConvId = msg.optString("conversationId")
                        val senderHash = msg.optString("senderHash")
                        if (typingConvId.isNotEmpty()) dispatchTyping(typingConvId, senderHash, false)
                    }
                    "presence" -> {
                        val userHash = msg.optString("senderHash")
                        val heartbeat = msg.optLong("heartbeatMs", 0L)
                        val lastSeen = msg.optLong("lastSeenMs", 0L)
                        if (userHash.isNotEmpty()) {
                            pl.sinusoide.sinuschat.pclink.PcLinkHub.onPresence(userHash, heartbeat, lastSeen)
                            AppExecutor.main { onPresenceCallback?.invoke(userHash, heartbeat, lastSeen) }
                        }
                    }
                    "group_settings_changed" -> {
                        val gId = msg.optString("groupId")
                        val ctx = appCtx
                        if (gId.isNotEmpty() && ctx != null) {
                            if (msg.has("disappearingTtlMs") && !msg.isNull("disappearingTtlMs")) {
                                val ttl = msg.optLong("disappearingTtlMs", 0L)
                                pl.sinusoide.sinuschat.util.DisappearingMessages.setTtl(ctx, gId, ttl)
                                pl.sinusoide.sinuschat.util.GroupSystemMessages.disappearingChanged(ctx, gId, ttl)
                            }
                            if (msg.has("perms") && !msg.isNull("perms")) {
                                pl.sinusoide.sinuschat.util.GroupSystemMessages.settingsChanged(ctx, gId)
                            }
                            ctx.sendBroadcast(android.content.Intent(
                                pl.sinusoide.sinuschat.messenger.ChatActivity.GROUP_SETTINGS_CHANGED)
                                .setPackage(ctx.packageName).putExtra("groupId", gId))
                        }
                    }
                    "invite_used" -> {
                        val convId = msg.optString("conversationId")
                        val ctx = appCtx
                        if (convId.isNotEmpty() && ctx != null) {
                            ctx.getSharedPreferences("sinuschat_accepted_convs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean(convId, true).apply()
                            msg.optString("byHash").takeIf { it.isNotEmpty() }?.let { pl.sinusoide.sinuschat.util.LocalChats.add(ctx, convId, it) }
                            AppExecutor.main {
                                ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")
                                    .setPackage(ctx.packageName))
                            }
                        }
                    }
                    "report_update" -> {
                        val id = msg.optInt("id", 0)
                        val ctx = appCtx
                        if (id > 0 && ctx != null) {
                            AppExecutor.main {
                                pl.sinusoide.sinuschat.util.ReportNotifier.onUpdate(ctx, id,
                                    msg.optString("status"), msg.optBoolean("replied", false))
                            }
                        }
                    }
                    "group_kicked" -> {
                        val gId = msg.optString("groupId")
                        if (gId.isNotEmpty()) {
                            appCtx?.let { c ->
                                AppExecutor.io {
                                    try {
                                        pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(c)
                                            .messageDao().clearConversation(gId)
                                    } catch (_: Exception) {}
                                    try { pl.sinusoide.sinuschat.util.MediaManager.clearConversationMedia(c, gId) } catch (_: Exception) {}
                                    pl.sinusoide.sinuschat.crypto.GroupKeyManager.clearGroup(c, gId)
                                    AppExecutor.main {
                                        c.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")
                                            .setPackage(c.packageName))
                                    }
                                }
                            }
                        }
                    }
                    "call_answer", "call_ice", "call_reject", "call_end", "call_reoffer", "call_reanswer" -> {
                        val route: (JSONObject) -> Unit = { m -> AppExecutor.main { routeCallEvent(m) } }
                        val c = appCtx
                        if (c != null) pl.sinusoide.sinuschat.call.CallCrypto.open(c, msg, route) else route(msg)
                    }
                    "gcall_state", "gcall_signal", "gcall_error" -> {
                        if (msg.optString("type") == "gcall_state" && msg.optBoolean("active")) runCatching {
                            webSocket.send(JSONObject().put("type", "gcall_ack")
                                .put("conversationId", msg.optString("conversationId")).toString())
                        }
                        val c = appCtx
                        if (c != null) pl.sinusoide.sinuschat.call.GroupCallHub.onServerMessage(c, msg)
                    }
                    "call_offer" -> {
                        Log.d(TAG, "call_offer ODEBRANY, onMessage=${onMessage != null}")
                        runCatching {
                            webSocket.send(JSONObject().put("type", "call_offer_ack")
                                .put("conversationId", msg.optString("conversationId")).toString())
                        }
                        val c = appCtx
                        if (c != null) pl.sinusoide.sinuschat.call.CallCrypto.open(c, msg) { m -> onMessage?.invoke(m) }
                        else AppExecutor.main { onMessage?.invoke(msg) }
                    }
                    else -> onMessage?.invoke(msg)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed ($code): $reason"); onLost(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failure: ${t.message} | HTTP ${response?.code} ${response?.message}")
                appCtx?.let { pl.sinusoide.sinuschat.util.LinkLog.addThrottled(it, "ws", "${t.javaClass.simpleName} ${response?.code ?: ""}".trim()) }
                onLost(webSocket)
            }
        })
    }

    fun forceReconnect() {
        if (stopKicked || !active.get()) return
        if (connecting.get()) return
        Log.d(TAG, "forceReconnect (push połączenia)")
        authed.set(false)
        mainHandler.removeCallbacks(pingTask)
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectDelay = RECONNECT_MIN_MS
        mainHandler.post { doConnect() }
    }

    private fun confirmKick(ctx: Context, tries: Int) {
        when (ApiClient.stillActive(ctx)) {
            false -> {
                Log.w(TAG, "device_kicked potwierdzony — zalogowano na innym urządzeniu")
                stopKicked = true
                AppExecutor.main { ApiClient.onDeviceKicked(ctx) }
            }
            true -> {
                Log.w(TAG, "device_kicked bez potwierdzenia serwera — łączę ponownie")
                pl.sinusoide.sinuschat.util.LinkLog.add(ctx, "kick", "fałszywy device_kicked, sesja aktywna")
                mainHandler.post { reconnectNow() }
            }
            null -> if (tries < KICK_CHECK_TRIES) {
                mainHandler.postDelayed({ AppExecutor.io { confirmKick(ctx, tries + 1) } }, KICK_CHECK_DELAY_MS)
            }
        }
    }

    private fun onLost(ws: WebSocket) {
        if (staleSockets.remove(ws)) { Log.d(TAG, "Pomijam zamknięcie starego gniazda"); return }
        authed.set(false)
        connecting.set(false)
        socket = null
        mainHandler.removeCallbacks(pingTask)
        if (active.get()) {
            val delay = reconnectDelay
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(RECONNECT_MAX_MS)
            Log.d(TAG, "Reconnect za ${delay}ms")
            mainHandler.postDelayed({ doConnect() }, delay)
        }
    }
}
