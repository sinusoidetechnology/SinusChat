package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.call.CallActivity
import pl.sinusoide.sinuschat.call.CallCrypto
import pl.sinusoide.sinuschat.call.CallNotifier
import pl.sinusoide.sinuschat.call.CallPrep
import pl.sinusoide.sinuschat.call.GroupCallSession
import pl.sinusoide.sinuschat.call.IncomingCallActivity
import pl.sinusoide.sinuschat.data.CallLogRepository
import pl.sinusoide.sinuschat.link.IceServer
import pl.sinusoide.sinuschat.link.LinkConnection
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.messenger.GroupRepository
import pl.sinusoide.sinuschat.messenger.MessageRepository
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.PrivacySettings
import java.util.UUID
import java.util.concurrent.Executors

object PcLinkCalls {

    private const val TAG = "PcLinkCalls"
    private val FROM_PC = setOf("call_offer", "call_answer", "call_ice", "call_reoffer", "call_reanswer", "call_screen")

    private class Call(
        val id: String,
        val conv: String,
        val peer: String,
        val name: String,
        val video: Boolean,
        val caller: Boolean,
        val offer: String?
    ) {
        @Volatile var conn: LinkConnection? = null
        @Volatile var channel: CallCrypto.Channel? = null
        @Volatile var accepted = false
        @Volatile var answeredAt = 0L
        @Volatile var handler: ((JSONObject) -> Unit)? = null
        val startedAt = System.currentTimeMillis()
    }

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var current: Call? = null

    fun busy(): Boolean = current?.accepted == true

    fun onIncoming(ctx: Context, conv: String, peer: String, name: String, video: Boolean, offer: String) {
        if (offer.isEmpty() || PcLinkServer.connectedCount() == 0 || PcLinkHub.phoneLocked()) return
        exec.execute {
            if (current?.accepted == true) return@execute
            val call = Call(UUID.randomUUID().toString(), conv, peer, name, video, caller = false, offer = offer)
            current = call
            PcLinkHub.broadcastFrame(LinkFrame.CallRing(call.id, conv, name, video,
                pl.sinusoide.sinuschat.util.SoundPrefs.ring(ctx).key, pl.sinusoide.sinuschat.util.SoundPrefs.ringVolume(ctx)))
        }
    }

    fun onRingStopped(conv: String, reason: String) {
        exec.execute {
            val call = current ?: return@execute
            if (call.conv != conv || call.accepted) return@execute
            current = null
            PcLinkHub.broadcastFrame(LinkFrame.CallEnd(call.id, reason))
        }
    }

    fun onPcGone(conn: LinkConnection) {
        exec.execute {
            val call = current ?: return@execute
            if (!call.accepted && PcLinkServer.connectedCount() == 0) current = null
            if (call.accepted && call.conn === conn) Log.w(TAG, "Komputer rozłączony w trakcie rozmowy")
        }
    }

    fun onFrame(ctx: Context, conn: LinkConnection, frame: LinkFrame) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                when (frame) {
                    is LinkFrame.CallStart -> start(app, conn, frame)
                    is LinkFrame.CallAccept -> accept(app, conn, frame.call)
                    is LinkFrame.CallSignal -> signal(app, frame)
                    is LinkFrame.CallEnd -> endFromPc(app, frame)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "${frame::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun start(ctx: Context, conn: LinkConnection, f: LinkFrame.CallStart) {
        fun fail(reason: String) { conn.send(LinkFrame.CallEnd(f.call, reason)) }
        if (GroupRepository.isGroup(f.conv)) return fail(LinkFrame.CALL_GROUP)
        if (current?.accepted == true || CallActivity.activeConversationId != null ||
            GroupCallSession.current?.ended == false) return fail(LinkFrame.CALL_BUSY)
        val peer = PcLinkHub.peerOf(ctx, f.conv) ?: return fail(LinkFrame.CALL_FAILED)
        if (BlockList.isCutOff(ctx, peer)) return fail(LinkFrame.CALL_FAILED)
        val name = ContactCache.getName(ctx, peer).orEmpty()
        val call = Call(f.call, f.conv, peer, name, f.video, caller = true, offer = null)
        call.conn = conn
        call.accepted = true
        current = call
        prepare(ctx, call)
    }

    private fun accept(ctx: Context, conn: LinkConnection, id: String) {
        val call = current?.takeIf { it.id == id && !it.caller }
        if (call == null) { conn.send(LinkFrame.CallEnd(id, LinkFrame.CALL_PEER_HUNG_UP)); return }
        if (call.accepted || CallActivity.activeConversationId == call.conv) {
            conn.send(LinkFrame.CallEnd(id, LinkFrame.CALL_ON_PHONE)); return
        }
        call.accepted = true
        call.conn = conn
        call.answeredAt = System.currentTimeMillis()
        PcLinkHub.broadcastFrame(LinkFrame.CallEnd(id, LinkFrame.CALL_ON_PHONE), except = conn)
        AppExecutor.main {
            CallNotifier.answered(call.conv)
            CallNotifier.cancel(ctx)
            ctx.sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_CANCELLED).setPackage(ctx.packageName)
                .putExtra("conversationId", call.conv))
        }
        prepare(ctx, call)
    }

    private fun prepare(ctx: Context, call: Call) {
        if (!WebSocketManager.isConnected()) WebSocketManager.connect(ctx)
        val turn = try { CallPrep.turn(ctx) } catch (_: Exception) { null }
        if (turn == null) {
            finish(ctx, call, LinkFrame.CALL_FAILED, notifyPc = true, sendEnd = !call.caller)
            return
        }
        call.channel = try { CallPrep.channel(ctx, call.peer) } catch (_: Exception) { null }
        call.conn?.send(LinkFrame.CallSetup(
            call.id, call.conv, call.name, call.video, call.caller,
            iceServers(turn), PrivacySettings.isCallRelayEnabled(ctx), call.offer,
            pl.sinusoide.sinuschat.util.SoundPrefs.outgoing(ctx) == pl.sinusoide.sinuschat.util.SoundPrefs.Outgoing.TONE
        ))
        val handler: (JSONObject) -> Unit = { msg -> exec.execute { onServer(ctx, call, msg) } }
        call.handler = handler
        CallActivity.activeConversationId = call.conv
        WebSocketManager.onCallEvent = handler
    }

    private fun iceServers(creds: JSONObject): List<IceServer> {
        val arr = creds.optJSONArray("urls") ?: return emptyList()
        val user = creds.optString("username")
        val pass = creds.optString("credential")
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotEmpty() } }.map { raw ->
            val url = if (raw.startsWith("turns:")) raw.substringBefore("?") else raw
            if (url.startsWith("turn") && user.isNotEmpty()) IceServer(listOf(url), user, pass) else IceServer(listOf(url))
        }
    }

    private fun signal(ctx: Context, f: LinkFrame.CallSignal) {
        val call = current?.takeIf { it.id == f.call && it.accepted } ?: return
        if (f.type !in FROM_PC) return
        val extra = try { JSONObject(f.body) } catch (_: Exception) { return }
        val ch = call.channel
        val body = when {
            ch != null -> CallCrypto.seal(ctx, ch, call.conv, f.type, extra)
            f.type in CallCrypto.SEALED && CallCrypto.isPinned(ctx, call.peer) -> null
            else -> extra
        }
        if (body == null) {
            finish(ctx, call, LinkFrame.CALL_FAILED, notifyPc = true, sendEnd = true)
            return
        }
        WebSocketManager.send(JSONObject(body.toString()).put("type", f.type).put("conversationId", call.conv))
    }

    private fun onServer(ctx: Context, call: Call, msg: JSONObject) {
        if (current !== call) return
        val conv = msg.optString("conversationId")
        if (conv.isNotEmpty() && conv != call.conv) return
        when (val type = msg.optString("type")) {
            "call_end" -> finish(ctx, call, LinkFrame.CALL_PEER_HUNG_UP, notifyPc = true, sendEnd = false)
            "call_reject" -> finish(ctx, call, when (msg.optString("reason")) {
                "timeout" -> LinkFrame.CALL_NO_ANSWER
                "busy" -> LinkFrame.CALL_BUSY
                else -> LinkFrame.CALL_PEER_REJECTED
            }, notifyPc = true, sendEnd = false)
            else -> {
                if (type == "call_answer" && call.answeredAt == 0L) call.answeredAt = System.currentTimeMillis()
                call.conn?.send(LinkFrame.CallSignal(call.id, type, msg.toString()))
            }
        }
    }

    private fun endFromPc(ctx: Context, f: LinkFrame.CallEnd) {
        val call = current?.takeIf { it.id == f.call } ?: return
        if (!call.accepted) {
            if (f.reason != LinkFrame.CALL_DECLINED) return
            current = null
            PcLinkHub.broadcastFrame(LinkFrame.CallEnd(call.id, LinkFrame.CALL_DECLINED))
            WebSocketManager.send(JSONObject().put("type", "call_reject").put("conversationId", call.conv).put("reason", "declined"))
            AppExecutor.main {
                CallNotifier.cancel(ctx)
                CallNotifier.showMissedCall(ctx, call.conv, call.peer, call.name, call.video, declined = true)
                ctx.sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_CANCELLED).setPackage(ctx.packageName)
                    .putExtra("conversationId", call.conv))
            }
            return
        }
        finish(ctx, call, f.reason, notifyPc = false, sendEnd = true, seconds = f.seconds, connected = f.connected)
    }

    private fun finish(
        ctx: Context,
        call: Call,
        reason: String,
        notifyPc: Boolean,
        sendEnd: Boolean,
        seconds: Int = -1,
        connected: Boolean = call.answeredAt > 0L
    ) {
        if (current !== call) return
        current = null
        if (sendEnd) WebSocketManager.send(JSONObject().put("type", "call_end").put("conversationId", call.conv))
        if (notifyPc) call.conn?.send(LinkFrame.CallEnd(call.id, reason))
        val handler = call.handler
        AppExecutor.main {
            if (handler != null && WebSocketManager.onCallEvent === handler) WebSocketManager.onCallEvent = null
            if (CallActivity.activeConversationId == call.conv) CallActivity.activeConversationId = null
        }
        val secs = if (seconds >= 0) seconds
            else if (call.answeredAt > 0L) ((System.currentTimeMillis() - call.answeredAt) / 1000L).toInt() else 0
        log(ctx, call, secs, connected)
    }

    private fun log(ctx: Context, call: Call, seconds: Int, connected: Boolean) {
        val direction = when {
            call.caller -> CallLogRepository.OUTGOING
            connected -> CallLogRepository.INCOMING
            else -> CallLogRepository.MISSED
        }
        CallLogRepository.log(ctx, call.peer, call.name, call.conv, call.video, direction,
            if (connected) seconds else 0, call.startedAt)
        if (!connected) return
        val res = LocaleManager.wrap(ctx)
        val label = res.getString(if (call.video) R.string.call_label_video else R.string.call_label_voice) +
            " · " + String.format("%d:%02d", seconds / 60, seconds % 60)
        AppExecutor.io { MessageRepository.insertSystemMessage(ctx, call.conv, label, "call_done") }
    }
}
