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
    private const val BASE_URL = "https://files.sinusoide.pl/api/upload.php"

    data class UploadResult(
        val fileId: String,
        val downloadUrl: String,
        val fileKey: String,
        val fileName: String
    )

    const val MAX_CONTENT = 150L * 1024 * 1024
    private const val MAX_CHUNKED_CIPHER = 160L * 1024 * 1024
    private const val SINGLE_MAX = 40L * 1024 * 1024
    private const val CHUNK = 16L * 1024 * 1024
    private const val CHUNK_TRIES = 3
    private const val CHUNK_TOKEN_MIN_MS = 270_000L
    private const val PARALLEL_PARTS = 2
    private const val MAX_VOICE_CIPHER = 2 * 1024 * 1024 - 16

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

    private fun postJson(context: Context, body: okhttp3.RequestBody, onCall: ((okhttp3.Call) -> Unit)?): JSONObject? {
        val (code, resp) = pl.sinusoide.sinuschat.network.ApiClient.postBody(context, BASE_URL, body,
            mapOf("Accept" to "application/json", "User-Agent" to "SinusChat/5.0"), onCall ?: {}) ?: return null
        NetworkSettings.recordSent(context, body.contentLength().coerceAtLeast(0L))
        val text = resp.toString(Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) { Log.e(TAG, "Upload HTTP $code: ${text.take(200)}"); return null }
        val json = JSONObject(text)
        if (!json.optBoolean("success", false)) { Log.e(TAG, "Upload HTTP $code: ${json.optString("error", "unknown")}"); return null }
        return json
    }

    private fun form(context: Context, action: String, phoneHash: String, minValidMs: Long = 30_000L): okhttp3.MultipartBody.Builder {
        val token = fetchUploadToken(context, minValidMs) ?: throw IOException("brak tokena uploadu")
        return okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("action", action)
            .addFormDataPart("token", token)
            .addFormDataPart("phoneHash", phoneHash)
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
            val phoneHash = SetupActivity.getUserPhoneHash(context)
            val downloads = maxDownloads.coerceIn(1, 500).toString()
            val size = enc.length()
            val json = if (size <= SINGLE_MAX) {
                postJson(context, form(context, "upload", phoneHash)
                    .addFormDataPart("type", "attachment")
                    .addFormDataPart("authHash", auth)
                    .addFormDataPart("maxDownloads", downloads)
                    .addFormDataPart("file", "file.enc", fileBody(enc, 0, size, isCancelled) { sent ->
                        onProgress?.invoke(sent.toFloat() / size)
                    })
                    .build(), onCall)
            } else uploadChunked(context, enc, phoneHash, auth, downloads, onProgress, onCall, isCancelled)
            json ?: return null
            UploadResult(json.getString("fileId"), json.getString("downloadUrl"), fileKey, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "uploadEncryptedFile fail", e)
            null
        } finally {
            enc.delete()
        }
    }

    private fun uploadChunked(context: Context, enc: File, phoneHash: String, auth: String, downloads: String,
                              onProgress: ((Float) -> Unit)?, onCall: ((okhttp3.Call) -> Unit)?,
                              isCancelled: () -> Boolean): JSONObject? {
        val total = enc.length()
        val parts = ((total + CHUNK - 1) / CHUNK).toInt()
        val init = postJson(context, form(context, "chunk_init", phoneHash)
            .addFormDataPart("total", total.toString())
            .addFormDataPart("parts", parts.toString())
            .addFormDataPart("authHash", auth)
            .addFormDataPart("maxDownloads", downloads)
            .build(), onCall) ?: return null
        val uploadId = init.optString("uploadId").takeIf { Regex("[a-f0-9]{32}").matches(it) } ?: return null
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
                if (!uploadPart(context, enc, phoneHash, uploadId, i, parts, sent, report, onCall, stop)) {
                    failed.set(true)
                    break
                }
            }
        }
        val threads = (1..minOf(PARALLEL_PARTS, parts)).map { Thread(worker, "chunk-upload-$it").apply { start() } }
        threads.forEach { it.join() }
        if (failed.get() || isCancelled()) { abortChunked(context, phoneHash, uploadId); return null }
        return postJson(context, form(context, "chunk_finish", phoneHash)
            .addFormDataPart("uploadId", uploadId)
            .build(), onCall)?.also { onProgress?.invoke(1f) }
            ?: run {
                Log.w(TAG, "Składanie pliku z $parts części nie przeszło")
                abortChunked(context, phoneHash, uploadId)
                null
            }
    }

    private fun abortChunked(context: Context, phoneHash: String, uploadId: String) {
        val app = context.applicationContext
        AppExecutor.io {
            try {
                postJson(app, form(app, "chunk_abort", phoneHash).addFormDataPart("uploadId", uploadId).build(), null)
            } catch (_: Exception) {}
        }
    }

    private fun uploadPart(context: Context, enc: File, phoneHash: String, uploadId: String, i: Int, parts: Int,
                           sent: java.util.concurrent.atomic.AtomicLongArray, report: () -> Unit,
                           onCall: ((okhttp3.Call) -> Unit)?, stop: () -> Boolean): Boolean {
        val offset = i * CHUNK
        val len = minOf(CHUNK, enc.length() - offset)
        for (attempt in 1..CHUNK_TRIES) {
            if (stop()) return false
            sent.set(i, 0L)
            report()
            val body = form(context, "chunk", phoneHash, CHUNK_TOKEN_MIN_MS)
                .addFormDataPart("uploadId", uploadId)
                .addFormDataPart("index", i.toString())
                .addFormDataPart("file", "part.bin", fileBody(enc, offset, len, stop) { s ->
                    sent.set(i, s)
                    report()
                })
                .build()
            if (postJson(context, body, onCall) != null) {
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
                              onProgress: ((Float) -> Unit)? = null, onMissing: (() -> Unit)? = null): Boolean {
        val enc = File(workDir(context, "download_enc"), java.util.UUID.randomUUID().toString() + ".enc")
        val dir = target.parentFile ?: return false
        val plain = File(dir, target.name + ".part")
        val unpacked = File(dir, target.name + ".unpack")
        return try {
            val kiv = Base64.decode(fileKey, Base64.NO_WRAP)
            if (kiv.size != 44) return false
            val phoneHash = SetupActivity.getUserPhoneHash(context)
            val token = fetchUploadToken(context)
            if (token == null) { Log.e(TAG, "DOWNLOAD: brak tokena uploadu"); return false }
            val hdrs = mapOf("X-File-Id" to fileId, "X-Upload-Token" to token,
                "X-Account" to phoneHash, "X-File-Auth" to downloadAuth(fileKey))
            var fetched: Int? = null
            for (attempt in 1..3) {
                if (attempt > 1) Thread.sleep(2000L * (attempt - 1))
                fetched = pl.sinusoide.sinuschat.network.ApiClient.getToFile(context, "$BASE_URL?action=download&ack=1", hdrs, enc) { p ->
                    onProgress?.invoke(p * 0.97f)
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
            ackDownload(context, fileId, fileKey, phoneHash)
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

    private fun stripExifMetadata(imageBytes: ByteArray): ByteArray? =
        ImagePrep.forSending(imageBytes)

    fun uploadVoiceBlocking(context: Context, file: java.io.File): Pair<String, String>? {
        return try {
            val token = pl.sinusoide.sinuschat.network.ApiClient.getToken(context) ?: return null
            val bytes = file.readBytes()

            if (bytes.size > 10 * 1024 * 1024) {
                Log.e(TAG, "Głosówka za duża: ${bytes.size / 1024}KB")
                return null
            }
            val (cipher, fileKey) = encryptBytes(bytes, MAX_VOICE_CIPHER) ?: return null

            val (code, body) = pl.sinusoide.sinuschat.network.ApiClient.rawPost(context,
                pl.sinusoide.sinuschat.AppConfig.UPLOAD_VOICE_URL, cipher, "audio/ogg",
                mapOf("Authorization" to "Bearer $token")) ?: return null
            NetworkSettings.recordSent(context, cipher.size.toLong())
            if (code !in 200..299) {
                Log.e(TAG, "Voice upload failed: $code")
                return null
            }
            val url = org.json.JSONObject(body.toString(Charsets.UTF_8)).optStringOrNull("url") ?: return null
            url to fileKey
        } catch (e: Exception) {
            Log.e(TAG, "Voice upload error", e)
            null
        }
    }

    fun downloadVoiceBlocking(context: Context, url: String, fileKey: String?): ByteArray? {
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
            key != null -> pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealAvatar(context, bytes, key)
                ?.let { doUpload(context, it, "group.bin", "group_avatar_enc", "application/octet-stream") }
                ?.optStringOrNull("url")
            else -> {
                val sanitized = stripExifMetadata(bytes) ?: bytes
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                doUpload(context, sanitized, "group.jpg", "group_avatar", mimeType)?.optStringOrNull("url")
            }
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
            val json = doUpload(context, sealed, "avatar.bin", "avatar_enc", "application/octet-stream", phoneHash) ?: return null
            json.optStringOrNull("url")
        } catch (e: Exception) { Log.e(TAG, "Avatar fail", e); null }
    }

    fun downloadAndDecryptAsync(context: Context, fileId: String, fileKey: String, onComplete: (ByteArray?) -> Unit) {
        Thread {
            val r = downloadAndDecrypt(context, fileId, fileKey)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(r) }
        }.start()
    }

    @Volatile private var cachedUploadToken: String? = null
    @Volatile private var cachedTokenExpiry: Long = 0L

    private fun fetchUploadToken(context: Context, minValidMs: Long = 30_000L): String? {
        val now = System.currentTimeMillis()
        cachedUploadToken?.let { if (cachedTokenExpiry - now > minValidMs) return it }
        return try {
            val obj = pl.sinusoide.sinuschat.network.ApiClient.getObject("/api/upload-token", context)
            if (obj == null) { Log.e(TAG, "TOKEN: /api/upload-token zwróciło null (brak sieci/autoryzacji?)"); return null }
            val token = obj.optString("token").takeIf { it.isNotEmpty() }
            val expiry = obj.optLong("expires_at", 0L)
            if (token != null) { cachedUploadToken = token; cachedTokenExpiry = expiry }
            else Log.e(TAG, "TOKEN: odpowiedź bez pola 'token': $obj")
            token
        } catch (e: Exception) { Log.e(TAG, "TOKEN: wyjątek: ${e.message}", e); null }
    }

    private fun downloadAuth(fileKey: String): String =
        pl.sinusoide.sinuschat.crypto.E2EManager.sha256("dl:$fileKey")

    private fun ackDownload(context: Context, fileId: String, fileKey: String, phoneHash: String) {
        for (attempt in 1..3) {
            val token = fetchUploadToken(context) ?: return
            val hdrs = mapOf("X-File-Id" to fileId, "X-Upload-Token" to token,
                "X-Account" to phoneHash, "X-File-Auth" to downloadAuth(fileKey))
            val code = pl.sinusoide.sinuschat.network.ApiClient.postBody(context, "$BASE_URL?action=ack",
                ByteArray(0).toRequestBody(null), hdrs)?.first
            if (code != null && code in 200..299) return
            if (code == 403 || code == 400) { cachedUploadToken = null; if (attempt > 1) return }
            Log.w(TAG, "ACK: próba $attempt nieudana (HTTP $code)")
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

    private fun doUpload(context: Context, data: ByteArray, fileName: String, type: String, contentType: String = "application/octet-stream",
                         authHash: String? = null, maxDownloads: Int = 1,
                         onProgress: ((Float) -> Unit)? = null, onCall: ((okhttp3.Call) -> Unit)? = null): JSONObject? {
        val phoneHash = SetupActivity.getUserPhoneHash(context)
        return doUpload(context, data, fileName, type, contentType, phoneHash, authHash, maxDownloads, onProgress, onCall)
    }

    private fun doUpload(context: Context, data: ByteArray, fileName: String, type: String, contentType: String = "application/octet-stream", phoneHash: String,
                         authHash: String? = null, maxDownloads: Int = 1,
                         onProgress: ((Float) -> Unit)? = null, onCall: ((okhttp3.Call) -> Unit)? = null): JSONObject? {
        return try {
            val token = fetchUploadToken(context) ?: return null
            val boundary = "----SinusChat${System.currentTimeMillis()}"

            Log.d(TAG, "Upload: type=$type, size=${data.size}")

            val baos = ByteArrayOutputStream()
            val out = DataOutputStream(baos)

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"action\"\r\n\r\n")
            out.writeBytes("upload\r\n")

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"type\"\r\n\r\n")
            out.writeBytes("$type\r\n")

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"token\"\r\n\r\n")
            out.writeBytes("$token\r\n")

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"phoneHash\"\r\n\r\n")
            out.writeBytes("$phoneHash\r\n")

            if (authHash != null) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"authHash\"\r\n\r\n")
                out.writeBytes("$authHash\r\n")
            }

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"maxDownloads\"\r\n\r\n")
            out.writeBytes("${maxDownloads.coerceIn(1, 500)}\r\n")

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            out.writeBytes("Content-Type: $contentType\r\n\r\n")
            out.write(data)
            out.writeBytes("\r\n")

            out.writeBytes("--$boundary--\r\n")
            out.flush()

            val requestBytes = baos.toByteArray()
            val ctype = "multipart/form-data; boundary=$boundary"
            val hdrs = mapOf("Accept" to "application/json", "User-Agent" to "SinusChat/5.0")
            val (code, respBytes) = (if (onProgress != null)
                pl.sinusoide.sinuschat.network.ApiClient.rawPostProgress(context, BASE_URL, requestBytes, ctype, hdrs,
                    onProgress, onCall ?: {})
            else pl.sinusoide.sinuschat.network.ApiClient.rawPost(context, BASE_URL, requestBytes, ctype, hdrs)) ?: return null
            NetworkSettings.recordSent(context, requestBytes.size.toLong())
            Log.d(TAG, "Response code: $code")
            val resp = respBytes.toString(Charsets.UTF_8)

            Log.d(TAG, "Response body (first 500): ${resp.take(500)}")

            if (resp.trimStart().startsWith("{")) {
                val json = JSONObject(resp)
                if (json.optBoolean("success", false)) {
                    Log.d(TAG, "Upload SUCCESS: $type")
                    json
                } else {
                    Log.e(TAG, "Upload failed: ${json.optString("error", "unknown")}")
                    null
                }
            } else {
                Log.e(TAG, "Server returned non-JSON: ${resp.take(200)}")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "doUpload exception", e)
            null
        }
    }
}
