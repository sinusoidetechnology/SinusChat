package pl.sinusoide.sinuschat.util

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.crypto.ProfileCrypto
import pl.sinusoide.sinuschat.crypto.SignalStore
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.messenger.MessageRepository
import java.io.File

object AccountMove {

    private const val TAG = "AccountMove"
    const val KIND = "moved"

    private fun message(oldHash: String, newHash: String) = "SinusChat moved v1|$oldHash|$newHash".toByteArray()

    private const val PREFS = "account_move"
    private const val RESEND_GAP_MS = 3_600_000L
    private val resentTo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun prefs(ctx: Context) = pl.sinusoide.sinuschat.crypto.SecurePrefs.get(ctx, PREFS)

    fun previousHashes(ctx: Context): Set<String> =
        try { prefs(ctx).getStringSet("prev", emptySet()).orEmpty() } catch (_: Exception) { emptySet() }

    private fun remember(ctx: Context, oldHash: String, newHash: String) {
        try {
            val prev = previousHashes(ctx).toMutableSet()
            prev.add(oldHash)
            prev.remove(newHash)
            prefs(ctx).edit().putStringSet("prev", prev).putString("last_old", oldHash)
                .putString("last_new", newHash).putBoolean("pending", true).apply()
        } catch (_: Exception) {}
    }

    fun currentConvId(ctx: Context, convId: String, me: String): String {
        if (me.isEmpty() || !LocalChats.isDirect(convId)) return convId
        val parts = convId.split('_')
        if (parts.size != 2 || me in parts) return convId
        val prev = previousHashes(ctx)
        val mine = parts.firstOrNull { it in prev } ?: return convId
        val other = parts.firstOrNull { it != mine }?.takeIf { it.isNotEmpty() } ?: return convId
        return MessageRepository.conversationId(me, other)
    }

    private fun signature(ctx: Context, oldHash: String, newHash: String): String? {
        val priv = E2EManager.myPrivateKeyBytes(ctx) ?: return null
        return try { Base64.encodeToString(ECPrivateKey(priv).calculateSignature(message(oldHash, newHash)), Base64.NO_WRAP) }
        catch (e: Exception) { Log.w(TAG, "Podpis: ${e.message}"); null }
    }

    fun reannounceTo(ctx: Context, other: String) {
        val app = ctx.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        val last = resentTo[other]
        if (last != null && now - last < RESEND_GAP_MS) return
        resentTo[other] = now
        val p = try { prefs(app) } catch (_: Exception) { return }
        val oldHash = p.getString("last_old", null) ?: return
        val newHash = p.getString("last_new", null) ?: return
        if (newHash != pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(app)) return
        val sig = signature(app, oldHash, newHash) ?: return
        MessageRepository.sendMovedBlocking(app, MessageRepository.conversationId(newHash, other), newHash, other, oldHash, sig)
    }

    fun retryPending(ctx: Context) {
        val app = ctx.applicationContext
        val p = try { prefs(app) } catch (_: Exception) { return }
        if (!p.getBoolean("pending", false)) return
        val oldHash = p.getString("last_old", null) ?: return
        val newHash = p.getString("last_new", null) ?: return
        if (newHash != pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(app)) {
            p.edit().putBoolean("pending", false).apply()
            return
        }
        announceMove(app, oldHash, newHash, LocalChats.contacts(app, newHash))
    }

    fun moveLocally(ctx: Context, oldHash: String, newHash: String): List<String> {
        val app = ctx.applicationContext
        remember(app, oldHash, newHash)
        GroupRoster.renameMember(app, oldHash, newHash)
        val contacts = LocalChats.contacts(app, oldHash)
        if (pl.sinusoide.sinuschat.BuildConfig.DEBUG) Log.d(TAG, "Przenoszę rozmowy: ${contacts.size}")
        for (other in contacts) {
            renameConversation(app, MessageRepository.conversationId(oldHash, other),
                MessageRepository.conversationId(newHash, other), other)
        }
        renameHashLocally(app, oldHash, newHash)
        return contacts
    }

    @Synchronized
    fun announceMove(ctx: Context, oldHash: String, newHash: String, contacts: List<String>) {
        val app = ctx.applicationContext
        UserStatusManager.init(newHash, app)
        val sig = signature(app, oldHash, newHash) ?: return
        var allSent = true
        for (other in contacts) {
            val ok = try {
                MessageRepository.sendMovedBlocking(app, MessageRepository.conversationId(newHash, other), newHash, other, oldHash, sig)
            } catch (_: Exception) { false }
            if (!ok) allSent = false
        }
        if (allSent) try {
            val p = prefs(app)
            if (p.getString("last_new", null) == newHash) p.edit().putBoolean("pending", false).apply()
        } catch (_: Exception) {}
        if (pl.sinusoide.sinuschat.BuildConfig.DEBUG) Log.d(TAG, "Powiadomienie o przeniesieniu: ${contacts.size} kontaktów, wszystkie wysłane: $allSent")
    }

    fun afterEmailChange(ctx: Context, oldHash: String, newHash: String) {
        val app = ctx.applicationContext
        val contacts = moveLocally(app, oldHash, newHash)
        announceMove(app, oldHash, newHash, contacts)
        if (pl.sinusoide.sinuschat.BuildConfig.DEBUG) {
            val left = try {
                SinusChatDatabase.getInstance(app).messageDao().getConversationIds().count { it.contains(oldHash) }
            } catch (_: Exception) { -1 }
            Log.d(TAG, "Po przeniesieniu zostało rozmów ze starym kontem w bazie: $left")
        }
    }

    fun onMoved(ctx: Context, me: String, newHash: String, payload: JSONObject): Boolean {
        val oldHash = payload.optString("oldHash")
        val sig = payload.optString("sig")
        if (!Regex("[a-f0-9]{64}").matches(oldHash) || oldHash == newHash || sig.isEmpty()) return false
        val known = PeerKeyCache.getKey(ctx, oldHash) ?: return false
        val ok = try {
            ECPublicKey(Base64.decode(known, Base64.NO_WRAP))
                .verifySignature(message(oldHash, newHash), Base64.decode(sig, Base64.NO_WRAP))
        } catch (_: Exception) { false }
        if (!ok) { Log.w(TAG, "Podpis przeniesienia się nie zgadza — pomijam"); return false }
        val oldId = MessageRepository.conversationId(me, oldHash)
        val newId = MessageRepository.conversationId(me, newHash)
        renameConversation(ctx, oldId, newId, newHash)
        renameHashLocally(ctx, oldHash, newHash)
        for (gid in GroupRoster.groupsOf(ctx, oldHash)) GroupRoster.applyMove(ctx, gid, oldHash, newHash)
        PeerKeyCache.saveKey(ctx, newHash, known)
        SignalStore.get(ctx).renamePeer(oldHash, newHash)
        ProfileCrypto.renameContact(ctx, oldHash, newHash)
        val text = LocaleManager.wrap(ctx).getString(pl.sinusoide.sinuschat.R.string.sys_peer_email_changed)
        MessageRepository.insertSystemMessage(ctx, newId, text, "sys_email_changed")
        return true
    }

    private fun renameConversation(ctx: Context, oldId: String, newId: String, newOther: String) {
        if (oldId == newId) return
        try {
            val db = SinusChatDatabase.getInstance(ctx)
            val moved = db.messageDao().renameConversation(oldId, newId)
            db.callDao().renameConversation(oldId, newId)
            if (pl.sinusoide.sinuschat.BuildConfig.DEBUG) Log.d(TAG, "Rozmowa ${oldId.takeLast(6)} -> ${newId.takeLast(6)}: wiadomości $moved")
        } catch (e: Exception) { Log.w(TAG, "Baza: ${e.message}") }
        LocalChats.rename(ctx, oldId, newId, newOther)
        renameMediaDir(ctx, oldId, newId)
        renamePrefKeys(ctx, oldId, newId)
        try { ConversationCache.remove(ctx, oldId) } catch (_: Exception) {}
    }

    private fun renameHashLocally(ctx: Context, oldHash: String, newHash: String) {
        try {
            val db = SinusChatDatabase.getInstance(ctx)
            db.messageDao().renameSender(oldHash, newHash)
            db.callDao().renameOther(oldHash, newHash)
        } catch (e: Exception) { Log.w(TAG, "Baza: ${e.message}") }
        renamePrefKeys(ctx, oldHash, newHash)
    }

    private fun renameMediaDir(ctx: Context, oldId: String, newId: String) {
        fun dir(id: String) = File(ctx.filesDir, "media/" + id.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        val from = dir(oldId)
        if (from.exists() && !dir(newId).exists()) from.renameTo(dir(newId))
    }

    private fun renamePrefKeys(ctx: Context, old: String, new: String) {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        for (f in dir.listFiles() ?: return) {
            val name = f.name.removeSuffix(".xml")
            try {
                val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val hits = p.all.filterKeys { it.contains(old) }
                if (hits.isEmpty()) continue
                val e = p.edit()
                for ((k, v) in hits) {
                    val nk = k.replace(old, new)
                    when (v) {
                        is String -> e.putString(nk, v)
                        is Long -> e.putLong(nk, v)
                        is Int -> e.putInt(nk, v)
                        is Boolean -> e.putBoolean(nk, v)
                        is Float -> e.putFloat(nk, v)
                        is Set<*> -> e.putStringSet(nk, v.filterIsInstance<String>().toSet())
                    }
                    e.remove(k)
                }
                e.apply()
            } catch (_: Exception) {}
        }
    }
}
