package pl.sinusoide.sinuschat.util

import android.content.Context

object BlockList {

    private const val PREFS = "sinuschat_blocked"
    private fun key(hash: String) = "blocked_$hash"

    fun isBlocked(context: Context, hash: String?): Boolean {
        if (hash.isNullOrEmpty()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(hash), false)
    }

    fun block(context: Context, hash: String) {
        if (hash.isEmpty()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(key(hash), true).apply()
        pl.sinusoide.sinuschat.pclink.PcLinkHub.refreshChats()
        AppExecutor.io {
            syncServer(app)
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.rotate(app)
            prefs(app).edit().putBoolean(ROTATED, true).apply()
        }
    }

    fun unblock(context: Context, hash: String) {
        if (hash.isEmpty()) return
        val app = context.applicationContext
        prefs(app).edit().remove(key(hash)).apply()
        pl.sinusoide.sinuschat.pclink.PcLinkHub.refreshChats()
        AppExecutor.io { syncServer(app) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyServer(hash: String) = "srv_$hash"
    private const val ROTATED = "rotated_v1"

    @Synchronized
    fun syncServer(context: Context) {
        val app = context.applicationContext
        if (!pl.sinusoide.sinuschat.network.ApiClient.isAuthenticated(app)) return
        val p = prefs(app)
        val all = p.all
        val wanted = all.filter { (k, v) -> k.startsWith("blocked_") && !k.startsWith("blocked_by_") && v == true }
            .keys.map { it.removePrefix("blocked_") }.toSet()
        val onServer = all.keys.filter { it.startsWith("srv_") }.map { it.removePrefix("srv_") }.toSet()
        fun send(hash: String, block: Boolean) = pl.sinusoide.sinuschat.network.ApiClient.post("/api/user/block",
            org.json.JSONObject().put("blockedHash", hash).put("block", block), app) != null
        for (h in wanted - onServer) if (send(h, true)) p.edit().putBoolean(keyServer(h), true).apply()
        for (h in onServer - wanted) if (send(h, false)) p.edit().remove(keyServer(h)).apply()
        if (wanted.isNotEmpty() && !p.getBoolean(ROTATED, false)) {
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.rotate(app)
            p.edit().putBoolean(ROTATED, true).apply()
        }
    }

    private fun keyByThem(hash: String) = "blocked_by_$hash"
    private fun keyByThemAt(hash: String) = "blocked_by_at_$hash"

    private const val BLOCKED_BY_TTL_MS = 30 * 60 * 1000L

    fun markBlockedByThem(context: Context, hash: String?) {
        if (hash.isNullOrEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(keyByThem(hash), true)
            .putLong(keyByThemAt(hash), System.currentTimeMillis())
            .apply()
    }

    fun clearBlockedByThem(context: Context, hash: String?) {
        if (hash.isNullOrEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(keyByThem(hash)).remove(keyByThemAt(hash)).apply()
    }

    fun isBlockedByThem(context: Context, hash: String?): Boolean {
        if (hash.isNullOrEmpty()) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(keyByThem(hash), false)) return false

        val setAt = p.getLong(keyByThemAt(hash), 0L)
        if (setAt == 0L) { clearBlockedByThem(context, hash); return false }

        if (System.currentTimeMillis() - setAt > BLOCKED_BY_TTL_MS) {
            clearBlockedByThem(context, hash)
            return false
        }
        return true
    }

    private fun keyDeleted(hash: String) = "deleted_$hash"

    fun markDeleted(context: Context, hash: String?) {
        if (hash.isNullOrEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(keyDeleted(hash), true).apply()
    }

    fun isDeleted(context: Context, hash: String?): Boolean {
        if (hash.isNullOrEmpty()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(keyDeleted(hash), false)
    }

    fun clearDeleted(context: Context, hash: String?): Boolean {
        if (hash.isNullOrEmpty() || !isDeleted(context, hash)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(keyDeleted(hash)).apply()
        return true
    }

    fun isCutOff(context: Context, hash: String?): Boolean =
        isBlocked(context, hash) || isBlockedByThem(context, hash) || isDeleted(context, hash)
}
