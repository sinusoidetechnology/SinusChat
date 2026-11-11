package pl.sinusoide.sinuschat.messenger

import android.content.Context
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.GroupRoster
import java.util.concurrent.ConcurrentHashMap

object GroupGuard {

    private const val FRESH_MS = 5 * 60_000L
    private const val REFETCH_GAP_MS = 5_000L

    private class Info(val admins: Map<String, Boolean>, val sendMessages: Boolean, val at: Long)

    private val cache = ConcurrentHashMap<String, Info>()

    fun allows(ctx: Context, groupId: String, sender: String, kind: String): Boolean {
        if (sender.isEmpty() || kind !in GroupRepository.SEALED_GROUP_KINDS) return false
        val now = System.currentTimeMillis()
        var info = cache[groupId]?.takeIf { now - it.at < FRESH_MS } ?: fetch(ctx, groupId)
        if (info != null && sender !in info.admins && now - info.at >= REFETCH_GAP_MS) info = fetch(ctx, groupId) ?: info
        if (info == null) return GroupRoster.members(ctx, groupId)?.contains(sender) == true
        val admin = info.admins[sender] ?: return false
        return admin || info.sendMessages
    }

    fun forget(groupId: String) {
        cache.remove(groupId)
    }

    private fun fetch(ctx: Context, groupId: String): Info? {
        val resp = try { ApiClient.getObject("/api/groups/$groupId", ctx) } catch (_: Exception) { null } ?: return null
        val arr = resp.optJSONArray("members") ?: return null
        val admins = HashMap<String, Boolean>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val h = m.optString("user_hash")
            if (h.isNotEmpty()) admins[h] = m.optBoolean("is_admin", false)
        }
        if (admins.isEmpty()) return null
        GroupRoster.reconcile(ctx, groupId, admins.keys.toList())
        val info = Info(admins, GroupPerms.from(GroupRepository.permsOf(resp)).sendMessages, System.currentTimeMillis())
        cache[groupId] = info
        return info
    }
}
