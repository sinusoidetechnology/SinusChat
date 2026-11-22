package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.util.Log
import org.json.JSONObject
import pl.sinusoide.sinuschat.call.CallActivity
import pl.sinusoide.sinuschat.call.CallPrep
import pl.sinusoide.sinuschat.call.GroupCallHub
import pl.sinusoide.sinuschat.call.GroupCallRing
import pl.sinusoide.sinuschat.call.GroupCallSession
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.link.LinkConnection
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.messenger.GroupRepository
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.PrivacySettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PcLinkGroupCalls {

    private const val TAG = "PcLinkGroupCalls"
    private const val PING_MS = 15_000L
    private const val REJOIN_MS = 3_000L
    private const val GONE_GRACE_MS = 30_000L
    private const val SECRET_WAIT_S = 15L
    private const val RING_STOPPED = -1L
    private val SIGNALS = setOf("offer", "answer", "ice", "media")
    private val HASH = Regex("^[a-f0-9]{64}$")

    private class Call(val id: String, val conv: String, val video: Boolean, val me: String, @Volatile var conn: LinkConnection) {
        @Volatile var ended = false
        @Volatile var lastJoinAt = 0L
        @Volatile var gone: ScheduledFuture<*>? = null
        val secrets = ConcurrentHashMap<String, ByteArray>()
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var current: Call? = null
    @Volatile private var pinger: ScheduledFuture<*>? = null
    @Volatile private var app: Context? = null

    fun active(conv: String): Boolean = current?.let { !it.ended && it.conv == conv } == true

    fun busy(): Boolean = current?.ended == false

    fun onFrame(ctx: Context, conn: LinkConnection, frame: LinkFrame) {
        val context = ctx.applicationContext
        app = context
        exec.execute {
            try {
                when (frame) {
                    is LinkFrame.GroupCallJoin -> join(context, conn, frame)
                    is LinkFrame.GroupCallSignal -> fromPc(context, frame)
                    is LinkFrame.GroupCallLeave -> current?.takeIf { it.id == frame.call }?.let { end(it, frame.reason, notifyPc = false) }
                    is LinkFrame.GroupCallDecline -> AppExecutor.main { GroupCallRing.stop(context, frame.conv) }
                    is LinkFrame.GroupCallQuery -> query(context, conn, frame.conv)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "${frame::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun names(ctx: Context, conv: String, members: List<String>): Map<String, String> {
        val known = PcLinkGroups.cached(conv)?.members?.associate { it.userHash to it.displayName }.orEmpty()
        return members.associateWith { h -> known[h]?.ifBlank { null } ?: ContactCache.getName(ctx, h).orEmpty() }
            .filterValues { it.isNotEmpty() }
    }

    private fun stateFrame(ctx: Context, conv: String, state: GroupCallHub.State?, ringMs: Long): LinkFrame.GroupCallState =
        if (state == null) LinkFrame.GroupCallState(conv, false, ringMs = ringMs)
        else LinkFrame.GroupCallState(
            conv = conv,
            active = true,
            video = state.isVideo,
            startedBy = state.startedBy,
            startedAt = state.startedAt,
            members = state.members,
            names = names(ctx, conv, state.members + state.startedBy),
            title = GroupCallRing.groupTitle(ctx, conv),
            ringMs = ringMs
        )

    private fun query(ctx: Context, conn: LinkConnection, conv: String) {
        if (!GroupRepository.isGroup(conv)) return
        GroupCallHub.state(conv)?.let { conn.send(stateFrame(ctx, conv, it, 0L)) }
        GroupCallHub.query(ctx, conv)
    }

    fun onHubState(ctx: Context, conv: String, state: GroupCallHub.State?, ringMs: Long) {
        val context = ctx.applicationContext
        exec.execute {
            try {
                PcLinkHub.broadcastFrame(stateFrame(context, conv, state, ringMs))
                val call = current?.takeIf { !it.ended && it.conv == conv } ?: return@execute
                val now = System.currentTimeMillis()
                if (now - call.lastJoinAt < REJOIN_MS) return@execute
                when {
                    state == null -> {
                        call.lastJoinAt = now
                        GroupCallHub.send("gcall_start", conv, JSONObject().put("kind", kindOf(call.video)))
                    }
                    call.me !in state.members -> {
                        call.lastJoinAt = now
                        GroupCallHub.send("gcall_join", conv, JSONObject().put("kind", kindOf(call.video)))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stan: ${e.message}")
            }
        }
    }

    fun onRingStopped(ctx: Context, conv: String) {
        val context = ctx.applicationContext
        exec.execute { PcLinkHub.broadcastFrame(stateFrame(context, conv, GroupCallHub.state(conv), RING_STOPPED)) }
    }

    private fun kindOf(video: Boolean) = if (video) GroupCallHub.KIND_VIDEO else GroupCallHub.KIND_AUDIO

    private fun join(ctx: Context, conn: LinkConnection, f: LinkFrame.GroupCallJoin) {
        fun fail(reason: String) { conn.send(LinkFrame.GroupCallLeave(f.call, f.conv, reason)) }
        val existing = current?.takeIf { !it.ended }
        if (existing != null && existing.id == f.call) {
            existing.conn = conn
            existing.gone?.cancel(false)
            existing.gone = null
            return
        }
        if (!GroupRepository.isGroup(f.conv) || PcLinkHub.phoneLocked()) return fail(LinkFrame.CALL_FAILED)
        if (existing != null) return fail(LinkFrame.CALL_BUSY)
        val phoneSession = GroupCallSession.current?.takeIf { !it.ended }
        if (phoneSession != null) return fail(if (phoneSession.groupId == f.conv) LinkFrame.CALL_ON_PHONE else LinkFrame.CALL_BUSY)
        if (PcLinkCalls.busy() || CallActivity.activeConversationId != null) return fail(LinkFrame.CALL_BUSY)
        if (!PcLinkGroups.canCall(f.conv)) return fail(LinkFrame.CALL_FORBIDDEN)
        if (!WebSocketManager.isConnected()) WebSocketManager.connect(ctx)
        val turn = try { CallPrep.turn(ctx) } catch (_: Exception) { null } ?: return fail(LinkFrame.CALL_FAILED)
        val me = SetupActivity.getUserPhoneHash(ctx)
        val running = GroupCallHub.state(f.conv)
        val video = running?.isVideo ?: f.video
        val call = Call(f.call, f.conv, video, me, conn)
        current = call
        AppExecutor.main { GroupCallRing.stop(ctx, f.conv) }
        conn.send(LinkFrame.GroupCallSetup(
            f.call, f.conv, me, video, PcLinkCalls.iceServers(turn), PrivacySettings.isCallRelayEnabled(ctx),
            GroupCallRing.groupTitle(ctx, f.conv)
        ))
        call.lastJoinAt = System.currentTimeMillis()
        GroupCallHub.send(if (running == null && f.start) "gcall_start" else "gcall_join", f.conv, JSONObject().put("kind", kindOf(video)))
        pinger?.cancel(false)
        pinger = exec.scheduleWithFixedDelay({
            if (current === call && !call.ended) GroupCallHub.send("gcall_ping", call.conv)
        }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
        GroupCallHub.takeBuffered(f.conv).forEach { relay(ctx, call, it.optString("from"), it.optString("payload")) }
        running?.let { conn.send(stateFrame(ctx, f.conv, it, 0L)) }
    }

    private fun secret(ctx: Context, call: Call, peer: String): ByteArray? {
        call.secrets[peer]?.let { return it }
        val key = E2EManager.getSharedSecretCached(ctx, peer) ?: run {
            val out = AtomicReference<ByteArray?>(null)
            val latch = CountDownLatch(1)
            E2EManager.getSharedSecretForUser(ctx, peer) { s ->
                out.set(s)
                latch.countDown()
            }
            latch.await(SECRET_WAIT_S, TimeUnit.SECONDS)
            out.get()
        } ?: return null
        call.secrets[peer] = key
        return key
    }

    fun onServerSignal(conv: String, from: String, payload: String): Boolean {
        val call = current?.takeIf { !it.ended && it.conv == conv } ?: return false
        val ctx = app ?: return false
        exec.execute {
            try {
                relay(ctx, call, from, payload)
            } catch (e: Exception) {
                Log.w(TAG, "sygnał: ${e.message}")
            }
        }
        return true
    }

    private fun relay(ctx: Context, call: Call, from: String, payload: String) {
        if (call.ended || from.isEmpty() || from == call.me || payload.isEmpty()) return
        val key = secret(ctx, call, from) ?: return
        val text = E2EManager.decrypt(payload, key) ?: return
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (o.optString("g") != call.conv || o.optString("f") != from || o.optString("t") !in SIGNALS) return
        o.remove("g")
        o.remove("f")
        call.conn.send(LinkFrame.GroupCallSignal(call.id, from, o.toString()))
    }

    private fun fromPc(ctx: Context, f: LinkFrame.GroupCallSignal) {
        val call = current?.takeIf { it.id == f.call && !it.ended } ?: return
        val peer = f.peer.lowercase()
        if (!HASH.matches(peer) || peer == call.me) return
        val o = runCatching { JSONObject(f.body) }.getOrNull() ?: return
        if (o.optString("t") !in SIGNALS) return
        o.put("g", call.conv).put("f", call.me)
        val key = secret(ctx, call, peer) ?: return
        val payload = E2EManager.encrypt(o.toString(), key) ?: return
        GroupCallHub.send("gcall_signal", call.conv, JSONObject().put("to", peer).put("payload", payload))
    }

    fun onServerError(conv: String, reason: String): Boolean {
        val call = current?.takeIf { !it.ended && it.conv == conv } ?: return false
        exec.execute {
            end(call, if (reason == "group_call_forbidden") LinkFrame.CALL_FORBIDDEN else LinkFrame.CALL_FAILED, notifyPc = true)
        }
        return true
    }

    fun endFor(conv: String, reason: String) {
        val call = current?.takeIf { !it.ended && it.conv == conv } ?: return
        call.ended = true
        if (current === call) current = null
        GroupCallHub.send("gcall_leave", conv)
        exec.execute {
            pinger?.cancel(false)
            pinger = null
            runCatching { call.conn.send(LinkFrame.GroupCallLeave(call.id, conv, reason)) }
        }
    }

    fun onPhoneTakeover(conv: String) = endFor(conv, LinkFrame.CALL_ON_PHONE)

    fun onPcGone(conn: LinkConnection) {
        exec.execute {
            val call = current?.takeIf { !it.ended && it.conn === conn } ?: return@execute
            call.gone?.cancel(false)
            call.gone = exec.schedule({
                if (current === call && call.conn === conn) end(call, LinkFrame.CALL_HANGUP, notifyPc = false)
            }, GONE_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun end(call: Call, reason: String, notifyPc: Boolean) {
        if (call.ended && current !== call) return
        call.ended = true
        if (current === call) current = null
        call.gone?.cancel(false)
        pinger?.cancel(false)
        pinger = null
        GroupCallHub.send("gcall_leave", call.conv)
        if (notifyPc) runCatching { call.conn.send(LinkFrame.GroupCallLeave(call.id, call.conv, reason)) }
    }
}
