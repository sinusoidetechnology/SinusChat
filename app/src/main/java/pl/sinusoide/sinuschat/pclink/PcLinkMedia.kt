package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import android.util.Log
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.link.LinkConnection
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.messenger.AttachmentDownloader
import pl.sinusoide.sinuschat.messenger.AttachmentSender
import pl.sinusoide.sinuschat.messenger.GroupRepository
import pl.sinusoide.sinuschat.messenger.ReplyLabel
import pl.sinusoide.sinuschat.messenger.ViewOnceManager
import pl.sinusoide.sinuschat.crypto.ProfileCrypto
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.ImagePrep
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.VideoThumbs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object PcLinkMedia {

    private const val TAG = "PcLinkMedia"
    private const val THUMB_SIDE = 480
    private const val VOICE_RATE = 24_000
    private const val AVATAR_SIDE = 256
    private const val VIEW_ONCE_SIDE = 2560
    private const val MAX_PC_VOICE_MS = 30 * 60_000L
    private const val PREPARE_POLL_MS = 700L
    private const val PREPARE_WAIT_MS = 20 * 60_000L

    private class Upload(
        val req: String,
        val batch: String,
        val name: String,
        val mime: String,
        val size: Long,
        val file: File,
        val out: FileOutputStream,
        val viewOnce: Boolean,
        val original: Boolean
    ) {
        var received = 0L
        var nextSeq = 0
        var done = false
        var failed = false
    }

    private val requests = Executors.newFixedThreadPool(2)
    private val thumbs = Executors.newSingleThreadExecutor()
    private val later = Executors.newSingleThreadScheduledExecutor()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    private val uploads = ConcurrentHashMap<String, Upload>()

    fun hasThumb(ctx: Context, e: MessageEntity, kind: String): Boolean = when (kind) {
        pl.sinusoide.sinuschat.link.ChatMessage.KIND_PHOTO -> true
        pl.sinusoide.sinuschat.link.ChatMessage.KIND_VIDEO -> VideoThumbs.load(ctx, e.conversationId, e.id)?.jpeg != null ||
            MediaManager.isDownloaded(ctx, e.conversationId, e.id, e.attachmentName.orEmpty())
        else -> false
    }

    fun sizeOf(ctx: Context, e: MessageEntity): Long {
        val name = e.attachmentName ?: return 0L
        if (e.type == MessageEntity.TYPE_VOICE) return 0L
        val local = MediaManager.getMediaFile(ctx, e.conversationId, e.id, name)
        if (local.exists()) return local.length()
        return VideoThumbs.load(ctx, e.conversationId, e.id)?.sizeBytes ?: 0L
    }

    fun durationOf(ctx: Context, e: MessageEntity): Long = when {
        e.type == MessageEntity.TYPE_VOICE ->
            (e.attachmentName?.removeSuffix("s")?.toLongOrNull() ?: 0L) * 1000L
        MediaManager.isVideo(e.attachmentName.orEmpty()) -> VideoThumbs.load(ctx, e.conversationId, e.id)?.durationMs ?: 0L
        else -> 0L
    }

    fun request(ctx: Context, conn: LinkConnection, f: LinkFrame.MediaRequest) {
        val flag = AtomicBoolean(false)
        cancelled[f.req] = flag
        dispatch(ctx, conn, f, flag, System.currentTimeMillis())
    }

    private fun dispatch(ctx: Context, conn: LinkConnection, f: LinkFrame.MediaRequest, flag: AtomicBoolean, since: Long) {
        if (flag.get()) { cancelled.remove(f.req); return }
        if (f.kind != LinkFrame.MEDIA_AVATAR && AttachmentSender.isPreparing(f.msg) &&
            System.currentTimeMillis() - since < PREPARE_WAIT_MS) {
            later.schedule({ dispatch(ctx, conn, f, flag, since) }, PREPARE_POLL_MS, TimeUnit.MILLISECONDS)
            return
        }
        val pool = if (f.kind == LinkFrame.MEDIA_THUMB) thumbs else requests
        pool.execute {
            try {
                serve(ctx, conn, f, flag)
            } catch (e: Exception) {
                Log.w(TAG, "media ${f.kind}: ${e.message}")
                conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_FAILED))
            } finally {
                cancelled.remove(f.req)
            }
        }
    }

    fun cancel(req: String) {
        cancelled[req]?.set(true)
    }

    private fun serve(ctx: Context, conn: LinkConnection, f: LinkFrame.MediaRequest, flag: AtomicBoolean) {
        if (f.kind == LinkFrame.MEDIA_VIEW_ONCE) {
            serveViewOnce(ctx, conn, f, flag)
            return
        }
        if (f.kind == LinkFrame.MEDIA_AVATAR) {
            val jpeg = avatarJpeg(ctx, f.msg) ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE)); return }
            streamBytes(conn, f.req, "avatar.jpg", "image/jpeg", jpeg, flag)
            return
        }
        val msg = SinusChatDatabase.getInstance(ctx).messageDao().getById(f.msg)
        if (msg == null || msg.conversationId != f.conv || msg.viewOnce) {
            conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE))
            return
        }
        when (f.kind) {
            LinkFrame.MEDIA_THUMB -> {
                val jpeg = thumbnail(ctx, msg) ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE)); return }
                streamBytes(conn, f.req, "thumb.jpg", "image/jpeg", jpeg, flag)
            }
            LinkFrame.MEDIA_VOICE -> {
                val wav = voiceWav(ctx, msg) ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE)); return }
                streamBytes(conn, f.req, "voice.wav", "audio/wav", wav, flag)
            }
            else -> {
                val file = localFile(ctx, msg) ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE)); return }
                val name = msg.attachmentName ?: file.name
                streamFile(conn, f.req, name, mimeOf(name), file, flag)
            }
        }
    }

    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun localFile(ctx: Context, msg: MessageEntity): File? {
        val name = msg.attachmentName ?: return null
        val local = MediaManager.getMediaFile(ctx, msg.conversationId, msg.id, name)
        if (local.exists() && local.length() > 0) return local
        val result = AtomicReference<File?>(null)
        val latch = CountDownLatch(1)
        AttachmentDownloader.fetch(ctx, msg) { file ->
            result.set(file)
            latch.countDown()
        }
        latch.await(10, TimeUnit.MINUTES)
        return result.get()
    }

    private fun thumbnail(ctx: Context, msg: MessageEntity): ByteArray? {
        val name = msg.attachmentName.orEmpty()
        if (MediaManager.isVideo(name)) {
            VideoThumbs.ensure(ctx, msg.conversationId, msg.id, name)
            val info = VideoThumbs.load(ctx, msg.conversationId, msg.id) ?: return null
            val jpeg = info.jpeg ?: return null
            val bmp = ImagePrep.decodeSampled(jpeg, THUMB_SIDE) ?: return jpeg
            return compress(bmp)
        }
        if (!MediaManager.isImage(name)) return null
        val file = localFile(ctx, msg) ?: return null
        val bmp = ImagePrep.decodeSampledFile(file.absolutePath, THUMB_SIDE) ?: return null
        return compress(bmp)
    }

    private val openingOnce = ConcurrentHashMap.newKeySet<String>()

    private fun serveViewOnce(ctx: Context, conn: LinkConnection, f: LinkFrame.MediaRequest, flag: AtomicBoolean) {
        val msg = SinusChatDatabase.getInstance(ctx).messageDao().getById(f.msg)
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx)
        val name = msg?.attachmentName.orEmpty()
        val fileId = msg?.fileId
        val fileKey = msg?.fileKey
        when {
            msg == null || msg.conversationId != f.conv || !msg.viewOnce || msg.senderHash == me ||
                fileId.isNullOrEmpty() || fileKey.isNullOrEmpty() || !MediaManager.isImage(name) -> {
                conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_GONE)); return
            }
            msg.viewed -> { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_OPENED)); return }
        }
        if (!openingOnce.add(msg!!.id)) { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_OPENED)); return }
        try {
            val bytes = FileUploader.downloadAndDecryptBlocking(ctx, fileId!!, fileKey!!)
                ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_FAILED)); return }
            val bmp = ImagePrep.decodeSampled(bytes, VIEW_ONCE_SIDE)
                ?: run { conn.send(LinkFrame.MediaError(f.req, LinkFrame.ERROR_FAILED)); return }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (!streamBytes(conn, f.req, "view_once.jpg", "image/jpeg", out.toByteArray(), flag)) return
            ViewOnceManager.markOpenedLocally(ctx, msg.conversationId, msg.id)
            ViewOnceManager.notifySenderOpened(ctx, msg.conversationId, msg.id)
        } finally {
            openingOnce.remove(msg.id)
        }
    }

    private fun avatarJpeg(ctx: Context, key: String): ByteArray? {
        if (key.isEmpty()) return null
        val own = key == LinkFrame.AVATAR_ME
        val model: Any = if (own) {
            PcLinkProfile.avatarFile(ctx) ?: return null
        } else {
            val raw = ContactCache.storedAvatarUrl(ctx, key)?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            if (ProfileCrypto.isEncryptedAvatar(raw)) {
                ProfileCrypto.fetchAvatar(ctx, key, raw) ?: run {
                    ProfileCrypto.displayAvatar(ctx, key, raw)
                    return null
                }
            } else raw
        }
        val bmp = try {
            com.bumptech.glide.Glide.with(ctx.applicationContext).asBitmap().load(model).centerCrop()
                .skipMemoryCache(own)
                .diskCacheStrategy(if (own) com.bumptech.glide.load.engine.DiskCacheStrategy.NONE else com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                .submit(AVATAR_SIDE, AVATAR_SIDE).get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "avatar: ${e.message}")
            return null
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun compress(src: Bitmap): ByteArray {
        val scale = minOf(1f, THUMB_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    private fun voiceWav(ctx: Context, msg: MessageEntity): ByteArray? {
        if (durationOf(ctx, msg) > MAX_PC_VOICE_MS) return null
        val cache = MediaManager.voiceCacheFile(ctx, msg.id)
        if (!cache.exists() || cache.length() == 0L) {
            val url = msg.fileId ?: return null
            val part = File(cache.parentFile, cache.name + ".part")
            if (!FileUploader.downloadVoiceToFile(ctx,
                    pl.sinusoide.sinuschat.util.VoicePlayer.normalizeSource(url), msg.fileKey, part)) { part.delete(); return null }
            if (!part.renameTo(cache)) { part.copyTo(cache, overwrite = true); part.delete() }
        }
        return decodeToWav(cache)
    }

    fun decodeToWav(file: File): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 500) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: continue
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        idle = 0
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(chunk, 0, info.size)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> idle++
                }
            }
            val mono = downmix(pcm.toByteArray(), channels)
            val resampled = resample(mono, rate, VOICE_RATE)
            return wav(resampled, VOICE_RATE)
        } catch (e: Exception) {
            Log.w(TAG, "voice decode: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun downmix(pcm: ByteArray, channels: Int): ShortArray {
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ch = channels.coerceAtLeast(1)
        val frames = src.remaining() / ch
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) sum += src.get(i * ch + c)
            out[i] = (sum / ch).toShort()
        }
        return out
    }

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * step
            val a = pos.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = pos - a
            out[i] = (src[a] * (1 - t) + src[b] * t).toInt().toShort()
        }
        return out
    }

    private fun wav(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    private fun streamBytes(conn: LinkConnection, req: String, name: String, mime: String, bytes: ByteArray, flag: AtomicBoolean): Boolean =
        stream(conn, req, name, mime, bytes.size.toLong(), bytes.inputStream(), flag)

    private fun streamFile(conn: LinkConnection, req: String, name: String, mime: String, file: File, flag: AtomicBoolean) {
        file.inputStream().use { stream(conn, req, name, mime, file.length(), it, flag) }
    }

    private fun stream(conn: LinkConnection, req: String, name: String, mime: String, size: Long, input: InputStream, flag: AtomicBoolean): Boolean {
        if (!conn.send(LinkFrame.MediaStart(req, name, mime, size))) return false
        val buf = ByteArray(LinkFrame.CHUNK_BYTES)
        var seq = 0
        while (true) {
            if (flag.get() || !conn.isOpen) return false
            val n = readFully(input, buf)
            if (n <= 0) break
            val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
            if (!conn.send(LinkFrame.MediaChunk(req, seq++, data))) return false
            if (n < buf.size) break
        }
        return conn.send(LinkFrame.MediaEnd(req))
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun uploadDir(ctx: Context): File = AttachmentSender.keyboardDir(ctx).apply { mkdirs() }

    fun uploadStart(ctx: Context, f: LinkFrame.UploadStart) {
        uploads.remove(f.req)?.let { discard(it) }
        if (f.size !in 1..LinkFrame.MAX_UPLOAD_BYTES) return
        val clean = f.name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().ifEmpty { "plik" }
        val ext = clean.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val file = File(uploadDir(ctx), "pc_" + java.util.UUID.randomUUID().toString() + "." + ext)
        uploads[f.req] = Upload(f.req, f.batch, clean.take(120), f.mime, f.size, file, FileOutputStream(file), f.viewOnce, f.original)
    }

    fun uploadChunk(f: LinkFrame.UploadChunk) {
        val u = uploads[f.req] ?: return
        if (u.failed || u.done) return
        try {
            if (f.seq != u.nextSeq) throw IllegalStateException("seq")
            val bytes = Base64.decode(f.data, Base64.NO_WRAP)
            u.received += bytes.size
            if (u.received > u.size) throw IllegalStateException("size")
            u.out.write(bytes)
            u.nextSeq++
        } catch (e: Exception) {
            u.failed = true
            runCatching { u.out.close() }
        }
    }

    fun uploadEnd(f: LinkFrame.UploadEnd) {
        val u = uploads[f.req] ?: return
        runCatching { u.out.close() }
        u.done = !u.failed && u.received == u.size
        if (!u.done) u.failed = true
    }

    fun takeStaged(req: String, maxBytes: Long): File? {
        val u = uploads.remove(req) ?: return null
        if (!u.done || u.size > maxBytes) {
            discard(u)
            return null
        }
        return u.file
    }

    fun uploadCancel(batch: String) {
        uploads.values.filter { it.batch == batch }.forEach {
            uploads.remove(it.req)
            discard(it)
        }
    }

    fun dropAll() {
        uploads.values.forEach { discard(it) }
        uploads.clear()
    }

    private fun discard(u: Upload) {
        runCatching { u.out.close() }
        u.file.delete()
    }

    fun commit(
        ctx: Context,
        f: LinkFrame.UploadCommit,
        me: String,
        target: (conv: String) -> Pair<AttachmentSender.Target?, String?>
    ): LinkFrame.UploadResult {
        val parts = f.reqs.mapNotNull { uploads[it] }
        if (parts.size != f.reqs.size || parts.any { !it.done || it.batch != f.batch }) {
            uploadCancel(f.batch)
            return LinkFrame.UploadResult(f.batch, false, LinkFrame.ERROR_FAILED)
        }
        val (t, error) = target(f.conv)
        if (t == null) {
            uploadCancel(f.batch)
            return LinkFrame.UploadResult(f.batch, false, error ?: LinkFrame.ERROR_FAILED)
        }
        parts.forEach { uploads.remove(it.req) }
        val items = parts.map { u ->
            val kind = when {
                MediaManager.isImage(u.name) -> AttachmentSender.Kind.IMAGE
                MediaManager.isVideo(u.name) -> AttachmentSender.Kind.VIDEO
                else -> AttachmentSender.Kind.FILE
            }
            val media = kind != AttachmentSender.Kind.FILE
            AttachmentSender.Item(Uri.fromFile(u.file), kind, viewOnce = media && u.viewOnce && !t.isGroup,
                mime = u.mime.ifBlank { mimeOf(u.name) }, name = u.name, original = media && u.original)
        }
        val reply = f.replyTo?.let { SinusChatDatabase.getInstance(ctx).messageDao().getById(it) }
            ?.takeIf { it.conversationId == f.conv }
        val caption = if (f.caption.isNotBlank() || reply != null) {
            val replyId = reply?.id?.let { id -> if (GroupRepository.isGroup(f.conv)) id.removeSuffix("_" + me.take(16)) else id }
            AttachmentSender.Caption(f.caption.trim().take(8_000), replyId, reply?.let { ReplyLabel.of(ctx, it) })
        } else null
        val ids = AttachmentSender.send(ctx, t, items, caption)
        return LinkFrame.UploadResult(f.batch, true, ids = ids)
    }
}
