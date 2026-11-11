package pl.sinusoide.sinuschat.network

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.ServerTime
import pl.sinusoide.sinuschat.util.optStringOrNull
import java.util.concurrent.TimeUnit

object ApiClient {

    const val BASE_URL = "https://api.sinusoide.pl"
    private const val PREFS   = "sinuschat_api"
    private const val KEY_JWT = "jwt_token"
    private const val KEY_AUTH_PROOF     = "auth_proof"
    private const val KEY_AUTH_PROOF_EXP = "auth_proof_exp"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val FILES_HOST = "files.sinusoide.pl"
    private const val RELAY_HOST = "api.sinusoide.pl"

    @Volatile private var torClient: Pair<Int, OkHttpClient>? = null

    fun httpClient(context: Context): OkHttpClient {
        if (!PrivacySettings.isTorEnabled(context)) return http
        val torPort = pl.sinusoide.sinuschat.tor.TorManager.socksPort
        torClient?.let { (port, client) -> if (port == torPort) return client }
        val client = OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .proxy(java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", torPort)
            ))
            .addInterceptor { chain -> chain.proceed(viaRelay(chain.request())) }
            .build()
        torClient = torPort to client
        return client
    }

    internal fun viaRelay(req: Request): Request {
        val url = req.url
        if (url.host != FILES_HOST || !url.encodedPath.startsWith("/api/")) return req
        val relayed = url.newBuilder()
            .host(RELAY_HOST)
            .encodedPath("/api/files" + url.encodedPath.removePrefix("/api"))
            .build()
        return req.newBuilder().url(relayed).build()
    }


    private fun fileClient(context: Context): OkHttpClient =
        httpClient(context).newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

    @Volatile private var uploadClientCache: Pair<OkHttpClient, OkHttpClient>? = null
    private const val UPLOAD_SEND_BUFFER = 512 * 1024

    private class SendBufferSocketFactory(private val base: javax.net.SocketFactory, private val size: Int) : javax.net.SocketFactory() {
        private fun tune(s: java.net.Socket): java.net.Socket = s.apply { try { sendBufferSize = size } catch (_: Exception) {} }
        override fun createSocket(): java.net.Socket = tune(base.createSocket())
        override fun createSocket(host: String?, port: Int): java.net.Socket = tune(base.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
            tune(base.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = tune(base.createSocket(host, port))
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
            tune(base.createSocket(address, port, localAddress, localPort))
    }

    private fun uploadClient(context: Context): OkHttpClient {
        val base = httpClient(context)
        uploadClientCache?.let { (b, c) -> if (b === base) return c }
        val client = base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(2, 1, TimeUnit.MINUTES))
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .socketFactory(SendBufferSocketFactory(javax.net.SocketFactory.getDefault(), UPLOAD_SEND_BUFFER))
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        uploadClientCache = base to client
        return client
    }

    fun rawPost(context: Context, url: String, body: ByteArray, contentType: String,
                headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray>? = try {
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        fileClient(context).newCall(req).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    } catch (e: Exception) { null }

    fun rawPostProgress(context: Context, url: String, body: ByteArray, contentType: String,
                        headers: Map<String, String> = emptyMap(),
                        onProgress: (Float) -> Unit, onCall: (okhttp3.Call) -> Unit): Pair<Int, ByteArray>? = try {
        val type = contentType.toMediaType()
        val counting = object : okhttp3.RequestBody() {
            override fun contentType() = type
            override fun contentLength() = body.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                val chunk = 16 * 1024
                var off = 0
                while (off < body.size) {
                    val n = minOf(chunk, body.size - off)
                    sink.write(body, off, n)
                    sink.flush()
                    off += n
                    onProgress(off.toFloat() / body.size)
                }
            }
        }
        val req = Request.Builder().url(url).post(counting)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val call = uploadClient(context).newCall(req)
        onCall(call)
        call.execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    } catch (e: Exception) { null }

    fun rawGetProgress(context: Context, url: String, headers: Map<String, String> = emptyMap(),
                       onProgress: (Float) -> Unit): Pair<Int, ByteArray>? = try {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        fileClient(context).newCall(req).execute().use { resp ->
            val body = resp.body
            if (body == null) resp.code to ByteArray(0) else {
                val total = body.contentLength()
                val out = java.io.ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 64 * 1024)
                val buf = ByteArray(16 * 1024)
                var read = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceAtMost(1f))
                    }
                }
                resp.code to out.toByteArray()
            }
        }
    } catch (e: Exception) { null }

    fun postBody(context: Context, url: String, body: okhttp3.RequestBody,
                 headers: Map<String, String> = emptyMap(), onCall: (okhttp3.Call) -> Unit = {}): Pair<Int, ByteArray>? = try {
        val req = Request.Builder().url(url).post(body)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val call = uploadClient(context).newCall(req)
        onCall(call)
        call.execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    } catch (e: Exception) { Log.w("ApiClient", "POST plik: ${e.javaClass.simpleName}: ${e.message}"); null }

    fun getToFile(context: Context, url: String, headers: Map<String, String>, target: java.io.File,
                  onProgress: ((Float) -> Unit)? = null): Int? = try {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        fileClient(context).newCall(req).execute().use { resp ->
            val body = resp.body
            if (body == null) resp.code else {
                val total = body.contentLength()
                var read = 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    java.io.BufferedOutputStream(java.io.FileOutputStream(target), 256 * 1024).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress?.invoke((read.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                resp.code
            }
        }
    } catch (e: Exception) { Log.w("ApiClient", "GET plik: ${e.javaClass.simpleName}: ${e.message}"); target.delete(); null }

    private const val RANGE_PART = 8L * 1024 * 1024
    private const val RANGE_PARALLEL = 3
    private const val RANGE_TRIES = 5

    @Volatile private var downloadClientCache: Pair<OkHttpClient, OkHttpClient>? = null

    private fun downloadClient(context: Context): OkHttpClient {
        val base = httpClient(context)
        downloadClientCache?.let { (b, c) -> if (b === base) return c }
        val client = base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(RANGE_PARALLEL * 2, 1, TimeUnit.MINUTES))
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        downloadClientCache = base to client
        return client
    }

    private class RangeJob(
        val client: OkHttpClient,
        val url: String,
        val headers: Map<String, String>,
        val channel: java.nio.channels.FileChannel,
        val onBytes: ((Long, Long) -> Unit)?
    ) {
        val done = java.util.concurrent.atomic.AtomicLong(0)
        @Volatile var total = -1L

        fun request(from: Long, to: Long): Request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$from-$to")
            .build()

        fun copy(body: okhttp3.ResponseBody, from: Long, limit: Long): Long {
            var pos = from
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (pos < limit) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - pos).toInt())
                    if (n < 0) break
                    val bb = java.nio.ByteBuffer.wrap(buf, 0, n)
                    var at = pos
                    while (bb.hasRemaining()) at += channel.write(bb, at)
                    pos += n
                    onBytes?.invoke(done.addAndGet(n.toLong()), total)
                }
            }
            return pos - from
        }

        fun part(from: Long, to: Long): Boolean {
            var pos = from
            var failures = 0
            while (pos <= to) {
                val before = pos
                try {
                    client.newCall(request(pos, to)).execute().use { resp ->
                        if (resp.code != 206) throw java.io.IOException("HTTP ${resp.code}")
                        pos += copy(resp.body ?: throw java.io.IOException("brak treści"), pos, to + 1)
                    }
                } catch (e: Exception) {
                    Log.w("ApiClient", "część $from: ${e.javaClass.simpleName}: ${e.message}")
                }
                if (pos > before) failures = 0
                else if (++failures >= RANGE_TRIES) return false
                else Thread.sleep(1000L * failures)
            }
            return true
        }
    }

    fun getRangedToFile(context: Context, url: String, headers: Map<String, String>, target: java.io.File,
                        onBytes: ((Long, Long) -> Unit)? = null): Int? = try {
        target.delete()
        java.io.RandomAccessFile(target, "rw").use { raf ->
            val job = RangeJob(downloadClient(context), url, headers, raf.channel, onBytes)
            var got = 0L
            val code = job.client.newCall(job.request(0, RANGE_PART - 1)).execute().use { resp ->
                val body = resp.body
                when {
                    body == null -> resp.code
                    resp.code == 206 -> {
                        job.total = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            ?: throw java.io.IOException("brak Content-Range")
                        got = job.copy(body, 0, minOf(RANGE_PART, job.total))
                        206
                    }
                    resp.code == 200 -> {
                        job.total = body.contentLength()
                        got = job.copy(body, 0, Long.MAX_VALUE)
                        if (job.total in 0 until Long.MAX_VALUE && got != job.total) throw java.io.IOException("urwane pobieranie")
                        200
                    }
                    else -> resp.code
                }
            }
            if (code != 206) {
                if (code != 200) target.delete()
                return@use code
            }
            val total = job.total
            val starts = generateSequence(got) { it + RANGE_PART }.takeWhile { it < total }.toList()
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val worker = Runnable {
                while (!failed.get()) {
                    val i = next.getAndIncrement()
                    if (i >= starts.size) break
                    val from = starts[i]
                    if (!job.part(from, minOf(from + RANGE_PART, total) - 1)) failed.set(true)
                }
            }
            (1..minOf(RANGE_PARALLEL, starts.size)).map { Thread(worker, "range-download-$it").apply { start() } }
                .forEach { it.join() }
            if (failed.get() || raf.length() != total) {
                target.delete()
                null
            } else 200
        }
    } catch (e: Exception) { Log.w("ApiClient", "GET plik: ${e.javaClass.simpleName}: ${e.message}"); target.delete(); null }

    fun rawGet(context: Context, url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray>? = try {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        fileClient(context).newCall(req).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    } catch (e: Exception) { null }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JWT, null)
            ?.takeIf { it.isNotBlank() }

    fun saveToken(context: Context, token: String) {
        if (token.isBlank()) { clearToken(context); return }
        reverifyHandled = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JWT, token).apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_JWT).apply()
    }

    fun saveAuthProof(context: Context, proof: String, expiresAtMs: Long) {
        if (proof.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_AUTH_PROOF, proof)
            .putLong(KEY_AUTH_PROOF_EXP, expiresAtMs)
            .apply()
    }

    fun getAuthProof(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val proof = p.getString(KEY_AUTH_PROOF, null) ?: return null
        val exp = p.getLong(KEY_AUTH_PROOF_EXP, 0L)
        if (exp <= System.currentTimeMillis()) {
            p.edit().remove(KEY_AUTH_PROOF).remove(KEY_AUTH_PROOF_EXP).apply()
            return null
        }
        return proof
    }

    fun isAuthenticated(context: Context) = getToken(context) != null

    fun autoRegisterIfNeeded(context: Context, onDone: (success: Boolean) -> Unit) {
        if (isAuthenticated(context)) { onDone(true); return }

        val emailHash = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
        if (emailHash.isEmpty()) { onDone(false); return }

        pl.sinusoide.sinuschat.util.AppExecutor.io {
            try {
                if (getAuthProof(context) == null) pl.sinusoide.sinuschat.setup.AnonAccount.refreshProof(context)
                val pubKey = pl.sinusoide.sinuschat.crypto.E2EManager.getMyPublicKey(context)
                    ?: run {
                        if (!pl.sinusoide.sinuschat.crypto.E2EManager.keysMissing(context)) {
                            pl.sinusoide.sinuschat.util.AppExecutor.main { onDone(false) }
                            return@io
                        }
                        pl.sinusoide.sinuschat.util.LinkLog.add(context, "keys", "brak klucza przy rejestracji, tworzę nowy")
                        val kp = pl.sinusoide.sinuschat.crypto.E2EManager.generateKeys(context)
                        android.util.Base64.encodeToString(kp.publicKey.serialize(), android.util.Base64.NO_WRAP)
                    }

                val name = pl.sinusoide.sinuschat.setup.SetupActivity.getUserDisplayName(context)
                val body = org.json.JSONObject().apply {
                    put("emailHash",    emailHash)
                    if (name.isNotBlank()) put("displayName", pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealName(context, name))
                    put("e2ePublicKey", pubKey)
                    put("keyUpdatedAt", pl.sinusoide.sinuschat.crypto.E2EManager.getKeyCreatedAt(context))
                    put("deviceId",     pl.sinusoide.sinuschat.util.DeviceId.get(context))
                    getAuthProof(context)?.let { put("authProof", it) }
                }
                val result = post("/api/register", body, context)
                val token  = result?.optString("token") ?: ""
                if (token.isNotEmpty()) {
                    saveToken(context, token)
                    result?.optStringOrNull("ntfyTopic")?.let {
                        NtfyManager.saveTopic(context, it)
                    }
                    Log.d("ApiClient", "Auto-rejestracja OVH OK")
                    pl.sinusoide.sinuschat.util.ProfileSync.pullMissing(context)
                    pl.sinusoide.sinuschat.util.AppExecutor.main { onDone(true) }
                } else {
                    Log.e("ApiClient", "Auto-rejestracja: brak tokena")
                    pl.sinusoide.sinuschat.util.AppExecutor.main { onDone(false) }
                }
            } catch (e: Exception) {
                Log.e("ApiClient", "Auto-rejestracja błąd", e)
                pl.sinusoide.sinuschat.util.AppExecutor.main { onDone(false) }
            }
        }
    }

    private val USER_PATH = Regex("^/api/user/[a-f0-9]{64}$")

    fun presenceBody(context: Context, hashes: Collection<String>): JSONObject {
        val keys = JSONObject()
        for (h in hashes) {
            try {
                pl.sinusoide.sinuschat.crypto.ProfileCrypto.contactKey(context, h)?.let { pk ->
                    keys.put(h, android.util.Base64.encodeToString(
                        pl.sinusoide.sinuschat.crypto.SealedSender.accessKey(pk), android.util.Base64.NO_WRAP))
                }
            } catch (_: Exception) {}
        }
        return JSONObject().put("hashes", org.json.JSONArray(hashes.toList())).put("keys", keys)
    }

    private fun userAccessKey(context: Context, path: String): String? = try {
        pl.sinusoide.sinuschat.crypto.ProfileCrypto.contactKey(context, path.removePrefix("/api/user/"))?.let { pk ->
            android.util.Base64.encodeToString(
                pl.sinusoide.sinuschat.crypto.SealedSender.accessKey(pk), android.util.Base64.NO_WRAP)
        }
    } catch (_: Exception) { null }

    fun getString(path: String, context: Context? = null, headers: Map<String, String>? = null): String? = try {
        val accessKey = if (context != null && headers?.containsKey("X-Access-Key") != true && USER_PATH.matches(path))
            userAccessKey(context, path) else null
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .get()
            .apply {
                context?.let { header("Authorization", "Bearer ${getToken(it) ?: ""}") }
                headers?.forEach { (k, v) -> header(k, v) }
                accessKey?.let { header("X-Access-Key", it) }
            }
            .build()
        val _client = if (context != null) httpClient(context) else http; _client.newCall(req).execute().use { resp ->
            noteServerClock(resp)
            if (resp.code == 409 && context != null) {
                val body = resp.body?.string()
                if (body != null && body.contains("device_kicked")) { onDeviceKicked(context); return null }
                Log.e("ApiClient", "GET $path → ${resp.code}"); null
            } else if (!resp.isSuccessful) { Log.e("ApiClient", "GET $path → ${resp.code}"); null }
            else resp.body?.string()
        }
    } catch (e: Exception) { Log.e("ApiClient", "GET $path", e); null }

    private fun noteServerClock(resp: okhttp3.Response) {
        try { resp.headers.getDate("Date")?.let { ServerTime.syncCoarse(it.time) } } catch (_: Exception) {}
    }

    fun getObject(path: String, context: Context? = null, headers: Map<String, String>? = null): JSONObject? =
        getString(path, context, headers)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.also { ServerTime.syncFrom(it) }

    fun getArray(path: String, context: Context, headers: Map<String, String>? = null): JSONArray? =
        getString(path, context, headers)?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun post(path: String, body: JSONObject, context: Context? = null): JSONObject? = try {
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .apply { context?.let { header("Authorization", "Bearer ${getToken(it) ?: ""}") } }
            .build()
        val _client = if (context != null) httpClient(context) else http; _client.newCall(req).execute().use { resp ->
            noteServerClock(resp)
            val str = resp.body?.string() ?: return null
            if (resp.code == 409 && str.contains("device_kicked") && context != null) {
                onDeviceKicked(context)
                null
            } else if (resp.code == 403 && str.contains("blocked_by_recipient") && context != null) {
                val who = runCatching { JSONObject(str).optString("blockedBy") }.getOrNull()
                pl.sinusoide.sinuschat.util.BlockList.markBlockedByThem(context, who)
                null
            } else if (resp.code == 410 && str.contains("account_deleted") && context != null) {
                val who = runCatching { JSONObject(str).optString("deleted") }.getOrNull()
                pl.sinusoide.sinuschat.util.BlockList.markDeleted(context, who)
                null
            } else if (resp.code == 401 && str.contains("email_verification_required") && context != null) {
                onEmailVerificationRequired(context)
                null
            } else if (!resp.isSuccessful) { Log.e("ApiClient", "POST $path → ${resp.code}: $str"); null }
            else runCatching { JSONObject(str) }.getOrNull()?.also { ServerTime.syncFrom(it) }
        }
    } catch (e: Exception) { Log.e("ApiClient", "POST $path", e); null }

    @Volatile private var reverifyHandled = false
    fun onEmailVerificationRequired(context: Context) {
        if (reverifyHandled) return
        reverifyHandled = true
        clearToken(context)
        if (pl.sinusoide.sinuschat.setup.AnonAccount.isCurrent(context)) {
            pl.sinusoide.sinuschat.util.AppExecutor.io {
                if (pl.sinusoide.sinuschat.setup.AnonAccount.refreshProof(context)) autoRegisterIfNeeded(context) {}
            }
            return
        }
        Log.w("ApiClient", "Serwer wymaga ponownej weryfikacji e-mail")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val intent = android.content.Intent(
                context.applicationContext, pl.sinusoide.sinuschat.setup.SetupActivity::class.java
            ).apply {
                putExtra(pl.sinusoide.sinuschat.setup.SetupActivity.EXTRA_REVERIFY, true)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.applicationContext.startActivity(intent)
        }
    }

    @Volatile private var kickedHandled = false

    fun resetSession() {
        kickedHandled = false
        reverifyHandled = false
    }
    fun stillActive(context: Context): Boolean? = try {
        val req = Request.Builder().url("$BASE_URL/api/messages/waiting")
            .header("Authorization", "Bearer ${getToken(context) ?: ""}")
            .get().build()
        httpClient(context).newCall(req).execute().use { resp ->
            when {
                resp.code == 409 && resp.body?.string()?.contains("device_kicked") == true -> false
                resp.isSuccessful -> true
                else -> null
            }
        }
    } catch (_: Exception) { null }

    fun onDeviceKicked(context: Context) {
        if (kickedHandled) return
        kickedHandled = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val intent = android.content.Intent(
                context.applicationContext, pl.sinusoide.sinuschat.SessionKickedActivity::class.java
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.applicationContext.startActivity(intent)
        }
    }
}
