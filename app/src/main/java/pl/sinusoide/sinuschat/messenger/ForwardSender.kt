package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.crypto.GroupKeyManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.VoicePlayer
import java.io.File
import java.util.UUID

object ForwardSender {

    private const val TAG = "ForwardSender"
    private const val WORK_DIR = "forward"

    const val MAX_BYTES = FileUploader.MAX_CONTENT

    sealed class Item {
        data class Text(val text: String) : Item()

        data class Media(val uri: Uri, val name: String, val mime: String, val size: Long) : Item() {
            val isImage get() = mime.startsWith("image/")
            val isVideo get() = mime.startsWith("video/")
        }

        data class Voice(val messageId: String, val url: String, val duration: String, val fileKey: String? = null) : Item()
    }

    data class Target(val conversationId: String, val otherHash: String, val isGroup: Boolean, val name: String)

    class Failure(val target: Target, val reason: String)

    class Outcome(val sent: List<Target>, val failed: List<Failure>, val skipped: List<String>)

    fun describe(context: Context, uri: Uri, typeHint: String?): Item.Media {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (_: Exception) {}
        if (name == null) name = uri.lastPathSegment
        val mime = (try { context.contentResolver.getType(uri) } catch (_: Exception) { null })
            ?: typeHint?.takeIf { !it.contains('*') }
            ?: extensionMime(name)
            ?: "application/octet-stream"
        return Item.Media(uri, withExtension(name, mime), mime, size)
    }

    fun chatMedia(file: File, name: String): Item.Media {
        val mime = extensionMime(name) ?: "application/octet-stream"
        return Item.Media(Uri.fromFile(file), withExtension(name, mime), mime, file.length())
    }

    private fun extensionMime(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun withExtension(name: String?, mime: String): String {
        val base = (name ?: "").substringAfterLast('/').trim().ifEmpty { "plik" }
        if (base.contains('.')) return base
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return base
        return "$base.$ext"
    }

    fun sendBlocking(
        context: Context,
        targets: List<Target>,
        items: List<Item>,
        comment: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): Outcome {
        val ctx = context.applicationContext
        val myHash = SetupActivity.getUserPhoneHash(ctx)
        val all = items + listOfNotNull(comment.trim().takeIf { it.isNotEmpty() }?.let { Item.Text(it) })
        val copies = all.count { it is Item.Media }
        val total = (copies + targets.size * all.size).coerceAtLeast(1)
        var done = 0
        fun progress(to: Int) {
            done = to
            AppExecutor.main { onProgress(to, total) }
        }

        val root = File(ctx.cacheDir, WORK_DIR)
        cleanStale(root)
        val work = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val sent = ArrayList<Target>()
        val failed = ArrayList<Failure>()
        val skipped = ArrayList<String>()
        try {
            val ready = ArrayList<Item>()
            for (item in all) {
                if (item !is Item.Media) { ready += item; continue }
                val copy = try { copyForSending(ctx, item, work) } catch (e: Exception) {
                    Log.e(TAG, "Kopia pliku ${item.name} nieudana", e); null
                }
                if (copy == null) skipped += item.name else ready += copy
                progress(done + 1)
            }
            if (ready.isEmpty()) return Outcome(sent, failed, skipped)

            for ((i, t) in targets.withIndex()) {
                val reason = try {
                    if (t.isGroup) sendToGroup(ctx, t, ready, myHash) { progress(done + 1) }
                    else sendToPerson(ctx, t, ready, myHash) { progress(done + 1) }
                } catch (e: Exception) {
                    Log.e(TAG, "Wysyłka do ${t.conversationId} nieudana", e)
                    ctx.getString(R.string.forward_fail_generic)
                }
                if (reason == null) sent += t else failed += Failure(t, reason)
                progress(copies + (i + 1) * all.size)
            }
        } finally {
            work.deleteRecursively()
        }
        return Outcome(sent, failed, skipped)
    }

    private fun sendToPerson(ctx: Context, t: Target, items: List<Item>, myHash: String, step: () -> Unit): String? {
        if (BlockList.isBlocked(ctx, t.otherHash)) return ctx.getString(R.string.forward_fail_blocked)
        val secret = E2EManager.getSharedSecretCached(ctx, t.otherHash)
            ?: E2EManager.fetchAndCacheKey(ctx, t.otherHash)?.let { E2EManager.computeSharedSecret(ctx, it) }
            ?: return ctx.getString(R.string.forward_fail_key)
        for (item in items) {
            val now = System.currentTimeMillis()
            val entity = when (item) {
                is Item.Text -> MessageEntity(
                    id = UUID.randomUUID().toString(), conversationId = t.conversationId, senderHash = myHash,
                    text = item.text, timestampMs = now, type = MessageEntity.TYPE_TEXT)
                is Item.Media -> {
                    val src = sourceFile(item) ?: return ctx.getString(R.string.cant_read_file)
                    val up = FileUploader.uploadEncryptedFileBlocking(ctx, src, item.name, 1)
                        ?: return ctx.getString(R.string.forward_fail_upload)
                    val id = UUID.randomUUID().toString()
                    keepLocalCopy(ctx, src, t.conversationId, id, up.fileName)
                    MessageEntity(
                        id = id, conversationId = t.conversationId, senderHash = myHash, timestampMs = now,
                        type = MessageEntity.TYPE_ATTACHMENT, fileId = up.fileId, fileKey = up.fileKey,
                        attachmentName = up.fileName)
                }
                is Item.Voice -> {
                    val file = voiceFile(ctx, item) ?: return ctx.getString(R.string.forward_fail_voice)
                    val (url, voiceKey) = FileUploader.uploadVoiceBlocking(ctx, file,
                        if (t.isGroup) 50 else FileUploader.VOICE_DOWNLOADS)
                        ?: return ctx.getString(R.string.forward_fail_upload)
                    val id = UUID.randomUUID().toString()
                    try { file.copyTo(MediaManager.voiceCacheFile(ctx, id), overwrite = true) } catch (_: Exception) {}
                    MessageEntity(
                        id = id, conversationId = t.conversationId, senderHash = myHash,
                        timestampMs = now, type = MessageEntity.TYPE_VOICE, fileId = url, fileKey = voiceKey,
                        attachmentName = item.duration)
                }
            }
            MessageRepository.sendNowBlocking(ctx, entity, secret, t.otherHash)
            step()
        }
        return null
    }

    private fun sendToGroup(ctx: Context, t: Target, items: List<Item>, myHash: String, step: () -> Unit): String? {
        val gid = t.conversationId
        val access = GroupRepository.sendAccessBlocking(ctx, gid)
        if (access != null && !access.canWrite) return ctx.getString(R.string.group_cant_write)
        val recipients = access?.memberHashes?.count { it != myHash }?.takeIf { it > 0 }?.coerceAtMost(500) ?: 50
        if (access != null) shareGroupKey(ctx, gid, myHash, access.memberHashes)
        val hadUnread = unreadCount(ctx, gid) > 0

        for (item in items) {
            val ok = when (item) {
                is Item.Text -> GroupRepository.sendTextBlocking(ctx, gid, myHash, item.text)
                is Item.Media -> {
                    val src = sourceFile(item) ?: return ctx.getString(R.string.cant_read_file)
                    val up = FileUploader.uploadEncryptedFileBlocking(ctx, src, item.name, recipients)
                        ?: return ctx.getString(R.string.forward_fail_upload)
                    val id = UUID.randomUUID().toString()
                    keepLocalCopy(ctx, src, gid, id, up.fileName)
                    GroupRepository.sendAttachmentBlocking(ctx, gid, myHash, id, up.fileId, up.fileKey, up.fileName)
                }
                is Item.Voice -> return ctx.getString(R.string.forward_voice_no_groups)
            }
            if (!ok) return ctx.getString(R.string.forward_fail_generic)
            step()
        }
        if (!hadUnread) MessageRepository.publishRead(ctx, gid, myHash, System.currentTimeMillis())
        return null
    }

    private val lastKeyShare = HashMap<String, Long>()

    private fun shareGroupKey(ctx: Context, gid: String, myHash: String, members: List<String>) {
        val now = System.currentTimeMillis()
        synchronized(lastKeyShare) {
            if (now - (lastKeyShare[gid] ?: 0L) < 5 * 60_000L) return
            lastKeyShare[gid] = now
        }
        try { GroupKeyManager.distributeMyKey(ctx, gid, myHash, members) } catch (e: Exception) {
            Log.w(TAG, "Rozesłanie klucza grupy nieudane", e)
        }
    }

    private fun unreadCount(ctx: Context, conversationId: String): Int {
        val arr = ConversationCache.load(ctx) ?: return 0
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optString("id") == conversationId) return j.optInt("unread_count", 0)
        }
        return 0
    }

    private fun sourceFile(item: Item.Media): File? =
        item.uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }?.takeIf { it.exists() }

    private fun keepLocalCopy(ctx: Context, src: File, conversationId: String, id: String, name: String) {
        try {
            src.copyTo(MediaManager.getMediaFile(ctx, conversationId, id, name), overwrite = true)
        } catch (e: Exception) {
            Log.e(TAG, "Kopia lokalna $name nieudana", e)
        }
    }

    private fun voiceFile(ctx: Context, v: Item.Voice): File? {
        val cache = MediaManager.voiceCacheFile(ctx, v.messageId)
        if (cache.exists() && cache.length() > 0) return cache
        return try {
            val bytes = FileUploader.downloadVoiceBlocking(ctx, VoicePlayer.normalizeSource(v.url), v.fileKey)
            if (bytes == null || bytes.isEmpty()) return null
            cache.writeBytes(bytes)
            cache
        } catch (e: Exception) {
            Log.e(TAG, "Pobranie głosówki nieudane", e); null
        }
    }

    private fun copyForSending(ctx: Context, item: Item.Media, dir: File): Item.Media? {
        val ext = item.name.substringAfterLast('.', "dat").filter { it.isLetterOrDigit() }.take(10).ifEmpty { "dat" }
        val out = File(dir, UUID.randomUUID().toString() + "." + ext)
        var copied = 0L
        val input = ctx.contentResolver.openInputStream(item.uri) ?: return null
        input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > MAX_BYTES) { dst.close(); out.delete(); return null }
                    dst.write(buf, 0, n)
                }
            }
        }
        if (item.isVideo && pl.sinusoide.sinuschat.util.VideoPrep.canStrip(item.name))
            pl.sinusoide.sinuschat.util.VideoPrep.stripInPlace(out)
        if (!item.isImage) return item.copy(uri = Uri.fromFile(out), size = out.length())
        return cleanImage(item.copy(uri = Uri.fromFile(out), size = out.length()), out)
    }

    private fun cleanImage(item: Item.Media, file: File): Item.Media {
        val mime = item.mime.lowercase()
        return try {
            when (mime) {
                "image/jpeg", "image/jpg", "image/png", "image/webp" ->
                    if (pl.sinusoide.sinuschat.util.ImagePrep.scrubInPlace(file)) item else toJpeg(item, file) ?: item
                "image/heic", "image/heif" -> toJpeg(item, file) ?: item
                else -> item
            }
        } catch (e: Exception) {
            Log.w(TAG, "Czyszczenie metadanych nieudane, koduję od nowa", e)
            toJpeg(item, file) ?: item
        }
    }

    private fun toJpeg(item: Item.Media, file: File): Item.Media? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("decode")
        val rotation = try { ExifInterface(file.absolutePath).rotationDegrees } catch (_: Exception) { 0 }
        val bmp = if (rotation != 0) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else src
        val out = File(file.parentFile, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        file.delete()
        val name = item.name.substringBeforeLast('.', item.name) + ".jpg"
        item.copy(uri = Uri.fromFile(out), name = name, mime = "image/jpeg", size = out.length())
    } catch (e: Exception) {
        Log.e(TAG, "Zamiana na JPEG nieudana", e); null
    }

    private fun cleanStale(root: File) {
        val old = System.currentTimeMillis() - 24 * 3600_000L
        root.listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
    }
}
