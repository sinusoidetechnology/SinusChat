package pl.sinusoide.sinuschat.util

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.crypto.SecurePrefs
import pl.sinusoide.sinuschat.network.ApiClient

object GroupEnded {

    const val DELETED = "deleted"
    const val KICKED = "kicked"
    const val ACTION = "pl.sinusoide.sinuschat.GROUP_ENDED"
    private const val PREFS = "group_ended"
    private const val MAX_CHECKS = 5

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx.applicationContext, PREFS)

    private fun entry(ctx: Context, groupId: String): JSONObject? = try {
        prefs(ctx).getString("e_$groupId", null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    fun reason(ctx: Context, groupId: String): String? =
        if (!groupId.startsWith("g_")) null else entry(ctx, groupId)?.optString("r")?.takeIf { it.isNotEmpty() }

    fun bannerText(ctx: Context, reason: String): String =
        ctx.getString(if (reason == KICKED) R.string.group_ended_kicked else R.string.group_ended_deleted)

    fun mark(ctx: Context, groupId: String, reason: String) {
        val app = ctx.applicationContext
        if (!groupId.startsWith("g_") || reason(app, groupId) != null) return
        val cached = ConversationCache.load(app)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("id") == groupId }
        }
        val row = cached ?: JSONObject()
            .put("id", groupId)
            .put("is_group", true)
            .put("group_name", ContactCache.getName(app, groupId) ?: app.getString(R.string.group))
        try {
            prefs(app).edit().putString("e_$groupId", JSONObject()
                .put("r", reason)
                .put("t", System.currentTimeMillis())
                .put("row", row).toString()).apply()
        } catch (_: Exception) { return }
        GroupSystemMessages.insert(app, groupId,
            app.getString(if (reason == KICKED) R.string.group_ended_sys_kicked else R.string.group_ended_sys_deleted),
            idSuffix = "ended_$groupId")
        AppExecutor.main {
            app.sendBroadcast(Intent(ACTION).setPackage(app.packageName).putExtra("groupId", groupId))
            app.sendBroadcast(Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(app.packageName))
        }
    }

    fun clear(ctx: Context, groupId: String) {
        try { prefs(ctx).edit().remove("e_$groupId").apply() } catch (_: Exception) {}
    }

    fun rows(ctx: Context): List<JSONObject> = try {
        prefs(ctx).all.filterKeys { it.startsWith("e_") }.mapNotNull { (_, v) ->
            val e = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
            val row = e.optJSONObject("row") ?: return@mapNotNull null
            JSONObject(row.toString()).put("ended", e.optString("r"))
        }
    } catch (_: Exception) { emptyList() }

    fun verify(ctx: Context, groupId: String): String? {
        if (!groupId.startsWith("g_")) return null
        reason(ctx, groupId)?.let { return it }
        val (code, body) = ApiClient.getWithCode("/api/groups/$groupId", ctx) ?: return null
        val tag = runCatching { JSONObject(body).optString("code") }.getOrNull()
        val r = when {
            code == 404 && tag == "group_deleted" -> DELETED
            code == 403 && tag == "kicked" -> KICKED
            else -> null
        }
        if (r != null) mark(ctx, groupId, r)
        return r
    }

    fun checkMissing(ctx: Context, previous: Collection<String>, current: Set<String>) {
        previous.asSequence()
            .filter { it.startsWith("g_") && it !in current && reason(ctx, it) == null }
            .take(MAX_CHECKS)
            .forEach { verify(ctx, it) }
    }
}
