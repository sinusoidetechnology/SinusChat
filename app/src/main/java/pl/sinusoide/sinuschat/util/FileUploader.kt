package pl.sinusoide.sinuschat.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object FileUploader {

    private const val TAG = "FileUploader"
    private val FILES_API = pl.sinusoide.sinuschat.network.ApiClient.BASE_URL + "/api"
    private val FILE_ID = Regex("[a-f0-9]{32}")
    private val lastRefusal = ThreadLocal<String?>()

    fun takeRefusal(): String? = lastRefusal.get().also { lastRefusal.remove() }

    data class UploadResult(
        val fileId: String,
        val downloadUrl: String,
        val fileKey: String,
        val fileName: String
    )

    const val MAX_CONTENT = 150L * 1024 * 1024
    const val VOICE_DOWNLOADS = 3
    private const val MAX_CHUNKED_CIPHER = 160L * 1024 * 1024
    private const val SINGLE_MAX = 16L * 1024 * 1024
    private const val CHUNK = 16L * 1024 * 1024
    private const val CHUNK_TRIES = 3
    private const val PARALLEL_PARTS = 2

    private fun workDir(context: Context, name: String): File = File(context.cacheDir, name).apply { mkdirs() }

    private class PaddedSource(private val content: InputStream, private val contentLength: Long, private val total: Long) : InputStream() {
        private val head = FilePadding.header(contentLength)
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= total) return -1
            val headEnd = head.size.toLong()
            val contentEnd = headEnd + contentLength
            return when {
                pos < headEnd -> {
                    val n = minOf(len.toLong(), headEnd - pos).toInt()
                    System.arraycopy(head, pos.toInt(), b, off, n)
                    pos += n; n
                }
                pos < contentEnd -> {
                    val n = content.read(b, off, minOf(len.toLong(), contentEnd - pos).toInt())
                    if (n < 0) throw IOException("plik krótszy niż zapowiadany")
                    pos += n; n
                }
                else -> {
                    val n = minOf(len.toLong(), total - pos).toInt()
                    java.util.Arrays.fill(b, off, off + n, 0)
                    pos += n; n
                }
            }
        }
    }

    private class UnpadSink(private val out: OutputStream) : OutputStream() {
        private val head = ByteArray(FilePadding.HEADER_SIZE)
        private var headLen = 0
        private var remaining = -1L
        private var passthrough = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = off
            var l = len
            if (remaining < 0 && !passthrough) {
                val take = minOf(head.size - headLen, l)
                System.arraycopy(b, p, head, headLen, take)
                headLen += take; p += take; l -= take
                if (headLen < head.size) return
                val n = FilePadding.headerLength(head)
                if (n < 0) { passthrough = true; out.write(head) } else remaining = n
            }
            if (l <= 0) return
            if (passthrough) { out.write(b, p, l); return }
            val w = minOf(remaining, l.toLong()).toInt()
            if (w > 0) { out.write(b, p, w); remaining -= w }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (!passthrough && remaining < 0 && headLen > 0) out.write(head, 0, headLen)
            out.close()
        }
    }

    private fun fileBody(file: File, offset: Long, length: Long, isCancelled: () -> Boolean = { false },
                         onBytes: ((Long) -> Unit)?) = object : okhttp3.RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: okio.BufferedSink) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var left = length
                var sent = 0L
                while (left > 0) {
                    if (isCancelled()) throw IOException("anulowano")
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("koniec pliku")
                    sink.write(buf, 0, n)
                    left -= n; sent += n
                    onBytes?.invoke(sent)
                }
            }
        }
    }

    private fun authHeaders(context: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = pl.sinusoide.sinuschat.network.ApiClient.getToken(context) ?: throw IOException("brak sesji")
        return mapOf("Authorization" to "Bearer $token", "User-Agent" to "SinusChat/5.0") + extra
    }

    private fun jsonBody(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

    private fun call(context: Context, method: String, url: String, body: okhttp3.RequestBody,
                     headers: Map<String, String>, onCall: ((okhttp3.Call) -> Unit)? = null): JSONObject? {
        val (code, resp) = pl.sinusoide.sinuschat.network.ApiClient.sendBody(context, method, url, body,
            headers + ("Accept" to "application/json"), onCall ?: {}) ?: return null
        NetworkSettings.recordSent(context, body.contentLength().coerceAtLeast(0L))
        val text = resp.toString(Charsets.UTF_8)
        val json = if (text.trimStart().startsWith("{")) try { JSONObject(text) } catch (_: Exception) { null } else null
        if (json != null && json.optBoolean("success", false)) return json
        val error = json?.optString("error").orEmpty()
        when {
            code == 507 || error == "storage_full" -> lastRefusal.set("storage_full")
            error == "daily_limit" -> lastRefusal.set("daily_limit")
            code == 413 || error == "too_large" -> lastRefusal.set("too_large")
        }
        Log.e(TAG, "Plik $method HTTP $code: ${error.ifEmpty { text.take(120) }}")
        return null
    }

    fun uploadEncryptedFileBlocking(
        context: Context,
        source: File,
        fileName: String,
        maxDownloads: Int = 1,
        onProgress: ((Float) -> Unit)? = null,
        onCall: ((okhttp3.Call) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): UploadResult? {
        val enc = File(workDir(context, "upload_enc"), java.util.UUID.randomUUID().toString() + ".enc")
        return try {
            val random = SecureRandom()
            val key = ByteArray(32).also { random.nextBytes(it) }
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val n = source.length()
            val total = FilePadding.paddedSize(n + FilePadding.HEADER_SIZE)
                .coerceAtMost(MAX_CHUNKED_CIPHER - 16).coerceAtLeast(n + FilePadding.HEADER_SIZE)
            FileInputStream(source).use { input ->
                BufferedOutputStream(FileOutputStream(enc), 256 * 1024).use { out ->
                    StreamGcm(key, iv).encrypt(PaddedSource(BufferedInputStream(input, 256 * 1024), n, total), out)
                }
            }
            if (isCancelled()) return null
            val fileKey = Base64.encodeToString(key + iv, Base64.NO_WRAP)
            val auth = downloadAuth(fileKey)
            val downloads = maxDownloads.coerceIn(1, 500)
            val size = enc.length()
            lastRefusal.remove()
            val fileId = if (size <= SINGLE_MAX) {
                call(context, "POST", "$FILES_API/attachments", fileBody(enc, 0, size, isCancelled) { sent ->
                    onProgress?.invoke(sent.toFloat() / size)
                }, authHeaders(context, mapOf("X-File-Auth" to auth, "X-Max-Downloads" to downloads.toString())), onCall)
                    ?.optString("fileId")
            } else uploadChunked(context, enc, auth, downloads, onProgress, onCall, isCancelled)
            val id = fileId?.takeIf { FILE_ID.matches(it) } ?: return null
            UploadResult(id, "", fileKey, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "uploadEncryptedFile fail", e)
            null
        } finally {
            enc.delete()
        }
    }

    private fun uploadChunked(context: Context, enc: File, auth: String, downloads: Int,
                              onProgress: ((Float) -> Unit)?, onCall: ((okhttp3.Call) -> Unit)?,
                              isCancelled: () -> Boolean): String? {
        val total = enc.length()
        val parts = ((total + CHUNK - 1) / CHUNK).toInt()
        val init = call(context, "POST", "$FILES_API/attachments/uploads",
            jsonBody(JSONObject().put("total", total).put("parts", parts).put("authHash", auth).put("maxDownloads", downloads)),
            authHeaders(context), onCall) ?: return null
        val uploadId = init.optString("uploadId").takeIf { FILE_ID.matches(it) } ?: return null
        val sent = java.util.concurrent.atomic.AtomicLongArray(parts)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val stop = { failed.get() || isCancelled() }
        val report: () -> Unit = {
            var done = 0L
            for (k in 0 until parts) done += sent.get(k)
            onProgress?.invoke((done.toFloat() / total).coerceAtMost(0.99f))
        }
        val worker = Runnable {
            while (!stop()) {
                val i = next.getAndIncrement()
                if (i >= parts) break
                if (!uploadPart(context, enc, uploadId, i, parts, sent, report, onCall, stop)) {
                    failed.set(true)
                    break
                }
            }
        }
        val threads = (1..minOf(PARALLEL_PARTS, parts)).map { Thread(worker, "chunk-upload-$it").apply { start() } }
        threads.forEach { it.join() }
        if (failed.get() || isCancelled()) { abortChunked(context, uploadId); return null }
        return call(context, "POST", "$FILES_API/attachments/uploads/$uploadId/finish", jsonBody(JSONObject()),
            authHeaders(context), onCall)?.optString("fileId")?.also { onProgress?.invoke(1f) }
            ?: run {
                Log.w(TAG, "Składanie pliku z $parts części nie przeszło")
                abortChunked(context, uploadId)
                null
            }
    }

    private fun abortChunked(context: Context, uploadId: String) {
        val app = context.applicationContext
        AppExecutor.io {
            try {
                call(app, "DELETE", "$FILES_API/attachments/uploads/$uploadId", ByteArray(0).toRequestBody(null), authHeaders(app))
            } catch (_: Exception) {}
        }
    }

    private fun uploadPart(context: Context, enc: File, uploadId: String, i: Int, parts: Int,
                           sent: java.util.concurrent.atomic.AtomicLongArray, report: () -> Unit,
                           onCall: ((okhttp3.Call) -> Unit)?, stop: () -> Boolean): Boolean {
        val offset = i * CHUNK
        val len = minOf(CHUNK, enc.length() - offset)
        for (attempt in 1..CHUNK_TRIES) {
            if (stop()) return false
            sent.set(i, 0L)
            report()
            val body = fileBody(enc, offset, len, stop) { s ->
                sent.set(i, s)
                report()
            }
            if (call(context, "PUT", "$FILES_API/attachments/uploads/$uploadId/$i", body, authHeaders(context), onCall) != null) {
                sent.set(i, len)
                report()
                return true
            }
            Log.w(TAG, "Część ${i + 1}/$parts nie przeszła (próba $attempt/$CHUNK_TRIES)")
            if (stop()) return false
            Thread.sleep(1500L * attempt)
        }
        return false
    }

    fun downloadDecryptToFile(context: Context, fileId: String, fileKey: String, target: File,
                              onProgress: ((Float) -> Unit)? = null, onMissing: (() -> Unit)? = null,
                              onBytes: ((Long, Long) -> Unit)? = null): Boolean {
        val enc = File(workDir(context, "download_enc"), java.util.UUID.randomUUID().toString() + ".enc")
        val dir = target.parentFile ?: return false
        val plain = File(dir, target.name + ".part")
        val unpacked = File(dir, target.name + ".unpack")
        return try {
            val kiv = Base64.decode(fileKey, Base64.NO_WRAP)
            if (kiv.size != 44) return false
            if (!FILE_ID.matches(fileId)) return false
            val hdrs = authHeaders(context, mapOf("X-File-Auth" to downloadAuth(fileKey), "X-Download-Ack" to "1"))
            var fetched: Int? = null
            for (attempt in 1..3) {
                if (attempt > 1) Thread.sleep(2000L * (attempt - 1))
                fetched = pl.sinusoide.sinuschat.network.ApiClient.getRangedToFile(context, "$FILES_API/attachments/$fileId", hdrs, enc) { done, total ->
                    if (total > 0) onProgress?.invoke((done.toFloat() / total).coerceAtMost(1f) * 0.97f)
                    onBytes?.invoke(done, total)
                }
                if (fetched != null) break
            }
            val code = fetched ?: return false
            if (code != 200) {
                Log.e(TAG, "DOWNLOAD: HTTP $code")
                if (code == 404 || code == 410) onMissing?.invoke()
                return false
            }
            NetworkSettings.recordReceived(context, enc.length())
            val ok = FileInputStream(enc).use { input ->
                UnpadSink(BufferedOutputStream(FileOutputStream(plain), 256 * 1024)).use { sink ->
                    StreamGcm(kiv.copyOfRange(0, 32), kiv.copyOfRange(32, 44))
                        .decrypt(BufferedInputStream(input, 256 * 1024), enc.length(), sink)
                }
            }
            if (!ok) { Log.e(TAG, "DOWNLOAD: deszyfrowanie nieudane — zły klucz lub uszkodzony plik"); return false }
            enc.delete()
            val result = if (FileCompression.isPacked(plain)) {
                if (!FileCompression.unpack(plain, unpacked)) return false
                plain.delete()
                unpacked
            } else plain
            target.delete()
            if (!result.renameTo(target)) {
                result.copyTo(target, overwrite = true)
                result.delete()
            }
            ackDownload(context, fileId, fileKey)
            onProgress?.invoke(1f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "DOWNLOAD: wyjątek: ${e.message}", e)
            target.delete()
            false
        } finally {
            enc.delete()
            plain.delete()
            unpacked.delete()
        }
    }

    private fun encryptBytes(plain: ByteArray, maxCipher: Int): Pair<ByteArray, String>? {
        return try {
            val key = ByteArray(32); val iv = ByteArray(12)
            SecureRandom().nextBytes(key); SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            Pair(cipher.doFinal(FilePadding.pad(plain, maxCipher)), Base64.encodeToString(key + iv, Base64.NO_WRAP))
        } catch (e: Exception) { Log.e(TAG, "Encrypt fail", e); null }
    }

    fun decryptBytes(encrypted: ByteArray, fileKey: String): ByteArray? {
        return try {
            val kiv = Base64.decode(fileKey, Base64.NO_WRAP)
            if (kiv.size != 44) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kiv.copyOfRange(0, 32), "AES"),
                GCMParameterSpec(128, kiv.copyOfRange(32, 44)))
            FilePadding.unpad(cipher.doFinal(encrypted))
        } catch (e: Exception) { Log.e(TAG, "Decrypt fail", e); null }
    }

    fun uploadEncryptedBytesBlocking(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        maxDownloads: Int = 1,
        onProgress: ((Float) -> Unit)? = null,
        onCall: ((okhttp3.Call) -> Unit)? = null
    ): UploadResult? {
        val tmp = File(workDir(context, "upload_plain"), java.util.UUID.randomUUID().toString())
        return try {
            tmp.writeBytes(bytes)
            uploadEncryptedFileBlocking(context, tmp, fileName, maxDownloads, onProgress, onCall)
        } catch (e: Exception) {
            Log.e(TAG, "uploadEncryptedBytes fail", e)
            null
        } finally {
            tmp.delete()
        }
    }

    fun uploadVoiceBlocking(context: Context, file: java.io.File, maxDownloads: Int = VOICE_DOWNLOADS): Pair<String, String>? {
        if (file.length() <= 0L || file.length() > MAX_CONTENT) {
            Log.e(TAG, "Głosówka poza limitem: ${file.length() / 1024}KB")
            return null
        }
        val up = uploadEncryptedFileBlocking(context, file, "voice.ogg", maxDownloads) ?: return null
        return up.fileId to up.fileKey
    }

    fun isHostedVoice(source: String): Boolean = !source.startsWith("http")

    fun downloadVoiceToFile(context: Context, source: String, fileKey: String?, target: File): Boolean {
        if (isHostedVoice(source)) {
            if (fileKey.isNullOrEmpty()) return false
            return downloadDecryptToFile(context, source, fileKey, target)
        }
        val bytes = downloadVoiceBlocking(context, source, fileKey) ?: return false
        if (bytes.isEmpty()) return false
        target.writeBytes(bytes)
        return true
    }

    fun downloadVoiceBlocking(context: Context, url: String, fileKey: String?): ByteArray? {
        if (isHostedVoice(url)) return fileKey?.takeIf { it.isNotEmpty() }?.let { downloadAndDecryptBlocking(context, url, it) }
        val token = pl.sinusoide.sinuschat.network.ApiClient.getToken(context) ?: return null
        val (code, bytes) = pl.sinusoide.sinuschat.network.ApiClient.rawGet(context, url,
            mapOf("Authorization" to "Bearer $token")) ?: return null
        if (code !in 200..299 || bytes.isEmpty()) {
            Log.e(TAG, "Pobieranie głosówki nieudane: HTTP $code")
            return null
        }
        NetworkSettings.recordReceived(context, bytes.size.toLong())
        return if (fileKey.isNullOrEmpty()) bytes else decryptBytes(bytes, fileKey)
    }

    fun uploadAvatarAsync(context: Context, uri: Uri, onComplete: (String?) -> Unit) {
        Thread {
            val url = uploadAvatar(context, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(url) }
        }.start()
    }

    fun uploadGroupAvatarAsync(context: Context, uri: Uri, key: ByteArray?, onComplete: (String?) -> Unit) {
        Thread {
            val url = uploadGroupAvatarBlocking(context, uri, key)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(url) }
        }.start()
    }

    fun uploadGroupAvatarBlocking(context: Context, uri: Uri, key: ByteArray?): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        when {
            bytes == null -> null
            key == null -> { Log.e(TAG, "Zdjęcie grupy bez klucza grupy"); null }
            else -> pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealAvatar(context, bytes, key)
                ?.let { uploadSealed(context, "POST", "group-avatars", it) }
        }
    } catch (e: Exception) { Log.e(TAG, "Group avatar fail", e); null }

    fun uploadAvatarAsync(context: Context, uri: Uri, phoneHash: String, onComplete: (String?) -> Unit) {
        Thread {
            val url = uploadAvatar(context, uri, phoneHash)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(url) }
        }.start()
    }

    private fun uploadAvatar(context: Context, uri: Uri): String? =
        uploadAvatar(context, uri, SetupActivity.getUserPhoneHash(context))

    fun uploadAvatarBlocking(context: Context, file: java.io.File): String? =
        uploadAvatar(context, Uri.fromFile(file), SetupActivity.getUserPhoneHash(context))

    private fun uploadAvatar(context: Context, uri: Uri, phoneHash: String): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
            if (bytes == null) { Log.e(TAG, "Cannot read avatar file"); return null }

            val sealed = pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealAvatar(context, bytes) ?: return null
            uploadSealed(context, "PUT", "avatar", sealed)
        } catch (e: Exception) { Log.e(TAG, "Avatar fail", e); null }
    }

    fun downloadAndDecryptAsync(context: Context, fileId: String, fileKey: String, onComplete: (ByteArray?) -> Unit) {
        Thread {
            val r = downloadAndDecrypt(context, fileId, fileKey)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(r) }
        }.start()
    }

    private fun uploadSealed(context: Context, method: String, path: String, sealed: ByteArray): String? {
        lastRefusal.remove()
        return call(context, method, "$FILES_API/$path", sealed.toRequestBody("application/octet-stream".toMediaType()),
            authHeaders(context))?.optStringOrNull("url")
    }

    private fun downloadAuth(fileKey: String): String =
        pl.sinusoide.sinuschat.crypto.E2EManager.sha256("dl:$fileKey")

    private fun ackDownload(context: Context, fileId: String, fileKey: String) {
        for (attempt in 1..3) {
            val ok = try {
                call(context, "POST", "$FILES_API/attachments/$fileId/ack", jsonBody(JSONObject()),
                    authHeaders(context, mapOf("X-File-Auth" to downloadAuth(fileKey)))) != null
            } catch (_: Exception) { false }
            if (ok) return
            Log.w(TAG, "ACK: próba $attempt nieudana")
        }
    }

    fun downloadAndDecryptBlocking(context: Context, fileId: String, fileKey: String,
                                   onProgress: ((Float) -> Unit)? = null): ByteArray? =
        downloadAndDecrypt(context, fileId, fileKey, onProgress)

    private fun downloadAndDecrypt(context: Context, fileId: String, fileKey: String,
                                   onProgress: ((Float) -> Unit)? = null): ByteArray? {
        val tmp = File(workDir(context, "download_plain"), java.util.UUID.randomUUID().toString())
        return try {
            if (downloadDecryptToFile(context, fileId, fileKey, tmp, onProgress)) tmp.readBytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "DOWNLOAD: wyjątek: ${e.message}", e)
            null
        } finally {
            tmp.delete()
        }
    }
}
