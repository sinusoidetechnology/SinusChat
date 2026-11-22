package pl.sinusoide.sinuschat.call

import android.content.Context
import org.json.JSONObject
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object GroupCallHub {

    const val KIND_AUDIO = "audio"
    const val KIND_VIDEO = "video"
    const val RING_WINDOW_MS = 45_000L

    data class State(
        val groupId: String,
        val kind: String,
        val startedBy: String,
        val startedAt: Long,
        val members: List<String>
    ) {
        val isVideo get() = kind == KIND_VIDEO
    }

    private val states = ConcurrentHashMap<String, State>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rung = java.util.Collections.synchronizedSet(HashSet<String>())
    private val logged = java.util.Collections.synchronizedSet(HashSet<String>())
    private val buffered = ConcurrentHashMap<String, MutableList<Pair<Long, JSONObject>>>()

    fun state(groupId: String): State? = states[groupId]

    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    private fun notifyListeners(groupId: String) {
        AppExecutor.main { listeners.forEach { it(groupId) } }
    }

    fun send(type: String, groupId: String, extra: JSONObject? = null): Boolean {
        val msg = JSONObject(extra?.toString() ?: "{}").put("type", type).put("conversationId", groupId)
        return WebSocketManager.send(msg)
    }

    fun query(context: Context, groupId: String) {
        if (!groupId.startsWith("g_")) return
        if (!WebSocketManager.isConnected()) WebSocketManager.connect(context.applicationContext)
        send("gcall_query", groupId)
    }

    fun takeBuffered(groupId: String): List<JSONObject> {
        val now = System.currentTimeMillis()
        return buffered.remove(groupId).orEmpty().filter { now - it.first < 20_000L }.map { it.second }
    }

    fun onServerMessage(context: Context, msg: JSONObject) {
        val groupId = msg.optString("conversationId")
        if (!groupId.startsWith("g_")) return
        when (msg.optString("type")) {
            "gcall_state" -> onState(context.applicationContext, groupId, msg)
            "gcall_signal" -> {
                if (pl.sinusoide.sinuschat.pclink.PcLinkGroupCalls.onServerSignal(groupId, msg.optString("from"), msg.optString("payload"))) return
                val session = GroupCallSession.current
                if (session != null && session.groupId == groupId) {
                    AppExecutor.main { session.onSignal(msg.optString("from"), msg.optString("payload")) }
                } else {
                    val list = buffered.getOrPut(groupId) { java.util.Collections.synchronizedList(mutableListOf()) }
                    if (list.size < 200) list.add(System.currentTimeMillis() to msg)
                }
            }
            "gcall_error" -> {
                if (pl.sinusoide.sinuschat.pclink.PcLinkGroupCalls.onServerError(groupId, msg.optString("reason"))) return
                val session = GroupCallSession.current
                if (session != null && session.groupId == groupId) AppExecutor.main { session.onError(msg.optString("reason")) }
            }
        }
    }

    private fun onState(context: Context, groupId: String, msg: JSONObject) {
        val me = SetupActivity.getUserPhoneHash(context)
        val active = msg.optBoolean("active", false)
        val arr = msg.optJSONArray("members")
        val members = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s.isNotEmpty() } }
        val startedAt = msg.optLong("startedAt", 0L)
        val serverNow = msg.optLong("now", startedAt)
        val state = State(groupId, msg.optString("kind", KIND_AUDIO), msg.optString("startedBy"), startedAt, members)
        val previous = states[groupId]
        if (active) states[groupId] = state else states.remove(groupId)

        val session = GroupCallSession.current
        if (session != null && session.groupId == groupId) AppExecutor.main { session.onState(if (active) state else null) }

        val ringing = GroupCallRing.isRinging(groupId)
        var ringFor = 0L
        if (!active || me in members) {
            if (ringing) GroupCallRing.stop(context, groupId)
        } else {
            val key = "$groupId@$startedAt"
            val fresh = serverNow - startedAt < RING_WINDOW_MS
            if (fresh && state.startedBy != me && key !in rung && session == null &&
                !pl.sinusoide.sinuschat.util.BlockList.isBlocked(context, state.startedBy)) {
                rung.add(key)
                ringFor = RING_WINDOW_MS - (serverNow - startedAt)
                GroupCallRing.start(context, state, ringFor)
            }
        }
        if (!active && previous != null && logged.add("$groupId@${previous.startedAt}")) {
            val label = context.getString(pl.sinusoide.sinuschat.R.string.gcall_happened)
            AppExecutor.io {
                pl.sinusoide.sinuschat.messenger.MessageRepository.insertSystemMessage(context, groupId, label, "call_done")
            }
        }
        pl.sinusoide.sinuschat.pclink.PcLinkGroupCalls.onHubState(context, groupId, if (active) state else null, ringFor)
        if (!active && previous == null && !ringing) return
        notifyListeners(groupId)
    }
}
