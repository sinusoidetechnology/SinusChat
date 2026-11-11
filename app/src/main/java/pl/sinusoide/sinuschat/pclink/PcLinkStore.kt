package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.SecurePrefs
import pl.sinusoide.sinuschat.link.LinkCrypto
import pl.sinusoide.sinuschat.link.LinkIdentity
import pl.sinusoide.sinuschat.link.LinkProtocol
import pl.sinusoide.sinuschat.link.PairingCode

object PcLinkStore {

    private const val PREFS = "pc_link"
    private const val K_PRIVATE = "identity_private"
    private const val K_PUBLIC = "identity_public"
    private const val K_PCS = "pcs"

    data class Pc(val key: String, val name: String, val pairedAt: Long, val lastSeen: Long, val app: String = "", val proximity: Boolean = false) {
        val keyBytes: ByteArray get() = decode(key)
        val isWatch: Boolean get() = app.contains("watch", true) || app.contains("wear", true)
        val id: String get() = LinkCrypto.fingerprint(keyBytes)
    }

    private class Pending(val pcKey: ByteArray, val token: ByteArray, val expiresAt: Long)

    @Volatile private var identity: LinkIdentity? = null
    @Volatile private var pending: Pending? = null

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx.applicationContext, PREFS)

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    @Synchronized
    fun identity(ctx: Context): LinkIdentity {
        identity?.let { return it }
        val p = prefs(ctx)
        val priv = p.getString(K_PRIVATE, null)
        val pub = p.getString(K_PUBLIC, null)
        val restored = if (priv != null && pub != null) {
            runCatching { LinkIdentity.restore(decode(priv), decode(pub)) }.getOrNull()
        } else null
        val id = restored ?: LinkIdentity.generate().also {
            p.edit().putString(K_PRIVATE, encode(it.privateBytes)).putString(K_PUBLIC, encode(it.publicBytes)).commit()
        }
        identity = id
        return id
    }

    @Synchronized
    fun pcs(ctx: Context): List<Pc> {
        val raw = prefs(ctx).getString(K_PCS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (key.isEmpty()) null
            else Pc(key, o.optString("name"), o.optLong("pairedAt"), o.optLong("lastSeen"), o.optString("app"), o.optBoolean("proximity"))
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Pc>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name)
                .put("pairedAt", it.pairedAt).put("lastSeen", it.lastSeen).put("app", it.app).put("proximity", it.proximity))
        }
        prefs(ctx).edit().putString(K_PCS, arr.toString()).commit()
    }

    fun find(ctx: Context, key: ByteArray): Pc? {
        val k = encode(key)
        return pcs(ctx).firstOrNull { it.key == k }
    }

    @Synchronized
    fun upsert(ctx: Context, pc: Pc) {
        save(ctx, pcs(ctx).filter { it.key != pc.key } + pc)
    }

    @Synchronized
    fun update(ctx: Context, key: String, name: String? = null, lastSeen: Long? = null, app: String? = null, proximity: Boolean? = null) {
        val list = pcs(ctx)
        if (list.none { it.key == key }) return
        save(ctx, list.map {
            if (it.key != key) it
            else it.copy(name = name ?: it.name, lastSeen = lastSeen ?: it.lastSeen, app = app ?: it.app, proximity = proximity ?: it.proximity)
        })
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        save(ctx, pcs(ctx).filter { it.key != key })
    }

    fun startPairing(code: PairingCode) {
        pending = Pending(code.pcKey, code.token, System.currentTimeMillis() + LinkProtocol.PAIRING_TTL_MS)
    }

    fun cancelPairing() {
        pending = null
    }

    fun isPairing(): Boolean {
        val p = pending ?: return false
        if (System.currentTimeMillis() > p.expiresAt) { pending = null; return false }
        return true
    }

    fun pairingToken(pcKey: ByteArray): ByteArray? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) { pending = null; return null }
        return if (LinkCrypto.equal(p.pcKey, pcKey)) p.token else null
    }

    fun pairingTokens(): List<ByteArray> {
        val p = pending ?: return emptyList()
        if (System.currentTimeMillis() > p.expiresAt) { pending = null; return emptyList() }
        return listOf(p.token)
    }

    fun pairingDone(pcKey: ByteArray) {
        val p = pending ?: return
        if (LinkCrypto.equal(p.pcKey, pcKey)) pending = null
    }

    @Synchronized
    fun clear(ctx: Context) {
        pending = null
        identity = null
        prefs(ctx).edit().clear().commit()
    }
}
