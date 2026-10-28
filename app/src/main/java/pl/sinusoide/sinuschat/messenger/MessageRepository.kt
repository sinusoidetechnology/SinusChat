package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.data.MessageDao
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.Translations
import java.util.UUID

object MessageRepository {

    private const val TAG = "MessageRepo"

    private val sending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun claim(messageId: String): Boolean = sending.add(messageId)

    fun release(messageId: String) { sending.remove(messageId) }

    private fun dao(context: Context): MessageDao? {
        return try {
            val keyInRam = DbKeyManager.cachedKey() != null
            val encryptionEnabled = DbKeyManager.isEnabled(context)
            
            if (encryptionEnabled && !keyInRam) {
                null
            } else {
                SinusChatDatabase.getInstance(context).messageDao()
            }
        } catch (e: Exception) { null }
    }

    fun conversationId(hashA: String, hashB: String): String =
        if (hashA < hashB) "${hashA}_${hashB}" else "${hashB}_${hashA}"

    private fun otherHash(convId: String, myHash: String): String? =
        convId.split("_", limit = 2).firstOrNull { it != myHash }?.takeIf { it.isNotEmpty() }

    fun observeMessages(context: Context, conversationId: String): LiveData<List<MessageEntity>> =
        dao(context)?.observeMessages(conversationId) ?: MutableLiveData<List<MessageEntity>>(emptyList())

    fun sendText(
        context: Context,
        conversationId: String,
        myHash: String,
        otherHash: String,
        sharedSecret: ByteArray,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        expiresAtMs: Long? = null,
        translation: Translations.Wire? = null
    ) {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderHash = myHash,
            text = text,
            timestampMs = System.currentTimeMillis(),
            status = MessageEntity.STATUS_PENDING,
            type = MessageEntity.TYPE_TEXT,
            replyToId = replyToId,
            replyToText = replyToText,
            expiresAtMs = expiresAtMs,
            trOrig = translation?.orig,
            trFrom = translation?.from,
            trTo = translation?.to
        )
        AppExecutor.local {
            dao(context)?.insert(entity)
            AppExecutor.send { pushToServer(context, entity, sharedSecret, otherHash) }
        }
    }

    fun queueText(
        context: Context,
        conversationId: String,
        myHash: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        translation: Translations.Wire? = null
    ) {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderHash = myHash,
            text = text,
            timestampMs = System.currentTimeMillis(),
            status = MessageEntity.STATUS_PENDING,
            type = MessageEntity.TYPE_TEXT,
            replyToId = replyToId,
            replyToText = replyToText,
            trOrig = translation?.orig,
            trFrom = translation?.from,
            trTo = translation?.to
        )
        val app = context.applicationContext
        AppExecutor.local {
            dao(app)?.insert(entity)
            AppExecutor.send { retryPendingOutgoing(app, myHash) }
        }
    }

    fun sendVoice(
        context: Context,
        conversationId: String,
        myHash: String,
        otherHash: String,
        sharedSecret: ByteArray,
        fileUrl: String,
        fileKey: String,
        durationSeconds: Int,
        localFile: java.io.File? = null
    ) {
        val entity = MessageEntity(
            id             = java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderHash     = myHash,
            text           = "",
            timestampMs    = System.currentTimeMillis(),
            status         = MessageEntity.STATUS_PENDING,
            type           = MessageEntity.TYPE_VOICE,
            fileId         = fileUrl,
            fileKey        = fileKey,
            attachmentName = "${durationSeconds}s"
        )
        localFile?.let {
            try { it.copyTo(MediaManager.voiceCacheFile(context, entity.id), overwrite = true) } catch (_: Exception) {}
        }
        AppExecutor.local {
            dao(context)?.insert(entity)
            AppExecutor.send { pushToServer(context, entity, sharedSecret, otherHash) }
        }
    }

    fun sendNowBlocking(context: Context, entity: MessageEntity, sharedSecret: ByteArray, otherHash: String) {
        dao(context)?.insert(entity)
        pushToServer(context, entity, sharedSecret, otherHash)
    }

    const val RESEND_MARK = "~r"
    const val CAPTION_SUFFIX = "_c"

    fun resendBlocking(context: Context, msg: MessageEntity, sharedSecret: ByteArray, recipientHash: String) =
        pushToServer(context, msg, sharedSecret, recipientHash, resend = true)

    fun requestResendBlocking(
        context: Context, conversationId: String, myHash: String, senderHash: String, ids: List<String>
    ) {
        if (ids.isEmpty()) return
        val payload = JSONObject().put("senderHash", myHash).put("ids", org.json.JSONArray(ids))
        sendEvent(context, conversationId, "resend_req", payload, senderHash)
    }

    private fun pushToServer(
        context: Context,
        msg: MessageEntity,
        sharedSecret: ByteArray,
        recipientHash: String,
        resend: Boolean = false
    ) {
        if (resend) { pushToServerClaimed(context, msg, sharedSecret, recipientHash, true); return }
        if (!claim(msg.id)) return
        try { pushToServerClaimed(context, msg, sharedSecret, recipientHash, false) } finally { release(msg.id) }
    }

    private fun pushToServerClaimed(
        context: Context,
        msg: MessageEntity,
        sharedSecret: ByteArray,
        recipientHash: String,
        resend: Boolean
    ) {
        if (!ApiClient.isAuthenticated(context)) {
            Log.w("MessageRepo", "Brak JWT — próba rejestracji przed wysłaniem")
            val emailHash = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
            val pubKey    = E2EManager.getMyPublicKey(context)
            val name      = pl.sinusoide.sinuschat.setup.SetupActivity.getUserDisplayName(context)
            if (emailHash.isNotEmpty() && pubKey != null) {
                val reg = ApiClient.post("/api/register", JSONObject().apply {
                    put("emailHash",    emailHash)
                    put("displayName",  pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealName(context, name.ifEmpty { "Użytkownik" }))
                    put("e2ePublicKey", pubKey)
                    put("keyUpdatedAt", E2EManager.getKeyCreatedAt(context))
                    put("deviceId",     pl.sinusoide.sinuschat.util.DeviceId.get(context))
                    ApiClient.getAuthProof(context)?.let { put("authProof", it) }
                }, context)
                val token = reg?.optString("token") ?: ""
                if (token.isNotEmpty()) {
                    ApiClient.saveToken(context, token)
                    Log.d("MessageRepo", "JWT pobrany — wysyłam wiadomość")
                } else {
                    Log.e("MessageRepo", "Nie udało się pobrać JWT — wiadomość zostaje pending")
                    if (!resend) scheduleRetry(context, msg.senderHash)
                    return
                }
            } else {
                Log.e("MessageRepo", "Brak emailHash lub klucza — nie mogę się zarejestrować")
                return
            }
        }
        if (resend) {
            pl.sinusoide.sinuschat.crypto.SignalProtocol.resetSession(context, recipientHash)
        }
        val v2 = if (resend) null else signalInner(context, msg, false)?.let {
            pl.sinusoide.sinuschat.crypto.SignalProtocol.encrypt(context, recipientHash, it.toString().toByteArray(Charsets.UTF_8))
        }
        if (v2 != null) {
            if (postMessage(context, msg, recipientHash, v2, resend) && !resend) afterSent(context, msg, sharedSecret, recipientHash)
            return
        }

        val innerContent = JSONObject().apply {
            put("senderHash", msg.senderHash)
            put("upk", pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKeyB64(context))
            if (resend) put("origId", msg.id)
            put("type", msg.type)
            put("timestamp", msg.timestampMs)
            if (msg.viewOnce) put("viewOnce", true)
            msg.expiresAtMs?.let { put("expiresAtMs", it) }
            if (msg.type == MessageEntity.TYPE_ATTACHMENT) {
                val encKey = msg.fileKey?.let { E2EManager.encrypt(it, sharedSecret) }
                if (msg.fileId.isNullOrEmpty() || encKey == null) {
                    dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED); return
                }
                put("fileId", msg.fileId)
                put("encryptedFileKey", encKey)
                put("attachmentName", msg.attachmentName ?: "Załącznik")
                msg.albumId?.let { put("albumId", it) }
                videoThumb(context, msg)?.let { pl.sinusoide.sinuschat.util.VideoThumbs.putInto(this, it) }
                if (msg.text.isNotBlank()) {
                    E2EManager.encrypt(msg.text, sharedSecret)?.let { put("encryptedCaption", it) }
                    msg.replyToId?.let { put("replyToId", it) }
                    msg.replyToText?.let { rt -> E2EManager.encrypt(rt, sharedSecret)?.let { put("encryptedReplyToText", it) } }
                }
            } else if (msg.type == MessageEntity.TYPE_VOICE) {
                val url = msg.fileId
                if (url.isNullOrEmpty()) {
                    dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED); return
                }
                put("fileId", url)
                put("attachmentName", msg.attachmentName ?: "0s")
                msg.fileKey?.let { k -> E2EManager.encrypt(k, sharedSecret)?.let { put("encryptedFileKey", it) } }
            } else {
                val encText = E2EManager.encrypt(msg.text, sharedSecret)
                if (encText == null) { dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED); return }
                put("encryptedText", encText)
                msg.replyToId?.let { put("replyToId", it) }
                msg.replyToText?.let { rt ->
                    E2EManager.encrypt(rt, sharedSecret)?.let { put("encryptedReplyToText", it) }
                }
                wireTranslation(msg)?.let { tr ->
                    E2EManager.encrypt(tr.toString(), sharedSecret)?.let { put("encryptedTr", it) }
                }
            }
        }

        val encryptedContent = E2EManager.encrypt(innerContent.toString(), sharedSecret)
        if (encryptedContent == null) {
            dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED); return
        }

        val payloadJson = JSONObject().apply {
            put("timestamp", msg.timestampMs)
            put("type", msg.type)
            put("encryptedContent", encryptedContent)
        }

        if (postMessage(context, msg, recipientHash, payloadJson, resend) && !resend) afterSent(context, msg, sharedSecret, recipientHash)
    }

    private fun afterSent(context: Context, msg: MessageEntity, sharedSecret: ByteArray, recipientHash: String) {
        if (msg.type != MessageEntity.TYPE_ATTACHMENT || msg.text.isBlank() || msg.viewOnce) return
        try { pushCaptionCompanion(context, msg, sharedSecret, recipientHash) }
        catch (e: Exception) { Log.w(TAG, "Podpis dla starszych wersji: ${e.message}") }
    }

    private fun pushCaptionCompanion(context: Context, msg: MessageEntity, sharedSecret: ByteArray, recipientHash: String) {
        val companion = msg.copy(id = msg.id + CAPTION_SUFFIX, type = MessageEntity.TYPE_TEXT,
            fileId = null, fileKey = null, attachmentName = null, albumId = null)
        val inner = signalInner(context, companion, false, capOf = msg.id) ?: return
        val payload = pl.sinusoide.sinuschat.crypto.SignalProtocol.encrypt(
            context, recipientHash, inner.toString().toByteArray(Charsets.UTF_8)) ?: run {
            val content = JSONObject()
                .put("senderHash", companion.senderHash)
                .put("upk", pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKeyB64(context))
                .put("type", companion.type)
                .put("timestamp", companion.timestampMs)
                .put("capOf", msg.id)
                .put("encryptedText", E2EManager.encrypt(companion.text, sharedSecret) ?: return)
            companion.expiresAtMs?.let { content.put("expiresAtMs", it) }
            companion.replyToId?.let { content.put("replyToId", it) }
            companion.replyToText?.let { rt -> E2EManager.encrypt(rt, sharedSecret)?.let { content.put("encryptedReplyToText", it) } }
            JSONObject()
                .put("timestamp", companion.timestampMs)
                .put("type", companion.type)
                .put("encryptedContent", E2EManager.encrypt(content.toString(), sharedSecret) ?: return)
        }
        val body = JSONObject()
            .put("conversationId", companion.conversationId)
            .put("messageId", companion.id)
            .put("kind", "msg")
            .put("payloadB64", Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("recipientHash", recipientHash)
        pl.sinusoide.sinuschat.crypto.SealedSender.post(context, body)
    }

    private fun videoThumb(context: Context, msg: MessageEntity): pl.sinusoide.sinuschat.util.VideoThumbs.Info? {
        if (msg.viewOnce) return null
        val name = msg.attachmentName ?: return null
        return try {
            pl.sinusoide.sinuschat.util.VideoThumbs.ensure(context, msg.conversationId, msg.id, name)
        } catch (_: Exception) { null }
    }

    private fun signalInner(context: Context, msg: MessageEntity, resend: Boolean, capOf: String? = null): JSONObject? {
        val o = JSONObject()
            .put("senderHash", msg.senderHash)
            .put("type", msg.type)
            .put("timestamp", msg.timestampMs)
            .put("upk", pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKeyB64(context))
        if (resend) o.put("origId", msg.id)
        if (msg.viewOnce) o.put("viewOnce", true)
        msg.expiresAtMs?.let { o.put("expiresAtMs", it) }
        when (msg.type) {
            MessageEntity.TYPE_ATTACHMENT -> {
                if (msg.fileId.isNullOrEmpty() || msg.fileKey.isNullOrEmpty()) {
                    if (!resend) dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED)
                    return null
                }
                o.put("fileId", msg.fileId).put("fileKey", msg.fileKey)
                    .put("attachmentName", msg.attachmentName ?: "Załącznik")
                msg.albumId?.let { o.put("albumId", it) }
                videoThumb(context, msg)?.let { pl.sinusoide.sinuschat.util.VideoThumbs.putInto(o, it) }
                if (msg.text.isNotBlank()) {
                    o.put("caption", msg.text)
                    msg.replyToId?.let { o.put("replyToId", it) }
                    msg.replyToText?.let { o.put("replyToText", it) }
                }
            }
            MessageEntity.TYPE_VOICE -> {
                if (msg.fileId.isNullOrEmpty()) {
                    if (!resend) dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED)
                    return null
                }
                o.put("fileId", msg.fileId).put("attachmentName", msg.attachmentName ?: "0s")
                msg.fileKey?.let { o.put("fileKey", it) }
            }
            else -> {
                o.put("text", msg.text)
                msg.replyToId?.let { o.put("replyToId", it) }
                msg.replyToText?.let { o.put("replyToText", it) }
                wireTranslation(msg)?.let { o.put("tr", it) }
                capOf?.let { o.put("capOf", it) }
            }
        }
        return o
    }

    fun wireTranslation(msg: MessageEntity): JSONObject? {
        val orig = msg.trOrig ?: return null
        val from = msg.trFrom ?: return null
        val to = msg.trTo ?: return null
        return Translations.toJson(orig, from, to)
    }

    private fun postMessage(
        context: Context, msg: MessageEntity, recipientHash: String, payloadJson: JSONObject, resend: Boolean
    ): Boolean {
        val payloadB64 = Base64.encodeToString(
            payloadJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

        val body = JSONObject().apply {
            put("conversationId", msg.conversationId)
            put("messageId", if (resend) msg.id + RESEND_MARK + (System.currentTimeMillis() % 1_000_000) else msg.id)
            put("kind", "msg")
            put("payloadB64", payloadB64)
            put("recipientHash", recipientHash)
        }

        var sent = false
        var response: JSONObject? = null
        val backoff = longArrayOf(0L, 1_500L, 4_000L)
        for (attempt in backoff.indices) {
            if (backoff[attempt] > 0) {
                try { Thread.sleep(backoff[attempt]) } catch (_: InterruptedException) { break }
            }
            val result = pl.sinusoide.sinuschat.crypto.SealedSender.post(context, body)
            if (result != null) { response = result; sent = true; break }
            if (BlockList.isDeleted(context, recipientHash)) break
            Log.w(TAG, "POST /api/message nieudany (próba ${attempt + 1}/${backoff.size}) — ${msg.id}")
        }

        if (resend) {
            if (!sent) Log.w(TAG, "Ponowna wysyłka nieudana — ${msg.id}")
            return sent
        }
        if (!sent && BlockList.isDeleted(context, recipientHash)) {
            dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_FAILED)
            return false
        }
        if (sent) {
            val serverTs = response?.optLong("createdAt", 0L) ?: 0L
            if (serverTs > 0L) dao(context)?.updateTimestamp(msg.id, serverTs)
            dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_SENT)
            BlockList.clearBlockedByThem(context, recipientHash)
            BlockList.clearDeleted(context, recipientHash)
        } else {
            dao(context)?.updateStatus(msg.id, MessageEntity.STATUS_PENDING)
            scheduleRetry(context, msg.senderHash)
        }
        return sent
    }

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var retryScheduled = false

    fun scheduleRetry(context: Context, myHash: String) {
        if (retryScheduled) return
        retryScheduled = true
        val appCtx = context.applicationContext
        retryHandler.postDelayed({
            retryScheduled = false
            retryPendingOutgoing(appCtx, myHash)
        }, 10_000L)
    }

    fun retryPendingOutgoing(context: Context, myHash: String) {
        AppExecutor.io {
            val d = dao(context) ?: return@io
            val convIds = d.getConversationsWithPending()
            for (convId in convIds) {
                if (GroupRepository.isGroup(convId)) {
                    val mine = d.getPendingOutgoing(convId).filter {
                        it.senderHash == myHash && it.type == MessageEntity.TYPE_TEXT
                    }
                    var failed = false
                    for (msg in mine) if (!GroupRepository.resendPendingTextBlocking(context, msg)) failed = true
                    if (failed) scheduleRetry(context, myHash)
                    continue
                }
                val recipientHash = otherHash(convId, myHash) ?: continue
                val pending = d.getPendingOutgoing(convId)
                if (pending.isEmpty()) continue
                if (BlockList.isDeleted(context, recipientHash)) {
                    for (m in pending) d.updateStatus(m.id, MessageEntity.STATUS_FAILED)
                    continue
                }
                val secret = E2EManager.fetchAndCacheKey(context, recipientHash)
                    ?.let { E2EManager.computeSharedSecret(context, it) }
                    ?: E2EManager.getSharedSecretCached(context, recipientHash)
                    ?: run { scheduleRetry(context, myHash); null }
                    ?: continue
                for (msg in pending) pushToServer(context, msg, secret, recipientHash)
            }
        }
    }

    fun setReaction(
        context: Context,
        conversationId: String,
        myHash: String,
        recipientHash: String,
        sharedSecret: ByteArray,
        targetMessageId: String,
        emoji: String?
    ) {
        AppExecutor.io {
            val d = dao(context) ?: return@io
            d.getById(targetMessageId)?.let { msg ->
                val current = msg.reactions.toMutableMap()
                if (emoji.isNullOrEmpty()) current.remove(myHash) else current[myHash] = emoji
                d.update(msg.copy(reactions = current))
            }
            val payload = JSONObject().put("senderHash", myHash).put("targetMessageId", targetMessageId)
            val v2 = pl.sinusoide.sinuschat.crypto.SignalProtocol.encrypt(context, recipientHash, (emoji ?: "").toByteArray(Charsets.UTF_8))
            if (v2 != null) mergeInto(payload, v2)
            else payload.put("encryptedEmoji", E2EManager.encrypt(emoji ?: "", sharedSecret) ?: return@io)
            sendEvent(context, conversationId, "reaction", payload, recipientHash)
        }
    }

    fun sendProfileKeyBlocking(context: Context, myHash: String, peer: String, direct: Boolean = false) {
        val plain = JSONObject().put("upk", pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKeyB64(context))
            .toString().toByteArray(Charsets.UTF_8)
        val v2 = pl.sinusoide.sinuschat.crypto.SignalProtocol.encrypt(context, peer, plain) ?: return
        val payload = JSONObject().put("senderHash", myHash)
        mergeInto(payload, v2)
        sendEvent(context, conversationId(myHash, peer), pl.sinusoide.sinuschat.crypto.ProfileCrypto.KIND_KEY, payload, peer, direct)
    }

    fun askProfileKeyBlocking(context: Context, myHash: String, peer: String) {
        val payload = JSONObject().put("senderHash", myHash)
        sendEvent(context, conversationId(myHash, peer), pl.sinusoide.sinuschat.crypto.ProfileCrypto.KIND_KEY_REQUEST, payload, peer)
    }

    fun setPinned(
        context: Context,
        conversationId: String,
        myHash: String,
        recipientHash: String,
        targetMessageId: String,
        pinned: Boolean
    ) {
        AppExecutor.io {
            val d = dao(context) ?: return@io
            d.getById(targetMessageId)?.let { d.update(it.copy(pinned = pinned)) }
            val payload = JSONObject().put("senderHash", myHash)
                .put("targetMessageId", targetMessageId).put("pinned", pinned)
            sendEvent(context, conversationId, "pin", payload, recipientHash)
        }
    }

    fun deleteMessageEverywhere(
        context: Context,
        conversationId: String,
        myHash: String,
        recipientHash: String,
        targetMessageId: String
    ) {
        AppExecutor.io {
            val captioned = getByIdBlocking(context, targetMessageId)
                ?.let { it.type == MessageEntity.TYPE_ATTACHMENT && it.text.isNotBlank() } == true
            deleteWithMediaBlocking(context, targetMessageId)
            val payload = JSONObject().put("senderHash", myHash).put("targetMessageId", targetMessageId)
            sendEvent(context, conversationId, "delete", payload, recipientHash)
            if (captioned) {
                val companion = JSONObject().put("senderHash", myHash)
                    .put("targetMessageId", targetMessageId + CAPTION_SUFFIX)
                sendEvent(context, conversationId, "delete", companion, recipientHash)
            }
        }
    }

    fun editMessageEverywhere(
        context: Context,
        conversationId: String,
        myHash: String,
        recipientHash: String,
        sharedSecret: ByteArray,
        targetMessageId: String,
        newText: String
    ) {
        AppExecutor.io {
            val d = dao(context) ?: return@io
            d.getById(targetMessageId)?.let { d.update(it.copy(text = newText, edited = true,
                trOrig = null, trLocal = null, trFrom = null, trTo = null)) }
            val payload = JSONObject().put("senderHash", myHash).put("targetMessageId", targetMessageId)
            val v2 = pl.sinusoide.sinuschat.crypto.SignalProtocol.encrypt(context, recipientHash, newText.toByteArray(Charsets.UTF_8))
            if (v2 != null) mergeInto(payload, v2)
            else payload.put("encryptedText", E2EManager.encrypt(newText, sharedSecret) ?: return@io)
            sendEvent(context, conversationId, "edit", payload, recipientHash)
        }
    }

    fun applyEditPatchBlocking(context: Context, conversationId: String, senderHash: String,
                               targetMessageId: String, newText: String) {
        val d = dao(context) ?: return
        d.getById(targetMessageId)
            ?.takeIf { it.conversationId == conversationId && it.senderHash == senderHash && !it.isSystemMessage }
            ?.let { d.update(it.copy(text = newText, edited = true,
                trOrig = null, trLocal = null, trFrom = null, trTo = null)) }
    }

    fun sendMovedBlocking(context: Context, conversationId: String, myHash: String, other: String,
                          oldHash: String, sig: String) {
        sendEvent(context, conversationId, pl.sinusoide.sinuschat.util.AccountMove.KIND,
            JSONObject().put("senderHash", myHash).put("oldHash", oldHash).put("sig", sig), other)
    }

    fun sendKeyHintsBlocking(context: Context, myHash: String) {
        for (other in pl.sinusoide.sinuschat.util.LocalChats.contacts(context, myHash).take(500)) {
            try {
                sendEvent(context, conversationId(myHash, other), "key_hint",
                    JSONObject().put("senderHash", myHash), other)
            } catch (_: Exception) {}
        }
    }

    private fun mergeInto(into: JSONObject, from: JSONObject) {
        for (k in from.keys()) into.put(k, from.get(k))
    }

    private fun sendEvent(
        context: Context,
        conversationId: String,
        kind: String,
        payloadJson: JSONObject,
        recipientHash: String,
        direct: Boolean = false
    ) {
        val payloadB64 = Base64.encodeToString(
            payloadJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val body = JSONObject().apply {
            put("conversationId", conversationId)
            put("messageId", UUID.randomUUID().toString())
            put("kind", kind)
            put("payloadB64", payloadB64)
            put("recipientHash", recipientHash)
        }
        if (direct) ApiClient.post("/api/message", body, context)
        else pl.sinusoide.sinuschat.crypto.SealedSender.post(context, body)
    }

    fun deleteConversation(context: Context, conversationId: String, myHash: String, onDone: (() -> Unit)?) {
        AppExecutor.io {
            try {
                ApiClient.post("/api/conversations/delete", JSONObject().apply {
                    put("conversationId", conversationId)
                }, context)
            } catch (_: Exception) {}
            dao(context)?.clearConversation(conversationId)
            MediaManager.clearConversationMedia(context, conversationId)
            try { pl.sinusoide.sinuschat.util.ConversationCache.remove(context, conversationId) } catch (_: Exception) {}
            pl.sinusoide.sinuschat.util.LocalChats.remove(context, conversationId)
            pl.sinusoide.sinuschat.util.ChatBubbles.forget(context, conversationId)
            try { androidx.core.app.NotificationManagerCompat.from(context).cancel(conversationId.hashCode()) } catch (_: Exception) {}
            onDone?.invoke()
        }
    }

    fun clearChatLocal(context: Context, conversationId: String) {
        AppExecutor.io {
            dao(context)?.clearConversation(conversationId)
            MediaManager.clearConversationMedia(context, conversationId)
        }
    }

    fun publishDelivered(context: Context, conversationId: String, myHash: String, uptoMs: Long) {
        AppExecutor.io {
            if (queueReceipt(context, conversationId, myHash, uptoMs, 0L)) return@io
            val small = conversationId.startsWith("g_") &&
                pl.sinusoide.sinuschat.util.GroupReceipts.isSmall(context, conversationId)
            if (conversationId.startsWith("g_") && pl.sinusoide.sinuschat.util.GroupReceipts.mayBeSmall(context, conversationId))
                pl.sinusoide.sinuschat.util.GroupReceipts.queue(context, conversationId, myHash, uptoMs, 0L)
            ApiClient.post("/api/ack", JSONObject().apply {
                put("conversationId", conversationId)
                put("deliveredUpTo", uptoMs)
                if (small) put("noRelay", true)
            }, context)
        }
    }

    private val receiptLock = Any()
    private val pendingReceipts = HashMap<String, LongArray>()
    private var receiptFlushScheduled = false
    private val unsealedConvs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun noteUnsealed(conversationId: String) { unsealedConvs.add(conversationId) }

    private fun sealedPartner(context: Context, conversationId: String, myHash: String): String? {
        if (conversationId.startsWith("g_") || myHash.isEmpty()) return null
        val other = otherHash(conversationId, myHash)?.takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: return null
        if (conversationId != conversationId(myHash, other)) return null
        return other.takeIf { pl.sinusoide.sinuschat.crypto.SealedSender.canSeal(context, it) }
    }

    private fun queueReceipt(context: Context, conversationId: String, myHash: String, delivered: Long, read: Long,
                             disappear: Long = 0L): Boolean {
        sealedPartner(context, conversationId, myHash) ?: return false
        val app = context.applicationContext
        synchronized(receiptLock) {
            val cur = pendingReceipts.getOrPut(conversationId) { longArrayOf(0L, 0L, 0L) }
            cur[0] = maxOf(cur[0], delivered)
            cur[1] = maxOf(cur[1], read)
            cur[2] = maxOf(cur[2], disappear)
            if (!receiptFlushScheduled) {
                receiptFlushScheduled = true
                retryHandler.postDelayed({ flushReceipts(app, myHash) }, 1_500L)
            }
        }
        return true
    }

    private fun flushReceipts(context: Context, myHash: String) {
        val batch = synchronized(receiptLock) {
            receiptFlushScheduled = false
            pendingReceipts.toMap().also { pendingReceipts.clear() }
        }
        AppExecutor.io {
            for ((convId, v) in batch) {
                val other = otherHash(convId, myHash) ?: continue
                val payload = JSONObject().apply {
                    if (v[0] > 0) put("deliveredUpTo", v[0])
                    if (v[1] > 0) put("readUpTo", v[1])
                    if (v[2] > v[1]) put("disappearUpTo", v[2])
                }
                val body = JSONObject()
                    .put("conversationId", convId)
                    .put("messageId", UUID.randomUUID().toString())
                    .put("kind", "ack")
                    .put("recipientHash", other)
                    .put("payloadB64", Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                pl.sinusoide.sinuschat.crypto.SealedSender.post(context, body)
                if (unsealedConvs.remove(convId)) {
                    ApiClient.post("/api/ack", JSONObject()
                        .put("conversationId", convId)
                        .put("deliveredUpTo", maxOf(v[0], v[1]))
                        .put("noRelay", true), context)
                }
            }
        }
    }

    fun markUndeliveredExpired(context: Context) {
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
        if (me.isEmpty()) return
        val d = dao(context) ?: return
        val p = context.getSharedPreferences("sealed_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val before = now - 25 * 3600_000L
        val after = maxOf(p.getLong("expiry_checked", 0L), now - 7 * 24 * 3600_000L)
        if (before <= after) return
        val touched = HashSet<String>()
        for (m in d.getSentUndelivered(me, after, before)) {
            d.updateStatus(m.id, MessageEntity.STATUS_UNDELIVERED)
            touched += m.conversationId
        }
        p.edit().putLong("expiry_checked", before).apply()
        for (conv in touched) refreshExpiryNotices(context, conv)
    }

    private const val EXPIRED_PREFIX = "sys_expired_"

    fun onMessageExpired(context: Context, conversationId: String, originalId: String, isGroup: Boolean,
                         delivered: Int, total: Int) {
        if (originalId.isEmpty()) return
        val d = dao(context) ?: return
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
        val original = d.getById(originalId) ?: return
        if (original.senderHash != me) return
        if (isGroup && delivered > 0) {
            if (pl.sinusoide.sinuschat.util.NotificationPrefs.undeliveredNotice(context)) d.insert(MessageEntity(
                id = EXPIRED_PREFIX + originalId, conversationId = conversationId, senderHash = "",
                text = context.getString(pl.sinusoide.sinuschat.R.string.msg_expired_group_partial, delivered, total),
                timestampMs = original.timestampMs + 1, type = MessageEntity.TYPE_EXPIRED_NOTICE,
                isSystemMessage = true, status = MessageEntity.STATUS_READ))
            return
        }
        if (original.status == MessageEntity.STATUS_SENT || original.status == MessageEntity.STATUS_PENDING)
            d.updateStatus(originalId, MessageEntity.STATUS_UNDELIVERED)
        refreshExpiryNotices(context, conversationId)
    }

    fun refreshAllExpiryNotices(context: Context) {
        val d = dao(context) ?: return
        for (conv in d.getConversationsWithExpiryMarks()) refreshExpiryNotices(context, conv)
    }

    fun refreshExpiryNotices(context: Context, conversationId: String) {
        val d = dao(context) ?: return
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
        if (me.isEmpty() || d.countExpiryMarks(conversationId) == 0) return
        val isGroup = conversationId.startsWith("g_")
        var all = d.getAllForConversationSync(conversationId).sortedBy { it.timestampMs }
        val groupNone = context.getString(pl.sinusoide.sinuschat.R.string.msg_expired_group_none)
        var migrated = false
        val byId = all.associateBy { it.id }
        for (n in all) {
            if (n.type != MessageEntity.TYPE_EXPIRED_NOTICE) continue
            val o = byId[n.id.removePrefix(EXPIRED_PREFIX)] ?: continue
            if (o.senderHash == me && o.status == MessageEntity.STATUS_SENT && (!isGroup || n.text == groupNone)) {
                d.updateStatus(o.id, MessageEntity.STATUS_UNDELIVERED)
                migrated = true
            }
        }
        if (migrated) all = d.getAllForConversationSync(conversationId).sortedBy { it.timestampMs }

        val show = pl.sinusoide.sinuschat.util.NotificationPrefs.undeliveredNotice(context)
        val real = all.filter { !it.isSystemMessage }
        val wanted = HashMap<String, MessageEntity>()
        var run = 0
        for ((i, m) in real.withIndex()) {
            if (m.senderHash != me || m.status != MessageEntity.STATUS_UNDELIVERED) { run = 0; continue }
            run++
            val next = real.getOrNull(i + 1)
            if (next != null && next.senderHash == me && next.status == MessageEntity.STATUS_UNDELIVERED) continue
            if (show) wanted[EXPIRED_PREFIX + m.id] = MessageEntity(
                id = EXPIRED_PREFIX + m.id, conversationId = conversationId, senderHash = "",
                text = context.getString(when {
                    isGroup && run > 1 -> pl.sinusoide.sinuschat.R.string.msg_expired_group_none_many
                    isGroup -> pl.sinusoide.sinuschat.R.string.msg_expired_group_none
                    run > 1 -> pl.sinusoide.sinuschat.R.string.msg_expired_private_many
                    else -> pl.sinusoide.sinuschat.R.string.msg_expired_private
                }),
                timestampMs = m.timestampMs + 1, type = MessageEntity.TYPE_EXPIRED_NOTICE,
                isSystemMessage = true, status = MessageEntity.STATUS_READ)
            run = 0
        }

        val current = all.associateBy { it.id }
        for (n in all) {
            if (n.type != MessageEntity.TYPE_EXPIRED_NOTICE || n.id in wanted) continue
            val o = current[n.id.removePrefix(EXPIRED_PREFIX)]
            if (show && isGroup && o != null && o.status != MessageEntity.STATUS_UNDELIVERED) {
                if (n.timestampMs != o.timestampMs + 1) d.updateTimestamp(n.id, o.timestampMs + 1)
            } else d.deleteById(n.id)
        }
        for (row in wanted.values) {
            val cur = current[row.id]
            if (cur == null || cur.text != row.text || cur.timestampMs != row.timestampMs) d.insert(row)
        }
    }

    fun publishRead(context: Context, conversationId: String, myHash: String, uptoMs: Long) {
        if (uptoMs <= 0L) return
        pl.sinusoide.sinuschat.util.LocalChats.setReadMark(context, conversationId, uptoMs)
        val silentRead = pl.sinusoide.sinuschat.util.PrivacySettings.isReadStatusDisabled(context)
        val disappearing = pl.sinusoide.sinuschat.util.DisappearingMessages.getTtl(context, conversationId) > 0L
        AppExecutor.io {
            if (sealedPartner(context, conversationId, myHash) != null) {
                if (!silentRead) queueReceipt(context, conversationId, myHash, 0L, uptoMs)
                else if (disappearing) queueReceipt(context, conversationId, myHash, 0L, 0L, uptoMs)
                return@io
            }
            if (conversationId.startsWith("g_")) {
                if (pl.sinusoide.sinuschat.util.GroupReceipts.mayBeSmall(context, conversationId)) {
                    if (!silentRead) pl.sinusoide.sinuschat.util.GroupReceipts.queue(context, conversationId, myHash, 0L, uptoMs)
                    else if (disappearing) pl.sinusoide.sinuschat.util.GroupReceipts.queue(context, conversationId, myHash, 0L, 0L, uptoMs)
                }
                return@io
            }
            if (silentRead) return@io
            ApiClient.post("/api/ack", JSONObject().apply {
                put("conversationId", conversationId)
                put("readUpTo", uptoMs)
            }, context)
        }
    }

    fun applyServerAcks(context: Context, conversationId: String, myHash: String,
                        otherDeliveredUpTo: Long, otherReadUpTo: Long, repair: Boolean) {
        AppExecutor.io {
            val dao = dao(context) ?: return@io
            val delivered = maxOf(otherDeliveredUpTo, otherReadUpTo)
            if (repair && delivered > 0) {
                dao.downgradeToDelivered(conversationId, myHash, otherReadUpTo, delivered)
                dao.downgradeToSent(conversationId, myHash, delivered)
            }
            if (otherDeliveredUpTo > 0) dao.markDeliveredUpTo(conversationId, myHash, otherDeliveredUpTo)
            if (otherReadUpTo > 0) {
                dao.markReadUpTo(conversationId, myHash, otherReadUpTo)
                pl.sinusoide.sinuschat.util.DisappearingMessages
                    .applyOnReadAck(context, conversationId, myHash, otherReadUpTo)
            }
        }
    }

    private const val ACK_PREFS = "sinuschat_acks"
    private const val ACK_REPAIR_KEY = "status_repair_v1"

    fun needsAckRepair(context: Context): Boolean =
        !context.getSharedPreferences(ACK_PREFS, Context.MODE_PRIVATE).getBoolean(ACK_REPAIR_KEY, false)

    fun markAckRepairDone(context: Context) {
        context.getSharedPreferences(ACK_PREFS, Context.MODE_PRIVATE).edit().putBoolean(ACK_REPAIR_KEY, true).apply()
    }

    fun applyDeliveryAck(context: Context, conversationId: String, myHash: String, uptoMs: Long) {
        AppExecutor.io { dao(context)?.markDeliveredUpTo(conversationId, myHash, uptoMs) }
    }

    fun applyDisappearAck(context: Context, conversationId: String, myHash: String, uptoMs: Long) {
        AppExecutor.io { pl.sinusoide.sinuschat.util.DisappearingMessages.applyOnReadAck(context, conversationId, myHash, uptoMs) }
    }

    fun applyReadAck(context: Context, conversationId: String, myHash: String, uptoMs: Long) {
        AppExecutor.io {
            dao(context)?.markReadUpTo(conversationId, myHash, uptoMs)
            pl.sinusoide.sinuschat.util.DisappearingMessages
                .applyOnReadAck(context, conversationId, myHash, uptoMs)
        }
    }

    fun getByIdBlocking(context: Context, id: String): MessageEntity? = dao(context)?.getById(id)
    fun insertIncomingMessageBlocking(context: Context, entity: MessageEntity) {
        dao(context)?.insert(entity)
    }
    fun markStatusBlocking(context: Context, id: String, status: String) {
        dao(context)?.updateStatus(id, status)
    }

    fun applyReactionPatchBlocking(context: Context, conversationId: String, targetId: String, fromHash: String, emoji: String?) {
        val d = dao(context) ?: return
        val msg = d.getById(targetId)?.takeIf { it.conversationId == conversationId } ?: return
        val map = msg.reactions.toMutableMap()
        if (emoji.isNullOrEmpty()) map.remove(fromHash) else map[fromHash] = emoji
        d.update(msg.copy(reactions = map))
    }

    fun applyPinPatchBlocking(context: Context, conversationId: String, targetId: String, pinned: Boolean) {
        val d = dao(context) ?: return
        val msg = d.getById(targetId)?.takeIf { it.conversationId == conversationId } ?: return
        d.update(msg.copy(pinned = pinned))
    }

    fun applyDeletePatchBlocking(context: Context, conversationId: String, senderHash: String, targetId: String) {
        val msg = dao(context)?.getById(targetId) ?: return
        if (msg.conversationId != conversationId) return
        if (msg.senderHash != senderHash || msg.isSystemMessage) return
        deleteWithMediaBlocking(context, targetId)
        try { pl.sinusoide.sinuschat.util.MessageNotifier.onMessageDeleted(context, msg) } catch (_: Exception) {}
    }

    fun deleteWithMediaBlocking(context: Context, messageId: String) {
        val d = dao(context) ?: return
        val msg = try { d.getById(messageId) } catch (_: Exception) { null }
        if (msg != null) {
            try { MediaManager.deleteMessageMedia(context, msg.conversationId, msg.id) } catch (_: Exception) {}
        }
        try { MediaManager.voiceCacheFile(context, messageId).delete() } catch (_: Exception) {}
        d.deleteById(messageId)
    }

    fun insertSystemMessage(
        context: Context,
        conversationId: String,
        text: String,
        kind: String
    ) {
        try {
            val entity = MessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderHash = "system",
                text = text,
                timestampMs = System.currentTimeMillis(),
                status = MessageEntity.STATUS_READ,
                type = MessageEntity.TYPE_TEXT,
                fileId = kind,
                isSystemMessage = true
            )
            dao(context)?.insert(entity)
        } catch (_: Exception) {}
    }
}
