package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.content.Intent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.sinusoide.sinuschat.AppConfig
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor

object GroupInviteManager {

    private val CODE_RE = Regex("^grp_[A-Za-z0-9_-]{20,40}$")

    fun isGroupInviteToken(token: String?): Boolean = token != null && CODE_RE.matches(token.substringBefore('#'))

    private fun split(token: String): Pair<String, ByteArray?> =
        token.substringBefore('#') to pl.sinusoide.sinuschat.crypto.GroupMeta.keyFromFragment(token.substringAfter('#', ""))

    data class Link(val code: String, val memberCount: Int, val fragment: String = "") {
        val url: String get() = AppConfig.ADD_LINK_BASE + code + fragment
    }

    data class Preview(
        val name: String,
        val description: String,
        val avatarColor: String,
        val avatarUrl: String?,
        val memberCount: Int,
        val alreadyMember: Boolean,
        val groupId: String?
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val code: String) : Result<Nothing>()
    }

    data class Joined(val groupId: String, val name: String, val avatarColor: String, val avatarUrl: String?)

    fun getLink(context: Context, groupId: String, reset: Boolean, onDone: (Link?) -> Unit) {
        val app = context.applicationContext
        AppExecutor.io {
            val (status, json) = post(app, "/api/groups/$groupId/invite", JSONObject().put("reset", reset))
            val link = if (status in 200..299 && json?.optBoolean("ok") == true)
                Link(json.optString("code"), json.optInt("memberCount"), pl.sinusoide.sinuschat.crypto.GroupMeta.linkFragment(app, groupId)) else null
            AppExecutor.main { onDone(link) }
        }
    }

    fun revoke(context: Context, groupId: String, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        AppExecutor.io {
            val (status, json) = post(app, "/api/groups/$groupId/invite/revoke", JSONObject())
            AppExecutor.main { onDone(status in 200..299 && json?.optBoolean("ok") == true) }
        }
    }

    fun preview(context: Context, token: String, onDone: (Result<Preview>) -> Unit) {
        if (!isGroupInviteToken(token)) { onDone(Result.Error("group_invite_invalid")); return }
        val (code, key) = split(token)
        val app = context.applicationContext
        AppExecutor.io {
            val (status, json) = get(app, "/api/group-invite/$code")
            val result: Result<Preview> = if (status in 200..299 && json?.optBoolean("ok") == true) {
                val groupId = json.optString("groupId").takeIf { it.startsWith("g_") }
                val rawAvatar = json.optString("avatarUrl").takeIf { it.isNotEmpty() && it != "null" }
                val avatar = if (pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(rawAvatar))
                    key?.let { pl.sinusoide.sinuschat.crypto.ProfileCrypto.fetchAvatar(app, groupId ?: "g_$code", rawAvatar!!, it) }
                else rawAvatar
                Result.Ok(Preview(
                    name = clean(pl.sinusoide.sinuschat.crypto.GroupMeta.openWith(key, json.optString("name"))).ifEmpty { app.getString(R.string.type_community) },
                    description = clean(pl.sinusoide.sinuschat.crypto.GroupMeta.openWith(key, json.optString("description")), 300),
                    avatarColor = json.optString("avatarColor", "#5288C1"),
                    avatarUrl = avatar,
                    memberCount = json.optInt("memberCount"),
                    alreadyMember = json.optBoolean("alreadyMember"),
                    groupId = groupId
                ))
            } else Result.Error(errorCode(status, json))
            AppExecutor.main { onDone(result) }
        }
    }

    fun join(context: Context, token: String, onDone: (Result<Joined>) -> Unit) {
        if (!isGroupInviteToken(token)) { onDone(Result.Error("group_invite_invalid")); return }
        val (code, key) = split(token)
        val app = context.applicationContext
        AppExecutor.io {
            val (status, json) = post(app, "/api/group-invite/join", JSONObject().put("code", code))
            val groupId = json?.optString("groupId").orEmpty()
            val result: Result<Joined> = if (status in 200..299 && json?.optBoolean("ok") == true && groupId.startsWith("g_")) {
                key?.let { pl.sinusoide.sinuschat.crypto.GroupMeta.receiveProfileKey(app, groupId, it) }
                val joined = Joined(
                    groupId = groupId,
                    name = clean(pl.sinusoide.sinuschat.crypto.GroupMeta.openWith(key, json.optString("name"))).ifEmpty { app.getString(R.string.type_community) },
                    avatarColor = json.optString("avatarColor", "#5288C1"),
                    avatarUrl = json.optString("avatarUrl").takeIf { it.isNotEmpty() && it != "null" }
                )
                pl.sinusoide.sinuschat.util.AvatarHelper.setContactColor(app, groupId, joined.avatarColor)
                pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(app, groupId, joined.avatarUrl)
                Result.Ok(joined)
            } else Result.Error(errorCode(status, json))
            AppExecutor.main {
                if (result is Result.Ok) {
                    app.sendBroadcast(Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(app.packageName))
                }
                onDone(result)
            }
        }
    }

    fun openGroup(context: Context, groupId: String, name: String) {
        context.startActivity(Intent(context, ChatActivity::class.java).apply {
            putExtra("conversationId", groupId)
            putExtra("otherUserName", name)
            putExtra("otherUserHash", "")
            putExtra("isGroup", true)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun errorMessage(context: Context, code: String): String = context.getString(when (code) {
        "group_full" -> R.string.group_join_full
        "network" -> R.string.error_connection
        else -> R.string.group_join_invalid
    })

    private fun clean(s: String?, max: Int = 80) =
        (s ?: "").filter { !it.isISOControl() || it == '\n' }.trim().take(max)

    private fun errorCode(status: Int, json: JSONObject?): String =
        json?.optString("code")?.takeIf { it.isNotEmpty() } ?: if (status <= 0) "network" else "group_invite_invalid"

    private fun post(context: Context, path: String, body: JSONObject): Pair<Int, JSONObject?> =
        call(context, okhttp3.Request.Builder().url(ApiClient.BASE_URL + path)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))

    private fun get(context: Context, path: String): Pair<Int, JSONObject?> =
        call(context, okhttp3.Request.Builder().url(ApiClient.BASE_URL + path).get())

    private fun call(context: Context, builder: okhttp3.Request.Builder): Pair<Int, JSONObject?> = try {
        val req = builder.header("Authorization", "Bearer ${ApiClient.getToken(context) ?: ""}").build()
        ApiClient.httpClient(context).newCall(req).execute().use { resp ->
            resp.code to resp.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    } catch (_: Exception) { -1 to null }
}
