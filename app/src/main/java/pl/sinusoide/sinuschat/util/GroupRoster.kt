package pl.sinusoide.sinuschat.util

import android.content.Context
import org.json.JSONArray
import pl.sinusoide.sinuschat.crypto.GroupKeyManager
import pl.sinusoide.sinuschat.crypto.SecurePrefs

object GroupRoster {

    private const val PREFS = "group_roster"

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    private fun known(ctx: Context, groupId: String): MutableSet<String>? = try {
        prefs(ctx).getString(groupId, null)?.let { raw ->
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toMutableSet()
        }
    } catch (_: Exception) { null }

    private fun store(ctx: Context, groupId: String, members: Set<String>) {
        try { prefs(ctx).edit().putString(groupId, JSONArray(members.toList()).toString()).apply() } catch (_: Exception) {}
    }

    @Synchronized
    fun reconcile(ctx: Context, groupId: String, members: List<String>) {
        if (groupId.isEmpty() || members.isEmpty()) return
        val now = members.filter { it.isNotEmpty() }.toSet()
        val before = known(ctx, groupId)
        if (before == null) {
            store(ctx, groupId, now)
            return
        }
        val added = now - before
        val removed = before - now
        if (added.isEmpty() && removed.isEmpty()) return
        for (h in added) GroupSystemMessages.memberJoined(ctx, groupId, h)
        for (h in removed) GroupSystemMessages.memberLeft(ctx, groupId, h)
        if (removed.isNotEmpty()) GroupKeyManager.markRotationNeeded(ctx, groupId)
        store(ctx, groupId, now)
    }

    @Synchronized
    fun onJoined(ctx: Context, groupId: String, hash: String) {
        val s = known(ctx, groupId) ?: return
        if (s.add(hash)) store(ctx, groupId, s)
    }

    @Synchronized
    fun onLeft(ctx: Context, groupId: String, hash: String) {
        val s = known(ctx, groupId) ?: return
        if (s.remove(hash)) store(ctx, groupId, s)
    }

    @Synchronized
    fun renameMember(ctx: Context, oldHash: String, newHash: String) {
        if (oldHash.isEmpty() || newHash.isEmpty() || oldHash == newHash) return
        try {
            for (groupId in prefs(ctx).all.keys) {
                val s = known(ctx, groupId) ?: continue
                if (s.remove(oldHash)) {
                    s.add(newHash)
                    store(ctx, groupId, s)
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun applyMove(ctx: Context, groupId: String, oldHash: String, newHash: String): Boolean {
        if (oldHash.isEmpty() || newHash.isEmpty() || oldHash == newHash) return false
        val s = known(ctx, groupId) ?: return false
        if (!s.remove(oldHash)) return false
        s.add(newHash)
        store(ctx, groupId, s)
        if (ContactCache.getName(ctx, newHash).isNullOrBlank()) {
            ContactCache.realName(ctx, oldHash)?.takeIf { it.isNotBlank() }?.let { ContactCache.saveName(ctx, newHash, it) }
        }
        if (newHash != pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx)) {
            GroupSystemMessages.emailChanged(ctx, groupId, newHash)
        }
        return true
    }

    fun members(ctx: Context, groupId: String): Set<String>? = known(ctx, groupId)

    fun allMembers(ctx: Context): Set<String> = try {
        prefs(ctx).all.keys.flatMap { known(ctx, it).orEmpty() }.toSet()
    } catch (_: Exception) { emptySet() }

    fun groupsOf(ctx: Context, hash: String): List<String> = try {
        prefs(ctx).all.filter { (_, v) -> (v as? String)?.contains("\"$hash\"") == true }.keys.toList()
    } catch (_: Exception) { emptyList() }

    fun forget(ctx: Context, groupId: String) {
        try { prefs(ctx).edit().remove(groupId).apply() } catch (_: Exception) {}
    }
}
