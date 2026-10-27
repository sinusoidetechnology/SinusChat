package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONObject
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.ImagePrep
import pl.sinusoide.sinuschat.util.MediaManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object AttachmentSender {

    private const val TAG = "AttachmentSender"
    private const val MAX_BYTES = FileUploader.MAX_CONTENT
    private const val IMAGE_PREP_MAX = 40L * 1024 * 1024

    enum class Kind { IMAGE, VIDEO, FILE }
    class Item(val uri: Uri, val kind: Kind, val viewOnce: Boolean,
               val mime: String? = null, val keepOriginal: Boolean = false,
               val packed: java.io.File? = null)

    class Caption(val text: String, val replyToId: String?, val replyToText: String?)

    class Target(
        val conversationId: String,
        val myHash: String,
        val otherHash: String,
        val secret: ByteArray?,
        val isGroup: Boolean,
        val recipients: Int
    )

    private class State {
        @Volatile var progress: Float? = null
        @Volatile var call: okhttp3.Call? = null
        @Volatile var cancelled = false
    }

    const val LOCATION_PENDING = "location_.png"

    private val states = ConcurrentHashMap<String, State>()
    private val queue = Executors.newSingleThreadExecutor()
    private val local = Executors.newSingleThreadExecutor()

    @Volatile var onLocalChanged: ((conversationId: String) -> Unit)? = null
    @Volatile var onError: ((conversationId: String, message: String) -> Unit)? = null

    fun progressOf(id: String): Float? = states[id]?.progress

    fun isActive(id: String): Boolean = states.containsKey(id)

    fun send(ctx: Context, target: Target, items: List<Item>, caption: Caption? = null) {
        if (items.isEmpty()) return
        val app = ctx.applicationContext
        val albumId = if (items.count { it.kind == Kind.IMAGE && !it.viewOnce } >= 2)
            UUID.randomUUID().toString() else null
        val now = System.currentTimeMillis()
        val captionAt = if (caption == null) -1 else items.indexOfFirst { !it.viewOnce }
        val jobs = items.mapIndexed { i, item ->
            val withCaption = i == captionAt
            val entity = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = target.conversationId,
                senderHash = target.myHash,
                timestampMs = now + i,
                status = MessageEntity.STATUS_UPLOADING,
                type = MessageEntity.TYPE_ATTACHMENT,
                fileId = "",
                fileKey = null,
                attachmentName = nameFor(app, item, now + i),
                viewOnce = item.viewOnce,
                albumId = if (item.kind == Kind.IMAGE && !item.viewOnce) albumId else null,
                text = if (withCaption) caption!!.text else "",
                replyToId = if (withCaption) caption!!.replyToId else null,
                replyToText = if (withCaption) caption!!.replyToText else null
            )
            states[entity.id] = State()
            entity to item
        }
        AppExecutor.io { jobs.forEach { dao(app).insert(it.first) } }
        for ((entity, item) in jobs) queue.execute { process(app, target, entity, item.uri, item.kind, item.keepOriginal, item.packed) }
    }

    fun sizeOf(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return java.io.File(uri.path ?: return -1L).length()
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) {}
        return try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
    }

    fun packedDir(ctx: Context): java.io.File = java.io.File(ctx.cacheDir, "upload_packed").apply { mkdirs() }

    fun reserve(ctx: Context, target: Target, name: String, bytes: ByteArray): MessageEntity {
        val app = ctx.applicationContext
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = target.conversationId,
            senderHash = target.myHash,
            timestampMs = System.currentTimeMillis(),
            status = MessageEntity.STATUS_UPLOADING,
            type = MessageEntity.TYPE_ATTACHMENT,
            fileId = "",
            fileKey = null,
            attachmentName = name
        )
        states[entity.id] = State()
        local.execute {
            MediaManager.saveMedia(app, entity.conversationId, entity.id, name, bytes)
            dao(app).insert(entity)
            onLocalChanged?.invoke(entity.conversationId)
        }
        return entity
    }

    fun complete(ctx: Context, target: Target, msg: MessageEntity, name: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        local.execute {
            val st = states[msg.id] ?: return@execute
            if (st.cancelled) return@execute
            val done = msg.copy(attachmentName = name)
            MediaManager.deleteMessageMedia(app, msg.conversationId, msg.id)
            MediaManager.saveMedia(app, msg.conversationId, msg.id, name, bytes)
            dao(app).insert(done)
            onLocalChanged?.invoke(msg.conversationId)
            queue.execute { process(app, target, done, null, Kind.IMAGE) }
        }
    }

    fun abandon(ctx: Context, msg: MessageEntity) {
        val app = ctx.applicationContext
        states.remove(msg.id)?.cancelled = true
        local.execute { discard(app, msg) }
    }

    fun retry(ctx: Context, target: Target, msg: MessageEntity) {
        val app = ctx.applicationContext
        val name = msg.attachmentName ?: return
        if (isActive(msg.id)) return
        if (name == LOCATION_PENDING) { cancel(app, msg); return }
        if (!MediaManager.getMediaFile(app, msg.conversationId, msg.id, name).exists()) {
            cancel(app, msg)
            onError?.invoke(msg.conversationId, app.getString(R.string.upload_error))
            return
        }
        states[msg.id] = State()
        AppExecutor.io { dao(app).updateStatus(msg.id, MessageEntity.STATUS_UPLOADING) }
        queue.execute { process(app, target, msg.copy(status = MessageEntity.STATUS_UPLOADING), null, kindOf(name)) }
    }

    fun cancel(ctx: Context, msg: MessageEntity) {
        val app = ctx.applicationContext
        states[msg.id]?.let { it.cancelled = true; it.call?.cancel() }
        AppExecutor.io { discard(app, msg) }
    }

    fun failStale(ctx: Context) {
        try {
            val d = dao(ctx)
            for (m in d.getByStatus(MessageEntity.STATUS_UPLOADING)) {
                if (!isActive(m.id)) d.updateStatus(m.id, MessageEntity.STATUS_FAILED)
            }
        } catch (e: Exception) { Log.w(TAG, "failStale: ${e.message}") }
    }

    private fun process(app: Context, t: Target, msg: MessageEntity, uri: Uri?, kind: Kind,
                        keepOriginal: Boolean = false, packed: java.io.File? = null) {
        val st = states[msg.id] ?: return
        val name = msg.attachmentName ?: "plik"
        var temp: java.io.File? = null
        var packedNow: java.io.File? = packed
        try {
            if (st.cancelled) return
            val local = MediaManager.getMediaFile(app, msg.conversationId, msg.id, name)
            val source: java.io.File = if (uri != null) {
                val dest = if (msg.viewOnce) java.io.File(packedDir(app), msg.id + ".tmp").also { temp = it } else local
                val size = sizeOf(app, uri)
                val copied = if (kind == Kind.IMAGE && !keepOriginal && size in 0..IMAGE_PREP_MAX) {
                    val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (raw == null) false else {
                        dest.writeBytes(ImagePrep.forSending(raw) ?: raw)
                        true
                    }
                } else {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.BufferedOutputStream(java.io.FileOutputStream(dest), 256 * 1024).use { input.copyTo(it, 256 * 1024) }
                    } != null
                }
                dropKeyboardCopy(app, uri)
                if (!copied) { dest.delete(); fail(app, msg, app.getString(R.string.cant_read_file)); return }
                if (kind == Kind.VIDEO && !keepOriginal && pl.sinusoide.sinuschat.util.VideoPrep.canStrip(name))
                    pl.sinusoide.sinuschat.util.VideoPrep.stripInPlace(dest) { st.cancelled }
                dest
            } else local
            if (!source.exists()) { fail(app, msg, app.getString(R.string.cant_read_file)); return }

            if (uri != null && !msg.viewOnce) {
                pl.sinusoide.sinuschat.util.VideoThumbs.ensure(app, msg.conversationId, msg.id, name)
                onLocalChanged?.invoke(msg.conversationId)
            }
            if (st.cancelled) return

            val payload: java.io.File = when {
                source.length() <= MAX_BYTES -> source
                packedNow != null && packedNow.exists() -> packedNow
                else -> {
                    val out = java.io.File(packedDir(app), msg.id + ".gz")
                    val ok = java.io.FileInputStream(source).use { input ->
                        pl.sinusoide.sinuschat.util.FileCompression.pack(input, out, MAX_BYTES, isCancelled = { st.cancelled })
                    }
                    if (!ok) { fail(app, msg, app.getString(R.string.file_too_large_150), keep = false); return }
                    packedNow = out
                    out
                }
            }
            if (st.cancelled) return

            val result = FileUploader.uploadEncryptedFileBlocking(app, payload, name, t.recipients,
                onProgress = { st.progress = it }, onCall = { st.call = it }, isCancelled = { st.cancelled })
            if (st.cancelled) return
            if (result == null) {
                Log.w(TAG, "Wysyłka ${msg.id.take(8)}: upload nieudany (${payload.length() / 1024} KB)")
                fail(app, msg, app.getString(R.string.upload_error)); return
            }

            val done = msg.copy(fileId = result.fileId, fileKey = result.fileKey, status = MessageEntity.STATUS_PENDING)
            if (t.isGroup) sendToGroup(app, t, done)
            else MessageRepository.sendNowBlocking(app, done, t.secret ?: run {
                fail(app, msg, app.getString(R.string.no_e2e_key)); return
            }, t.otherHash)
        } catch (e: Exception) {
            Log.e(TAG, "Wysyłka ${msg.id.take(8)}: ${e.message}")
            if (!st.cancelled) fail(app, msg, app.getString(R.string.upload_error))
        } finally {
            states.remove(msg.id)
            temp?.delete()
            packedNow?.delete()
            if (st.cancelled) discard(app, msg)
        }
    }

    private fun sendToGroup(app: Context, t: Target, msg: MessageEntity) {
        val meta = JSONObject()
            .put("fileId", msg.fileId)
            .put("fileKey", msg.fileKey)
            .put("fileName", msg.attachmentName)
        msg.albumId?.let { meta.put("albumId", it) }
        if (msg.text.isNotBlank()) {
            meta.put("caption", msg.text)
            msg.replyToId?.let { meta.put("replyToId", it).put("replyToText", msg.replyToText.orEmpty()) }
        }
        if (!msg.viewOnce) msg.attachmentName?.let { name ->
            pl.sinusoide.sinuschat.util.VideoThumbs.ensure(app, msg.conversationId, msg.id, name)
                ?.let { pl.sinusoide.sinuschat.util.VideoThumbs.putInto(meta, it) }
        }
        val sealed = pl.sinusoide.sinuschat.crypto.GroupKeyManager.sealForGroup(
            app, t.conversationId, meta.toString(), "encAttach")
        if (sealed == null) { fail(app, msg, app.getString(R.string.upload_error)); return }
        val payload = GroupRepository.merge(JSONObject()
            .put("senderHash", t.myHash)
            .put("timestamp", msg.timestampMs), sealed)
        val body = JSONObject()
            .put("conversationId", t.conversationId)
            .put("messageId", msg.id)
            .put("kind", "group_attach")
            .put("payloadB64", Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val resp = pl.sinusoide.sinuschat.network.ApiClient.post("/api/message", body, app)
        val ok = resp != null
        val sentTs = GroupRepository.serverTime(resp) ?: msg.timestampMs
        dao(app).insert(msg.copy(
            status = if (ok) MessageEntity.STATUS_SENT else MessageEntity.STATUS_FAILED,
            timestampMs = sentTs))
        if (ok) MessageRepository.publishRead(app, t.conversationId, t.myHash, sentTs)
        if (ok && msg.text.isNotBlank()) GroupRepository.sendCaptionCompanionBlocking(app, msg.copy(timestampMs = sentTs))
    }

    private fun fail(app: Context, msg: MessageEntity, message: String, keep: Boolean = true) {
        val canRetry = keep && !msg.viewOnce && msg.attachmentName != null &&
            MediaManager.getMediaFile(app, msg.conversationId, msg.id, msg.attachmentName).exists()
        if (canRetry) dao(app).updateStatus(msg.id, MessageEntity.STATUS_FAILED) else discard(app, msg)
        onError?.invoke(msg.conversationId, message)
    }

    private fun discard(app: Context, msg: MessageEntity) {
        try {
            dao(app).deleteById(msg.id)
            MediaManager.deleteMessageMedia(app, msg.conversationId, msg.id)
        } catch (_: Exception) {}
        onLocalChanged?.invoke(msg.conversationId)
    }

    fun keyboardDir(ctx: Context): java.io.File = java.io.File(ctx.cacheDir, "keyboard_media")

    private fun dropKeyboardCopy(ctx: Context, uri: Uri) {
        if (uri.scheme != "file") return
        val file = java.io.File(uri.path ?: return)
        if (file.parentFile?.canonicalPath == keyboardDir(ctx).canonicalPath) file.delete()
    }

    private fun nameFor(ctx: Context, item: Item, ts: Long): String {
        val mime = item.mime ?: try { ctx.contentResolver.getType(item.uri) } catch (_: Exception) { null }.orEmpty()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
        return when (item.kind) {
            Kind.IMAGE -> when {
                mime == "image/png" -> "IMG_$stamp.png"
                mime == "image/gif" -> "IMG_$stamp.gif"
                item.keepOriginal && mime == "image/webp" -> "IMG_$stamp.webp"
                else -> "IMG_$stamp.jpg"
            }
            Kind.VIDEO -> withExtension(displayName(ctx, item.uri) ?: "VID_$stamp", mime, "mp4")
            Kind.FILE -> withExtension(displayName(ctx, item.uri) ?: "plik_$stamp", mime, "bin")
        }
    }

    private fun withExtension(name: String, mime: String, fallback: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        if (clean.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 5 }) return clean
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: fallback
        return "$clean.$ext"
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null
        }
    } catch (_: Exception) { null }

    private fun kindOf(name: String): Kind = when {
        MediaManager.isImage(name) -> Kind.IMAGE
        MediaManager.isVideo(name) -> Kind.VIDEO
        else -> Kind.FILE
    }

    private fun dao(ctx: Context) = SinusChatDatabase.getInstance(ctx).messageDao()
}
