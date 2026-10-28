package pl.sinusoide.sinuschat.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.Request
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.ContactCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProfileCrypto {

    private const val TAG = "ProfileCrypto"
    private const val PREFS = "profile_keys"
    private const val NAME_PREFIX = "p1:"
    private val AVATAR_MAGIC = byteArrayOf('S'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val AVATAR_DIR = "avatars_plain"
    private const val AVATAR_MAX_PX = 512

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    @Synchronized
    fun myKey(ctx: Context): ByteArray {
        val p = prefs(ctx)
        p.getString("me", null)?.let { s -> Base64.decode(s, Base64.NO_WRAP).takeIf { it.size == 32 }?.let { return it } }
        val key = anonKey(ctx) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString("me", b64(key)).commit()
        return key
    }

    private fun anonKey(ctx: Context): ByteArray? {
        if (!pl.sinusoide.sinuschat.setup.AnonAccount.isCurrent(ctx) &&
            !pl.sinusoide.sinuschat.setup.AnonAccount.creating) return null
        return pl.sinusoide.sinuschat.util.BackupPrefs.recoveryKey(ctx)?.proof("SinusChat profile key v1", ByteArray(0))
    }

    fun myKeyB64(ctx: Context): String = b64(myKey(ctx))

    private fun wrapKey(rk: pl.sinusoide.sinuschat.util.RecoveryKey): ByteArray =
        rk.proof("SinusChat profile key wrap v1", ByteArray(0))

    private fun anonRecoveryKey(ctx: Context): pl.sinusoide.sinuschat.util.RecoveryKey? {
        if (!pl.sinusoide.sinuschat.setup.AnonAccount.isCurrent(ctx)) return null
        val rk = pl.sinusoide.sinuschat.util.BackupPrefs.recoveryKey(ctx) ?: return null
        return rk.takeIf { pl.sinusoide.sinuschat.setup.AnonAccount.accountHash(it) == SetupActivity.getUserPhoneHash(ctx) }
    }

    fun useAnonKey(ctx: Context, rk: pl.sinusoide.sinuschat.util.RecoveryKey) {
        setMyKey(ctx, b64(rk.proof("SinusChat profile key v1", ByteArray(0))))
    }

    fun restoreForAnon(ctx: Context, rk: pl.sinusoide.sinuschat.util.RecoveryKey) {
        val app = ctx.applicationContext
        val hash = pl.sinusoide.sinuschat.setup.AnonAccount.accountHash(rk)
        val proof = ApiClient.getAuthProof(app)
        val wrapped = try {
            ApiClient.getObject("/api/user/$hash", app, proof?.let { mapOf("X-Auth-Proof" to it) })
                ?.optString("profile_key_wrap")?.takeIf { it.isNotEmpty() && it != "null" }
        } catch (_: Exception) { null }
        val key = wrapped?.let { E2EManager.decrypt(it, wrapKey(rk)) }
        if (key != null) setMyKey(app, key) else useAnonKey(app, rk)
    }

    fun setMyKey(ctx: Context, keyB64: String) {
        val k = try { Base64.decode(keyB64, Base64.NO_WRAP) } catch (_: Exception) { return }
        if (k.size == 32) prefs(ctx).edit().putString("me", keyB64).remove("synced").commit()
    }

    private fun myPrevKey(ctx: Context): ByteArray? = try {
        prefs(ctx).getString("me_prev", null)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == 32 }
    } catch (_: Exception) { null }

    fun rotate(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        synchronized(this) {
            val old = p.getString("me", null)
            p.edit().putString("me", b64(key)).putString("me_prev", old).remove("synced")
                .putBoolean("push", true).commit()
        }
        ensureSynced(app)
    }

    const val KIND_KEY = "pkey"

    private val pushing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun pushNewKey(app: Context) {
        val p = prefs(app)
        if (!p.getBoolean("push", false) || p.getString("synced", null) != myKeyB64(app)) return
        val me = SetupActivity.getUserPhoneHash(app).ifEmpty { return }
        if (!pushing.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val key = myKeyB64(app)
                    val gen = p.getLong("push_gen", 0L)
                    if (p.getString("synced", null) != key) break
                    val peers = (pl.sinusoide.sinuschat.util.LocalChats.contacts(app, me) +
                            pl.sinusoide.sinuschat.util.GroupRoster.allMembers(app))
                        .distinct()
                        .filter { it.isNotEmpty() && it != me && !pl.sinusoide.sinuschat.util.BlockList.isCutOff(app, it) }
                    val rnd = SecureRandom()
                    for (peer in peers) {
                        pl.sinusoide.sinuschat.messenger.MessageRepository.sendProfileKeyBlocking(app, me, peer)
                        Thread.sleep(300L + rnd.nextInt(900))
                    }
                    Log.d(TAG, "Nowy klucz profilu wysłany do ${peers.size} osób")
                    if (myKeyB64(app) == key && p.getLong("push_gen", 0L) == gen) { p.edit().remove("push").apply(); break }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Rozsyłanie klucza profilu: ${e.message}")
            } finally {
                pushing.set(false)
            }
        }, "profile-key-push").start()
    }

    fun announceProfileChange(ctx: Context) {
        val app = ctx.applicationContext
        try {
            synchronized(this) {
                val p = prefs(app)
                p.edit().putBoolean("push", true).putLong("push_gen", p.getLong("push_gen", 0L) + 1).commit()
            }
        } catch (_: Exception) { return }
        AppExecutor.io { pushNewKey(app) }
    }

    fun onKeyUpdate(ctx: Context, sender: String, payload: org.json.JSONObject) {
        if (!SignalProtocol.isV2(payload)) return
        val r = SignalProtocol.decrypt(ctx, sender, payload) as? SignalProtocol.Opened.Ok ?: return
        val upk = runCatching { org.json.JSONObject(r.bytes.toString(Charsets.UTF_8)).optString("upk") }.getOrNull()
        if (!upk.isNullOrEmpty()) receiveKey(ctx, sender, upk, refreshIfSame = true)
    }

    private val lastRefresh = HashMap<String, Long>()
    private val deferredRefresh = HashSet<String>()
    private const val REFRESH_GAP_MS = 30_000L

    fun ensureSynced(ctx: Context) {
        val app = ctx.applicationContext
        val me = SetupActivity.getUserPhoneHash(app)
        if (me.isEmpty() || !ApiClient.isAuthenticated(app)) return
        val key = myKey(app)
        val keyB64 = b64(key)
        val p = prefs(app)
        val anonRk = anonRecoveryKey(app)
        val wrap = anonRk?.let { E2EManager.encrypt(keyB64, wrapKey(it)) }
        if (p.getString("synced", null) == keyB64) {
            if (wrap != null && p.getString("wrapped", null) != keyB64 &&
                ApiClient.post("/api/user/update", org.json.JSONObject().put("profileKeyWrap", wrap), app) != null) {
                p.edit().putString("wrapped", keyB64).apply()
            }
            pushNewKey(app)
            return
        }
        val body = org.json.JSONObject().put("accessKey", b64(SealedSender.accessKey(key)))
        wrap?.let { body.put("profileKeyWrap", it) }
        SetupActivity.getUserDisplayName(app).takeIf { it.isNotBlank() }?.let { body.put("displayName", sealName(app, it)) }
        ApiClient.post("/api/user/update", body, app) ?: return
        pl.sinusoide.sinuschat.util.AvatarHelper.getLocalAvatarPath(app)?.let { path ->
            val url = pl.sinusoide.sinuschat.util.FileUploader.uploadAvatarBlocking(app, File(path)) ?: return
            val versioned = pl.sinusoide.sinuschat.util.AvatarHelper.versionedUrl(url)
            ApiClient.post("/api/user/update", org.json.JSONObject()
                .put("avatarUrl", versioned)
                .put("avatarUpdatedAt", System.currentTimeMillis()), app) ?: return
            ContactCache.saveAvatarUrl(app, me, versioned)
            synchronized(this) {
                p.edit().putBoolean("push", true).putLong("push_gen", p.getLong("push_gen", 0L) + 1).commit()
            }
        }
        p.edit().putString("synced", keyB64).apply { if (wrap != null) putString("wrapped", keyB64) }.apply()
        pushNewKey(app)
    }

    fun linkFragment(ctx: Context): String =
        "#p=" + Base64.encodeToString(myKey(ctx), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun keyFromFragment(token: String?): String? {
        val v = token?.substringAfter('#', "")?.substringAfter("p=", "")?.substringBefore('&')
            ?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Base64.decode(v, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .takeIf { it.size == 32 }?.let { b64(it) }
        } catch (_: Exception) { null }
    }

    fun openNameWithKey(ctx: Context, hash: String, raw: String?, keyB64: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        if (!raw.startsWith(NAME_PREFIX)) return raw
        val key = keyB64?.let { k -> try { Base64.decode(k, Base64.NO_WRAP) } catch (_: Exception) { null } }
            ?.takeIf { it.size == 32 } ?: contactKey(ctx, hash) ?: return null
        return E2EManager.decrypt(raw.removePrefix(NAME_PREFIX), key)?.takeIf { it.isNotBlank() }
    }

    fun isSealedName(raw: String?): Boolean = raw?.startsWith(NAME_PREFIX) == true

    fun contactKey(ctx: Context, hash: String): ByteArray? = try {
        prefs(ctx).getString("c_$hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == 32 }
    } catch (_: Exception) { null }

    fun receiveKey(ctx: Context, hash: String, keyB64: String?, refreshIfSame: Boolean = false) {
        if (keyB64.isNullOrEmpty() || hash.isEmpty() || hash == SetupActivity.getUserPhoneHash(ctx)) return
        val k = try { Base64.decode(keyB64, Base64.NO_WRAP) } catch (_: Exception) { return }
        if (k.size != 32) return
        val old = try { prefs(ctx).getString("c_$hash", null) } catch (_: Exception) { null }
        if (old == keyB64) {
            if (!refreshIfSame) return
            val app = ctx.applicationContext
            val now = System.currentTimeMillis()
            val wait = synchronized(lastRefresh) {
                val since = now - (lastRefresh[hash] ?: 0L)
                when {
                    since >= REFRESH_GAP_MS -> { lastRefresh[hash] = now; 0L }
                    deferredRefresh.add(hash) -> REFRESH_GAP_MS - since
                    else -> -1L
                }
            }
            when {
                wait == 0L -> AppExecutor.io { refreshContact(app, hash) }
                wait > 0L -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    synchronized(lastRefresh) {
                        deferredRefresh.remove(hash)
                        lastRefresh[hash] = System.currentTimeMillis()
                    }
                    AppExecutor.io { refreshContact(app, hash) }
                }, wait)
            }
            return
        }
        try { prefs(ctx).edit().putString("c_$hash", keyB64).apply() } catch (_: Exception) { return }
        SealedSender.forgetRefusal(ctx, hash)
        AppExecutor.io { refreshContact(ctx.applicationContext, hash) }
    }

    fun exportContactKeys(ctx: Context): org.json.JSONObject {
        val out = org.json.JSONObject()
        try {
            for ((k, v) in prefs(ctx).all) {
                if (k.startsWith("c_") && v is String) out.put(k.removePrefix("c_"), v)
            }
        } catch (_: Exception) {}
        return out
    }

    fun importContactKeys(ctx: Context, keys: org.json.JSONObject) {
        val app = ctx.applicationContext
        val added = ArrayList<String>()
        try {
            val p = prefs(app)
            val e = p.edit()
            for (hash in keys.keys()) {
                val k = keys.optString(hash)
                val raw = try { Base64.decode(k, Base64.NO_WRAP) } catch (_: Exception) { continue }
                if (raw.size != 32 || p.getString("c_$hash", null) != null) continue
                e.putString("c_$hash", k)
                added.add(hash)
            }
            e.commit()
        } catch (e: Exception) { Log.w(TAG, "Klucze kontaktów z kopii: ${e.message}"); return }
        if (added.isEmpty()) return
        synchronized(tried) { tried.clear() }
        AppExecutor.io { for (hash in added) refreshContact(app, hash) }
    }

    private const val ASK_EVERY_MS = 24L * 3600_000L

    fun askForKey(ctx: Context, hash: String) {
        val app = ctx.applicationContext
        val me = SetupActivity.getUserPhoneHash(app)
        if (hash.isEmpty() || me.isEmpty() || hash == me || contactKey(app, hash) != null) return
        if (pl.sinusoide.sinuschat.util.BlockList.isCutOff(app, hash)) return
        val now = System.currentTimeMillis()
        val asked = app.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE)
        if (now - asked.getLong(hash, 0L) < ASK_EVERY_MS) return
        asked.edit().putLong(hash, now).apply()
        AppExecutor.io { pl.sinusoide.sinuschat.messenger.MessageRepository.askProfileKeyBlocking(app, me, hash) }
    }

    private const val ASK_PREFS = "profile_key_asked"
    const val KIND_KEY_REQUEST = "pkey_req"
    private val answered = HashMap<String, Long>()

    fun onKeyRequest(ctx: Context, sender: String) {
        val app = ctx.applicationContext
        val me = SetupActivity.getUserPhoneHash(app)
        if (me.isEmpty() || sender == me || pl.sinusoide.sinuschat.util.BlockList.isCutOff(app, sender)) return
        val known = pl.sinusoide.sinuschat.util.LocalChats.contacts(app, me).contains(sender) ||
            pl.sinusoide.sinuschat.util.GroupRoster.allMembers(app).contains(sender)
        if (!known) return
        val now = System.currentTimeMillis()
        synchronized(answered) {
            if (now - (answered[sender] ?: 0L) < 3600_000L) return
            answered[sender] = now
        }
        AppExecutor.io { pl.sinusoide.sinuschat.messenger.MessageRepository.sendProfileKeyBlocking(app, me, sender) }
    }

    fun renameContact(ctx: Context, oldHash: String, newHash: String) {
        try {
            val p = prefs(ctx)
            val k = p.getString("c_$oldHash", null) ?: return
            p.edit().remove("c_$oldHash").putString("c_$newHash", k).apply()
        } catch (_: Exception) {}
    }

    fun sealName(ctx: Context, name: String): String =
        E2EManager.encrypt(name, myKey(ctx))?.let { NAME_PREFIX + it } ?: name

    fun openName(ctx: Context, hash: String, raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        if (!raw.startsWith(NAME_PREFIX)) return raw
        if (hash == SetupActivity.getUserPhoneHash(ctx)) return openOwnName(ctx, raw)
        return contactKey(ctx, hash)?.let { E2EManager.decrypt(raw.removePrefix(NAME_PREFIX), it) }?.takeIf { it.isNotBlank() }
    }

    fun isEncryptedAvatar(url: String?) = url != null && url.substringBefore('?').endsWith(".bin")

    private fun isGroup(id: String) = id.startsWith("g_")

    fun sealAvatar(ctx: Context, imageBytes: ByteArray, key: ByteArray = myKey(ctx)): ByteArray? {
        val bmp = pl.sinusoide.sinuschat.util.ImagePrep.decodeSampled(imageBytes, AVATAR_MAX_PX * 2) ?: return null
        val scale = minOf(1f, AVATAR_MAX_PX.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        else bmp
        val jpeg = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return encrypt(key, jpeg)
    }

    private fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return AVATAR_MAGIC + c.iv + c.doFinal(plain)
    }

    private fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? = try {
        if (blob.size < 4 + 12 + 16 || !blob.copyOfRange(0, 4).contentEquals(AVATAR_MAGIC)) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(4, 16)))
            c.doFinal(blob.copyOfRange(16, blob.size))
        }
    } catch (_: Exception) { null }

    private fun plainFile(ctx: Context, hash: String, url: String): File {
        val tag = E2EManager.sha256(url).take(12)
        return File(File(ctx.filesDir, AVATAR_DIR), "${hash.take(32)}_$tag.jpg")
    }

    fun displayAvatar(ctx: Context, hash: String, url: String?): String? {
        if (url.isNullOrEmpty() || url == "null") return null
        if (!isEncryptedAvatar(url)) return url
        val f = plainFile(ctx, hash, url)
        if (f.exists()) return f.absolutePath
        if (isGroup(hash)) {
            if (GroupMeta.profileKey(ctx, hash) == null) return null
        } else if (hash != SetupActivity.getUserPhoneHash(ctx) && contactKey(ctx, hash) == null) {
            askForKey(ctx, hash)
            return null
        }
        val now = System.currentTimeMillis()
        synchronized(tried) {
            if (now - (tried[url] ?: 0L) < 10 * 60_000L) return null
            tried[url] = now
        }
        AppExecutor.io { fetchAvatar(ctx.applicationContext, hash, url) }
        return null
    }

    private val tried = HashMap<String, Long>()

    fun fetchAvatar(ctx: Context, hash: String, url: String, keyOverride: ByteArray? = null): String? {
        val out = plainFile(ctx, hash, url)
        if (out.exists()) return out.absolutePath
        val key = keyOverride ?: when {
            isGroup(hash) -> GroupMeta.profileKey(ctx, hash)
            hash == SetupActivity.getUserPhoneHash(ctx) -> myKey(ctx)
            else -> contactKey(ctx, hash)
        } ?: return null
        return try {
            val req = Request.Builder().url(url).build()
            val blob = ApiClient.httpClient(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.bytes()
            } ?: return null
            if (blob.size > 3 * 1024 * 1024) return null
            val jpeg = decrypt(key, blob) ?: return null
            out.parentFile?.mkdirs()
            out.parentFile?.listFiles { f -> f.name.startsWith("${hash.take(32)}_") }?.forEach { it.delete() }
            out.writeBytes(jpeg)
            AppExecutor.main {
                pl.sinusoide.sinuschat.util.AvatarHelper.onAvatarDecrypted()
                ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
            }
            out.absolutePath
        } catch (e: Exception) { Log.w(TAG, "Zdjęcie: ${e.message}"); null }
    }

    fun openOwnName(ctx: Context, raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        if (!raw.startsWith(NAME_PREFIX)) return raw
        val ct = raw.removePrefix(NAME_PREFIX)
        return (E2EManager.decrypt(ct, myKey(ctx)) ?: myPrevKey(ctx)?.let { E2EManager.decrypt(ct, it) })
            ?.takeIf { it.isNotBlank() }
    }

    fun refreshContact(ctx: Context, hash: String) {
        synchronized(tried) { tried.clear() }
        val user = try { ApiClient.getObject("/api/user/$hash", ctx) } catch (_: Exception) { null } ?: return
        openName(ctx, hash, user.optString("display_name"))?.let { name ->
            if (name != ContactCache.realName(ctx, hash)) {
                ContactCache.saveName(ctx, hash, name)
                AppExecutor.main {
                    ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
                }
            }
        }
        val url = user.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }
        val before = ContactCache.storedAvatarUrl(ctx, hash)
        ContactCache.saveAvatarUrl(ctx, hash, url)
        if (url != null && isEncryptedAvatar(url)) fetchAvatar(ctx, hash, url)
        if (url != before) AppExecutor.main {
            ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
        }
    }

    fun wipePlainAvatars(ctx: Context) {
        try { File(ctx.filesDir, AVATAR_DIR).deleteRecursively() } catch (_: Exception) {}
    }
}
