package pl.sinusoide.sinuschat.util

import android.content.Context
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.SecurePrefs

object GroupNotices {

    const val JOIN = "noticeJoin"
    const val LEAVE = "noticeLeave"
    const val EMAIL = "noticeEmail"
    const val KEY = "noticeKey"
    val ALL = listOf(JOIN, LEAVE, EMAIL, KEY)

    private const val PREFS = "group_notices"

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    fun save(ctx: Context, groupId: String, perms: JSONObject?) {
        if (groupId.isEmpty() || perms == null) return
        try {
            val e = prefs(ctx).edit()
            for (k in ALL) if (perms.has(k)) e.putBoolean("${groupId}|$k", perms.optBoolean(k, true))
            e.apply()
        } catch (_: Exception) {}
    }

    fun allows(ctx: Context, groupId: String, key: String): Boolean =
        try { prefs(ctx).getBoolean("${groupId}|$key", true) } catch (_: Exception) { true }

    fun forget(ctx: Context, groupId: String) {
        try {
            val e = prefs(ctx).edit()
            for (k in ALL) e.remove("${groupId}|$k")
            e.apply()
        } catch (_: Exception) {}
    }
}
