package pl.sinusoide.sinuschat.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.SecurePrefs
import pl.sinusoide.sinuschat.data.SinusChatDatabase

object LocalChats {

    private const val PREFS = "local_chats"
    private const val MARKS_READY = "__marks_ready"
    private const val GROUP_MARKS_READY = "__gmarks_ready"

    private fun isGroup(convId: String) = convId.startsWith("g_")

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    fun isDirect(convId: String) = convId.contains('_') && !convId.startsWith("g_") && !convId.startsWith("group:")

    fun add(ctx: Context, convId: String, otherHash: String) {
        if (!isDirect(convId) || otherHash.isEmpty()) return
        try {
            val p = prefs(ctx)
            if (p.contains("c_$convId")) return
            p.edit().putString("c_$convId",
                JSONObject().put("o", otherHash).put("t", System.currentTimeMillis()).toString()).apply()
        } catch (_: Exception) {}
    }

    fun remove(ctx: Context, convId: String) {
        try { prefs(ctx).edit().remove("c_$convId").remove("r_$convId").apply() } catch (_: Exception) {}
    }

    fun setReadMark(ctx: Context, convId: String, ts: Long) {
        if ((!isDirect(convId) && !isGroup(convId)) || ts <= 0) return
        try {
            val p = prefs(ctx)
            if (ts > p.getLong("r_$convId", 0L)) p.edit().putLong("r_$convId", ts).apply()
        } catch (_: Exception) {}
    }

    fun readMark(ctx: Context, convId: String): Long =
        try { prefs(ctx).getLong("r_$convId", 0L) } catch (_: Exception) { 0L }

    private fun explicit(ctx: Context): Map<String, JSONObject> = try {
        prefs(ctx).all.filterKeys { it.startsWith("c_") }.mapNotNull { (k, v) ->
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
            k.removePrefix("c_") to o
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun otherOf(convId: String, me: String, known: Map<String, JSONObject>): String? {
        known[convId]?.optString("o")?.takeIf { it.isNotEmpty() }?.let { return it }
        val parts = convId.split('_')
        if (parts.size != 2 || me !in parts) return null
        return parts.firstOrNull { it != me }?.takeIf { it.isNotEmpty() }
    }

    fun contacts(ctx: Context, me: String): List<String> {
        val known = explicit(ctx)
        val ids = known.keys + (dbIds(ctx).filter { isDirect(it) })
        return ids.mapNotNull { otherOf(it, me, known) }.distinct()
    }

    private fun dbIds(ctx: Context): List<String> = try {
        if (pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(ctx) &&
            pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() == null) emptyList()
        else SinusChatDatabase.getInstance(ctx).messageDao().getConversationIds()
    } catch (_: Exception) { emptyList() }

    fun merge(ctx: Context, me: String, server: JSONArray): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        val dao = try { SinusChatDatabase.getInstance(ctx).messageDao() } catch (_: Exception) { null }
        initGroupReadMarks(ctx, me, server)
        for (i in 0 until server.length()) {
            val o = server.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            if (isDirect(id)) o.optString("other_hash").takeIf { it.isNotEmpty() && it != "null" }?.let { add(ctx, id, it) }
            if (isGroup(id)) fillGroup(ctx, dao, me, o)
            seen.add(id)
            out.put(o)
        }
        initReadMarks(ctx)
        val known = explicit(ctx)
        val ids = LinkedHashSet<String>().apply { addAll(known.keys); addAll(dbIds(ctx).filter { isDirect(it) }) }
        val local = ArrayList<JSONObject>()
        for (id in ids) {
            if (id in seen) continue
            val other = otherOf(id, me, known) ?: continue
            val last = try { dao?.getLastMessage(id)?.timestampMs } catch (_: Exception) { null }
                ?: known[id]?.optLong("t", 0L) ?: 0L
            val unread = try { dao?.countIncomingSince(id, me, readMark(ctx, id)) ?: 0 } catch (_: Exception) { 0 }
            local.add(JSONObject()
                .put("id", id)
                .put("other_hash", other)
                .put("other_name", ContactCache.realName(ctx, other) ?: JSONObject.NULL)
                .put("avatar_url", ContactCache.getAvatarUrl(ctx, other) ?: JSONObject.NULL)
                .put("e2e_public_key", PeerKeyCache.getKey(ctx, other) ?: JSONObject.NULL)
                .put("is_group", false)
                .put("last_message_at", last)
                .put("unread_count", unread)
                .put("is_online", false)
                .put("last_heartbeat_ms", 0)
                .put("last_seen_ms", 0))
        }
        fillPresence(ctx, local)
        fillMissingNames(ctx, local)
        val all = (0 until out.length()).map { out.getJSONObject(it) } + local
        val sorted = all.sortedByDescending { it.optLong("last_message_at", 0L) }
        return JSONArray().apply { sorted.forEach { put(it) } }
    }

    private fun initReadMarks(ctx: Context) {
        try {
            val p = prefs(ctx)
            if (p.getBoolean(MARKS_READY, false)) return
            val dao = SinusChatDatabase.getInstance(ctx).messageDao()
            val e = p.edit()
            for (id in dbIds(ctx).filter { isDirect(it) }) {
                dao.getLastMessage(id)?.timestampMs?.let { e.putLong("r_$id", it) }
            }
            e.putBoolean(MARKS_READY, true).apply()
        } catch (_: Exception) {}
    }

    private fun fillGroup(ctx: Context, dao: pl.sinusoide.sinuschat.data.MessageDao?, me: String, o: JSONObject) {
        val id = o.optString("id")
        val p = try { prefs(ctx) } catch (_: Exception) { return }
        val firstSeen = p.getLong("gs_$id", 0L).takeIf { it > 0 }
            ?: System.currentTimeMillis().also { t -> try { p.edit().putLong("gs_$id", t).apply() } catch (_: Exception) {} }
        val last = try { dao?.getLastMessage(id)?.timestampMs } catch (_: Exception) { null } ?: 0L
        o.put("last_message_at", maxOf(o.optLong("last_message_at", 0L), last, firstSeen))
        val unread = try { dao?.countIncomingSince(id, me, readMark(ctx, id)) ?: 0 } catch (_: Exception) { 0 }
        o.put("unread_count", unread)
    }

    private fun initGroupReadMarks(ctx: Context, me: String, server: JSONArray) {
        try {
            val p = prefs(ctx)
            if (p.getBoolean(GROUP_MARKS_READY, false)) return
            val dao = SinusChatDatabase.getInstance(ctx).messageDao()
            val e = p.edit()
            for (i in 0 until server.length()) {
                val id = server.optJSONObject(i)?.optString("id") ?: continue
                if (!isGroup(id)) continue
                val unread = server.optJSONObject(i)?.optInt("unread_count", 0) ?: 0
                val mark = if (unread > 0) {
                    val msgs = try { dao.getLastIncoming(id, me, unread + 1) } catch (_: Exception) { emptyList() }
                    if (msgs.size > unread) msgs[unread].timestampMs else 0L
                } else dao.getLastMessage(id)?.timestampMs ?: 0L
                if (mark > 0) e.putLong("r_$id", mark)
            }
            e.putBoolean(GROUP_MARKS_READY, true).apply()
        } catch (_: Exception) {}
    }

    private fun fillPresence(ctx: Context, rows: List<JSONObject>) {
        if (rows.isEmpty()) return
        val hashes = rows.take(200).map { it.optString("other_hash") }
        val resp = try {
            pl.sinusoide.sinuschat.network.ApiClient.post("/api/presence",
                pl.sinusoide.sinuschat.network.ApiClient.presenceBody(ctx, hashes), ctx)
        } catch (_: Exception) { null } ?: return
        for (r in rows) {
            val p = resp.optJSONObject(r.optString("other_hash")) ?: continue
            r.put("is_online", p.optBoolean("online", false))
            r.put("last_seen_ms", p.optLong("seen", 0L))
        }
    }

    private fun fillMissingNames(ctx: Context, rows: List<JSONObject>) {
        for (r in rows.filter { it.isNull("other_name") }.take(10)) {
            val hash = r.optString("other_hash")
            val user = try { pl.sinusoide.sinuschat.network.ApiClient.getObject("/api/user/$hash", ctx) } catch (_: Exception) { null } ?: continue
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(ctx, hash, user.optString("display_name"))?.let {
                ContactCache.saveName(ctx, hash, it)
                r.put("other_name", it)
            }
            user.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }?.let {
                ContactCache.saveAvatarUrl(ctx, hash, it)
                ContactCache.getAvatarUrl(ctx, hash)?.let { shown -> r.put("avatar_url", shown) }
            }
        }
    }

    fun rename(ctx: Context, oldId: String, newId: String, newOther: String) {
        try {
            val p = prefs(ctx)
            val e = p.edit()
            val known = p.getString("c_$oldId", null)
            if (known != null) {
                val o = runCatching { JSONObject(known) }.getOrNull() ?: JSONObject().put("t", System.currentTimeMillis())
                e.remove("c_$oldId").putString("c_$newId", o.put("o", newOther).toString())
            }
            val mark = p.getLong("r_$oldId", -1L)
            if (mark >= 0) e.remove("r_$oldId").putLong("r_$newId", mark)
            e.apply()
        } catch (_: Exception) {}
    }
}
