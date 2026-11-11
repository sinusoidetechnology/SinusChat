package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.GroupKeyManager
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.MediaManager

data class GroupInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val avatarColor: String = "#5288C1",
    val createdBy: String = "",
    val members: List<GroupMember> = emptyList(),
    val activeCount: Int = 0,
    val disappearingTtlMs: Long = 0L,
    val disappearingTrigger: String = DisappearingMessages.TRIGGER_READ,
    val amIAdmin: Boolean = false,
    val perms: GroupPerms = GroupPerms(),
    val avatarUrl: String? = null,
    val isCommunity: Boolean = false,
    val hasInviteLink: Boolean = false
)

data class GroupPerms(
    val editSettings: Boolean = true,
    val sendMessages: Boolean = true,
    val addMembers:   Boolean = true,
    val callGroup:    Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("editSettings", editSettings)
        .put("sendMessages", sendMessages)
        .put("addMembers",   addMembers)
        .put("callGroup",    callGroup)

    companion object {
        fun from(o: JSONObject?): GroupPerms {
            if (o == null) return GroupPerms()
            return GroupPerms(
                editSettings = o.optBoolean("editSettings", true),
                sendMessages = o.optBoolean("sendMessages", true),
                addMembers   = o.optBoolean("addMembers",   true),
                callGroup    = o.optBoolean("callGroup",    true)
            )
        }
    }
}

data class GroupMember(
    val userHash: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val avatarColor: String = "#5288C1",
    val isAdmin: Boolean = false
)

object GroupRepository {

    const val CAP_GROUP_SEALED = "group_sealed"
    val SEALED_GROUP_KINDS = setOf("msg", "group_attach")

    private const val TAG = "GroupRepository"

    fun createGroup(
        context: Context,
        name: String,
        description: String = "",
        memberHashes: List<String>,
        avatarColor: String = "#5288C1",
        avatarUrl: String? = null,
        kind: String? = null,
        avatarUri: android.net.Uri? = null,
        onPhotoFailed: (() -> Unit)? = null,
        onResult: (groupId: String?) -> Unit
    ) {
        AppExecutor.io {
            try {
                val profileKey = if (memberHashes.all { hasNewProtocol(context, it) })
                    pl.sinusoide.sinuschat.crypto.GroupMeta.newKey() else null
                val photoUrl = avatarUri?.let { uri ->
                    pl.sinusoide.sinuschat.util.FileUploader.uploadGroupAvatarBlocking(context, uri, profileKey) ?: run {
                        AppExecutor.main { onPhotoFailed?.invoke() ?: onResult(null) }
                        return@io
                    }
                } ?: avatarUrl
                val body = JSONObject().apply {
                    put("name", profileKey?.let { pl.sinusoide.sinuschat.crypto.GroupMeta.seal(it, name) } ?: name)
                    put("description", profileKey?.let {
                        if (description.isEmpty()) "" else pl.sinusoide.sinuschat.crypto.GroupMeta.seal(it, description)
                    } ?: description)
                    put("avatarColor", avatarColor)
                    if (photoUrl != null) put("avatarUrl", photoUrl)
                    if (kind != null) put("kind", kind)
                    put("members", JSONArray(memberHashes))
                    put("aks", JSONObject().apply {
                        for (h in memberHashes) accessKeyFor(context, h)?.let { put(h, it) }
                    })
                }
                val resp = ApiClient.post("/api/groups/create", body, context)
                val groupId = resp?.optString("groupId")?.takeIf { it.isNotEmpty() }
                Log.d(TAG, "Created group: $groupId")
                if (groupId != null && profileKey != null) {
                    pl.sinusoide.sinuschat.crypto.GroupMeta.saveProfileKey(context, groupId, profileKey)
                    val myHash = SetupActivity.getUserPhoneHash(context)
                    GroupKeyManager.distributeMyKey(context, groupId, myHash, memberHashes + myHash)
                }
                AppExecutor.main { onResult(groupId) }
            } catch (e: Exception) {
                Log.e(TAG, "createGroup error", e)
                AppExecutor.main { onResult(null) }
            }
        }
    }

    fun getGroupInfo(
        context: Context,
        groupId: String,
        onResult: (GroupInfo?) -> Unit
    ) {
        AppExecutor.io {
            try {
                val resp = ApiClient.getObject("/api/groups/$groupId", context)
                if (resp == null) { AppExecutor.main { onResult(null) }; return@io }

                val myHash = SetupActivity.getUserPhoneHash(context)
                val membersArr = resp.optJSONArray("members") ?: JSONArray()
                val realNames = HashMap<String, String>()
                val members = (0 until membersArr.length()).map { i ->
                    val m = membersArr.getJSONObject(i)
                    val memberHash = m.optString("user_hash")
                    val real = pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(context, memberHash, m.optStringOrNull("display_name"))
                        ?: pl.sinusoide.sinuschat.util.ContactCache.realName(context, memberHash) ?: ""
                    realNames[memberHash] = real
                    GroupMember(
                        userHash    = memberHash,
                        displayName = pl.sinusoide.sinuschat.util.ContactCache.display(context, memberHash, real) ?: real,
                        avatarUrl   = pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(context, m.optString("user_hash"), m.optStringOrNull("avatar_url")),
                        avatarColor = m.optStringOrNull("avatar_color") ?: "#5288C1",
                        isAdmin     = m.optBoolean("is_admin", false)
                    )
                }
                pl.sinusoide.sinuschat.util.GroupRoster.reconcile(context, groupId, members.map { it.userHash })
                members.forEach { m ->
                    val real = realNames[m.userHash].orEmpty()
                    if (real.isNotEmpty()) {
                        pl.sinusoide.sinuschat.util.ContactCache.saveName(context, m.userHash, real)
                    }
                    if (m.userHash != myHash) {
                        pl.sinusoide.sinuschat.util.AvatarHelper.setContactColor(context, m.userHash, m.avatarColor)
                    }
                }
                resp.optStringOrNull("avatar_color")?.let {
                    pl.sinusoide.sinuschat.util.AvatarHelper.setContactColor(context, groupId, it)
                }
                val info = GroupInfo(
                    id          = resp.optString("id"),
                    name        = pl.sinusoide.sinuschat.crypto.GroupMeta.open(context, groupId,
                        resp.optString("name"), context.getString(pl.sinusoide.sinuschat.R.string.group)),
                    description = pl.sinusoide.sinuschat.crypto.GroupMeta.open(context, groupId,
                        resp.optString("description"), ""),
                    avatarColor = resp.optStringOrNull("avatar_color") ?: "#5288C1",
                    createdBy   = resp.optString("created_by"),
                    members     = members,
                    activeCount = resp.optInt("activeCount", 0),
                    disappearingTtlMs = resp.optLong("disappearing_ttl_ms", 0L),
                    disappearingTrigger = resp.optString("disappearing_trigger", DisappearingMessages.TRIGGER_READ),
                    amIAdmin    = members.any { it.userHash == myHash && it.isAdmin },
                    perms       = GroupPerms.from(permsOf(resp)),
                    avatarUrl   = resp.optStringOrNull("avatar_url"),
                    isCommunity = resp.optBoolean("is_community", false),
                    hasInviteLink = resp.optBoolean("has_invite_link", false)
                )
                pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(context, groupId, info.avatarUrl)
                AppExecutor.main { onResult(info) }
            } catch (e: Exception) {
                Log.e(TAG, "getGroupInfo error", e)
                AppExecutor.main { onResult(null) }
            }
        }
    }

    fun permsOf(resp: JSONObject): JSONObject? {
        resp.optJSONObject("perms")?.let { return it }
        val raw = resp.optString("perms", "")
        if (raw.isEmpty() || raw == "null") return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    fun setPermissions(
        context: Context,
        groupId: String,
        perms: GroupPerms,
        onResult: (Boolean) -> Unit = {}
    ) {
        AppExecutor.io {
            val resp = ApiClient.post("/api/groups/$groupId/settings",
                JSONObject().put("perms", perms.toJson()), context)
            AppExecutor.main { onResult(resp != null) }
        }
    }

    private fun accessKeyFor(context: Context, hash: String): String? =
        pl.sinusoide.sinuschat.crypto.ProfileCrypto.contactKey(context, hash)?.let {
            android.util.Base64.encodeToString(pl.sinusoide.sinuschat.crypto.SealedSender.accessKey(it), android.util.Base64.NO_WRAP)
        }

    fun addMember(
        context: Context,
        groupId: String,
        userHash: String,
        onResult: (Boolean) -> Unit
    ) {
        AppExecutor.io {
            val resp = ApiClient.post(
                "/api/groups/$groupId/add",
                JSONObject().put("userHash", userHash).apply { accessKeyFor(context, userHash)?.let { put("ak", it) } },
                context
            )
            val ok = resp?.optBoolean("ok") == true
            if (ok) {
                try {
                    GroupKeyManager.distributeMyKey(context, groupId,
                        SetupActivity.getUserPhoneHash(context), listOf(userHash))
                } catch (e: Exception) { Log.w(TAG, "Klucze dla nowej osoby: ${e.message}") }
            }
            AppExecutor.main { onResult(ok) }
        }
    }

    fun kickMember(context: Context, groupId: String, userHash: String, onResult: (Boolean) -> Unit) {
        AppExecutor.io {
            val resp = ApiClient.post("/api/groups/$groupId/kick",
                JSONObject().put("userHash", userHash), context)
            AppExecutor.main { onResult(resp?.optBoolean("ok") == true) }
        }
    }

    fun setAdmin(context: Context, groupId: String, userHash: String, makeAdmin: Boolean, onResult: (Boolean) -> Unit) {
        AppExecutor.io {
            val resp = ApiClient.post("/api/groups/$groupId/admin",
                JSONObject().put("userHash", userHash).put("makeAdmin", makeAdmin), context)
            AppExecutor.main { onResult(resp?.optBoolean("ok") == true) }
        }
    }

    fun setDisappearing(context: Context, groupId: String, ttlMs: Long, trigger: String, onResult: (Boolean) -> Unit) {
        AppExecutor.io {
            val resp = ApiClient.post("/api/groups/$groupId/settings",
                JSONObject().apply {
                    put("disappearingTtlMs", ttlMs)
                    put("disappearingTrigger", trigger)
                }, context)
            val ok = resp?.optBoolean("ok") == true
            if (ok) {
                DisappearingMessages.setTtl(context, groupId, ttlMs, trigger)
            }
            AppExecutor.main { onResult(ok) }
        }
    }

    fun leaveGroup(
        context: Context,
        groupId: String,
        silent: Boolean = false,
        onResult: (Boolean) -> Unit
    ) {
        AppExecutor.io {
            val resp = ApiClient.post("/api/groups/$groupId/leave",
                JSONObject().put("silent", silent), context)
            val ok = resp?.optBoolean("ok") == true
            if (ok) {
                try {
                    SinusChatDatabase.getInstance(context)
                        .messageDao().clearConversation(groupId)
                } catch (_: Exception) {}
                try { MediaManager.clearConversationMedia(context, groupId) } catch (_: Exception) {}
                try { ConversationCache.remove(context, groupId) } catch (_: Exception) {}
                GroupKeyManager.clearGroup(context, groupId)
            }
            AppExecutor.main { onResult(ok) }
        }
    }

    fun updateGroup(
        context: Context,
        groupId: String,
        name: String? = null,
        description: String? = null,
        avatarUrl: String? = null,
        avatarColor: String? = null,
        onResult: (Boolean) -> Unit
    ) {
        AppExecutor.io {
            val body = JSONObject()
            if (name != null) body.put("name", pl.sinusoide.sinuschat.crypto.GroupMeta.sealIfKeyed(context, groupId, name))
            if (description != null) body.put("description",
                if (description.isEmpty()) "" else pl.sinusoide.sinuschat.crypto.GroupMeta.sealIfKeyed(context, groupId, description))
            if (avatarUrl != null) body.put("avatarUrl", avatarUrl)
            if (avatarColor != null) body.put("avatarColor", avatarColor)
            val resp = ApiClient.post("/api/groups/$groupId/update", body, context)
            AppExecutor.main { onResult(resp?.optBoolean("ok") == true) }
        }
    }

    fun isGroup(id: String) = id.startsWith("g_") || id.startsWith("group:")

    class SendAccess(val canWrite: Boolean, val memberHashes: List<String>, val allSignal: Boolean = false)

    fun sendAccessBlocking(context: Context, groupId: String): SendAccess? {
        val resp = ApiClient.getObject("/api/groups/$groupId", context) ?: return null
        val myHash = SetupActivity.getUserPhoneHash(context)
        val arr = resp.optJSONArray("members") ?: JSONArray()
        var admin = false
        var allSignal = true
        val hashes = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val h = m.optString("user_hash")
            if (h == myHash && m.optBoolean("is_admin", false)) admin = true
            if (h != myHash && !m.optBoolean("signal", false)) allSignal = false
            h.ifEmpty { null }
        }
        pl.sinusoide.sinuschat.util.GroupRoster.reconcile(context, groupId, hashes)
        return SendAccess(admin || GroupPerms.from(permsOf(resp)).sendMessages, hashes, allSignal)
    }

    fun hasNewProtocol(context: Context, userHash: String): Boolean =
        ApiClient.getObject("/api/user/$userHash", context)?.optBoolean("signal", false) == true

    fun sendTextBlocking(context: Context, groupId: String, myHash: String, text: String,
                         replyId: String? = null, replyText: String? = null, id: String? = null): Boolean {
        val msgId = id ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dao = SinusChatDatabase.getInstance(context).messageDao()
        dao.insert(pl.sinusoide.sinuschat.data.MessageEntity(
            id = msgId, conversationId = groupId, senderHash = myHash, text = text, timestampMs = now,
            status = pl.sinusoide.sinuschat.data.MessageEntity.STATUS_PENDING,
            type = pl.sinusoide.sinuschat.data.MessageEntity.TYPE_TEXT,
            replyToId = replyId, replyToText = replyText))
        val payload = JSONObject().put("senderHash", myHash).put("timestamp", now)
        val replyJson = replyId?.let { JSONObject().put("id", it).put("text", replyText.orEmpty()).toString() }
        val sealed = GroupKeyManager.sealForGroup(context, groupId, text, "enc", replyJson)
        val resp = if (sealed != null) postGroupEvent(context, groupId, msgId, "msg", merge(payload, sealed)) else null
        val ok = resp != null
        serverTime(resp)?.let { dao.updateTimestamp(msgId, it) }
        dao.updateStatus(msgId,
            if (ok) pl.sinusoide.sinuschat.data.MessageEntity.STATUS_SENT
            else pl.sinusoide.sinuschat.data.MessageEntity.STATUS_FAILED)
        return ok
    }

    fun resendPendingTextBlocking(context: Context, msg: pl.sinusoide.sinuschat.data.MessageEntity): Boolean {
        if (!MessageRepository.claim(msg.id)) return true
        try {
            val dao = SinusChatDatabase.getInstance(context).messageDao()
            val replyJson = sideJson(msg.replyToId, msg.replyToText, MessageRepository.wireTranslation(msg))
            val sealed = GroupKeyManager.sealForGroup(context, msg.conversationId, msg.text, "enc", replyJson) ?: return false
            val payload = JSONObject().put("senderHash", msg.senderHash).put("timestamp", msg.timestampMs)
            val resp = postGroupEvent(context, msg.conversationId, msg.id, "msg", merge(payload, sealed)) ?: return false
            serverTime(resp)?.let { dao.updateTimestamp(msg.id, it) }
            dao.updateStatus(msg.id, pl.sinusoide.sinuschat.data.MessageEntity.STATUS_SENT)
            return true
        } finally {
            MessageRepository.release(msg.id)
        }
    }

    fun sendAttachmentBlocking(
        context: Context, groupId: String, myHash: String,
        messageId: String, fileId: String, fileKeyPlain: String, fileName: String
    ): Boolean {
        val sentAt = System.currentTimeMillis()
        val meta = JSONObject().put("fileId", fileId).put("fileKey", fileKeyPlain).put("fileName", fileName)
        pl.sinusoide.sinuschat.util.VideoThumbs.ensure(context, groupId, messageId, fileName)
            ?.let { pl.sinusoide.sinuschat.util.VideoThumbs.putInto(meta, it) }
        val sealed = GroupKeyManager.sealForGroup(context, groupId, meta.toString(), "encAttach") ?: return false
        val payload = merge(JSONObject().put("senderHash", myHash).put("timestamp", sentAt), sealed)
        val resp = postGroupEvent(context, groupId, messageId, "group_attach", payload)
        val ok = resp != null
        SinusChatDatabase.getInstance(context).messageDao().insert(pl.sinusoide.sinuschat.data.MessageEntity(
            id = messageId, conversationId = groupId, senderHash = myHash, text = "", timestampMs = serverTime(resp) ?: sentAt,
            status = if (ok) pl.sinusoide.sinuschat.data.MessageEntity.STATUS_SENT
                     else pl.sinusoide.sinuschat.data.MessageEntity.STATUS_FAILED,
            type = pl.sinusoide.sinuschat.data.MessageEntity.TYPE_ATTACHMENT,
            fileId = fileId, fileKey = fileKeyPlain, attachmentName = fileName))
        return ok
    }

    fun sendCaptionCompanionBlocking(context: Context, msg: pl.sinusoide.sinuschat.data.MessageEntity) {
        val side = sideJson(msg.replyToId, msg.replyToText, null, capOf = msg.id)
        val sealed = GroupKeyManager.sealForGroup(context, msg.conversationId, msg.text, "enc", side) ?: return
        val payload = JSONObject().put("senderHash", msg.senderHash).put("timestamp", msg.timestampMs)
        postGroupEvent(context, msg.conversationId, msg.id + MessageRepository.CAPTION_SUFFIX, "msg", merge(payload, sealed))
    }

    fun sideJson(replyId: String?, replyText: String?, translation: JSONObject?, capOf: String? = null): String? {
        if (replyId == null && translation == null && capOf == null) return null
        val o = JSONObject()
        replyId?.let { o.put("id", it).put("text", replyText.orEmpty()) }
        translation?.let { o.put("tr", it) }
        capOf?.let { o.put("capOf", it) }
        return o.toString()
    }

    fun merge(into: JSONObject, from: JSONObject): JSONObject {
        for (k in from.keys()) into.put(k, from.get(k))
        return into
    }

    fun serverTime(resp: JSONObject?): Long? = resp?.optLong("createdAt", 0L)?.takeIf { it > 0L }

    fun postGroupEvent(context: Context, groupId: String, messageId: String, kind: String, payload: JSONObject): JSONObject? {
        val payloadB64 = android.util.Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        if (kind in SEALED_GROUP_KINDS && pl.sinusoide.sinuschat.network.WebSocketManager.serverHas(CAP_GROUP_SEALED)) {
            sendSealedGroup(context, groupId, messageId, kind, payloadB64)?.let { return it.takeIf { r -> r.optBoolean("ok") } }
        }
        val body = JSONObject()
            .put("conversationId", groupId)
            .put("messageId", messageId)
            .put("kind", kind)
            .put("payloadB64", payloadB64)
        return ApiClient.post("/api/message", body, context)
    }

    private fun sendSealedGroup(context: Context, groupId: String, messageId: String, kind: String, payloadB64: String): JSONObject? {
        val me = SetupActivity.getUserPhoneHash(context)
        val members = pl.sinusoide.sinuschat.util.GroupRoster.members(context, groupId)
            ?: run { sendAccessBlocking(context, groupId); pl.sinusoide.sinuschat.util.GroupRoster.members(context, groupId) }
            ?: return null
        if (me !in members || members.size !in 2..pl.sinusoide.sinuschat.util.GroupReceipts.MAX_MEMBERS) return null
        val others = members - me
        if (others.none { pl.sinusoide.sinuschat.crypto.SealedSender.canSeal(context, it) }) return null
        val plain = ArrayList<String>()
        var createdAt = 0L
        for (m in others) {
            val r = pl.sinusoide.sinuschat.crypto.SealedSender.postToGroupMember(
                context, groupId, m, messageId + "_" + m.take(16), kind, payloadB64)
            when {
                r.sent -> createdAt = maxOf(createdAt, r.createdAt)
                r.noAccess -> plain += m
                else -> return JSONObject().put("ok", false)
            }
        }
        if (plain.isNotEmpty()) {
            val body = JSONObject()
                .put("conversationId", groupId)
                .put("messageId", messageId)
                .put("kind", kind)
                .put("payloadB64", payloadB64)
                .put("only", JSONArray(plain))
            val resp = ApiClient.post("/api/message", body, context) ?: return JSONObject().put("ok", false)
            if (createdAt == 0L) createdAt = resp.optLong("createdAt", 0L)
        }
        return JSONObject().put("ok", true).apply { if (createdAt > 0L) put("createdAt", createdAt) }
    }
}
