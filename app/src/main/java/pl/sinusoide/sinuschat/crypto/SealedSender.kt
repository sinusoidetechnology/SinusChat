package pl.sinusoide.sinuschat.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.PeerKeyCache
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SealedSender {

    private const val TAG = "SealedSender"
    private const val VERSION: Byte = 1
    private const val PREFS = "sealed_state"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val INFO_L1 = "SinusChat sealed v1 L1".toByteArray()
    private val INFO_L2 = "SinusChat sealed v1 L2".toByteArray()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun accessKey(profileKey: ByteArray): ByteArray =
        hmac(profileKey, "SinusChat access v1".toByteArray()).copyOf(16)

    fun sealRaw(myPriv: ECPrivateKey, myPub: ByteArray, theirPub: ByteArray, inner: ByteArray): ByteArray {
        val their = ECPublicKey(theirPub)
        val eph = ECKeyPair.generate()
        val ephPub = eph.publicKey.serialize()
        val header = byteArrayOf(VERSION) + ephPub
        val k1 = hkdf(eph.privateKey.calculateAgreement(their), theirPub + ephPub, INFO_L1)
        val c1 = gcmSeal(k1, myPub, header)
        val k2 = hkdf(myPriv.calculateAgreement(their), header + c1, INFO_L2)
        val c2 = gcmSeal(k2, inner, header + c1)
        return header + byteArrayOf((c1.size shr 8).toByte(), c1.size.toByte()) + c1 + c2
    }

    fun openRaw(myPriv: ECPrivateKey, myPub: ByteArray, blob: ByteArray): Pair<ByteArray, ByteArray>? = try {
        if (blob.size < 1 + 33 + 2 + 28 + 28 || blob[0] != VERSION) null
        else {
            val header = blob.copyOfRange(0, 34)
            val ephPub = blob.copyOfRange(1, 34)
            val len = ((blob[34].toInt() and 0xff) shl 8) or (blob[35].toInt() and 0xff)
            if (len < 28 || 36 + len >= blob.size) null
            else {
                val c1 = blob.copyOfRange(36, 36 + len)
                val c2 = blob.copyOfRange(36 + len, blob.size)
                val k1 = hkdf(myPriv.calculateAgreement(ECPublicKey(ephPub)), myPub + ephPub, INFO_L1)
                val senderPub = gcmOpen(k1, c1, header)
                if (senderPub == null || senderPub.size != 33) null
                else {
                    val k2 = hkdf(myPriv.calculateAgreement(ECPublicKey(senderPub)), header + c1, INFO_L2)
                    gcmOpen(k2, c2, header + c1)?.let { senderPub to it }
                }
            }
        }
    } catch (_: Exception) { null }

    class GroupSend(val sent: Boolean, val noAccess: Boolean, val createdAt: Long)

    fun postToGroupMember(ctx: Context, groupId: String, to: String, messageId: String, kind: String, payloadB64: String): GroupSend {
        val body = JSONObject()
            .put("conversationId", groupId)
            .put("messageId", messageId)
            .put("kind", kind)
            .put("recipientHash", to)
            .put("payloadB64", payloadB64)
        return when (val o = trySealed(ctx, body, allowGroup = true)) {
            is Outcome.Sent -> GroupSend(true, false, o.json.optLong("createdAt", 0L))
            Outcome.NoAccess, Outcome.Limited -> GroupSend(false, true, 0L)
            Outcome.Failed -> GroupSend(false, false, 0L)
        }
    }

    private sealed class Outcome {
        class Sent(val json: JSONObject) : Outcome()
        object NoAccess : Outcome()
        object Limited : Outcome()
        object Failed : Outcome()
    }

    fun post(ctx: Context, body: JSONObject): JSONObject? {
        when (val o = trySealed(ctx, body)) {
            is Outcome.Sent -> return o.json
            Outcome.Failed, Outcome.Limited -> return null
            Outcome.NoAccess -> {}
        }
        return ApiClient.post("/api/message", body, ctx)
    }

    fun sendTyping(ctx: Context, conversationId: String, to: String, typing: Boolean): Boolean {
        if (conversationId.startsWith("g_") || !canSeal(ctx, to)) return false
        val app = ctx.applicationContext
        val body = JSONObject()
            .put("conversationId", conversationId)
            .put("messageId", java.util.UUID.randomUUID().toString())
            .put("kind", if (typing) "typing" else "typing_stop")
            .put("recipientHash", to)
            .put("payloadB64", b64("{}".toByteArray()))
        pl.sinusoide.sinuschat.util.AppExecutor.io { trySealed(app, body, ephemeral = true) }
        return true
    }

    fun canSeal(ctx: Context, to: String): Boolean =
        to.isNotEmpty() && ProfileCrypto.contactKey(ctx, to) != null && PeerKeyCache.getKey(ctx, to) != null &&
            System.currentTimeMillis() >= refusedUntil(ctx, to)

    private fun trySealed(ctx: Context, body: JSONObject, ephemeral: Boolean = false, allowGroup: Boolean = false): Outcome {
        val conv = body.optString("conversationId")
        val to = body.optString("recipientHash")
        if (conv.isEmpty() || (!allowGroup && (conv.startsWith("g_") || conv.startsWith("group:")))) return Outcome.NoAccess
        if (!canSeal(ctx, to)) return Outcome.NoAccess
        val profileKey = ProfileCrypto.contactKey(ctx, to) ?: return Outcome.NoAccess
        val theirId = PeerKeyCache.getKey(ctx, to) ?: return Outcome.NoAccess
        val me = SetupActivity.getUserPhoneHash(ctx)
        val myPrivBytes = E2EManager.myPrivateKeyBytes(ctx) ?: return Outcome.NoAccess
        val myPub = E2EManager.getMyPublicKey(ctx) ?: return Outcome.NoAccess
        val env = try {
            val inner = JSONObject()
                .put("s", me)
                .put("c", conv)
                .put("k", body.optString("kind", "msg"))
                .put("p", body.optString("payloadB64"))
            sealRaw(ECPrivateKey(myPrivBytes), Base64.decode(myPub, Base64.NO_WRAP),
                Base64.decode(theirId, Base64.NO_WRAP), inner.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) { Log.w(TAG, "Koperta: ${e.message}"); return Outcome.NoAccess }

        val req = JSONObject()
            .put("to", to)
            .put("ak", b64(accessKey(profileKey)))
            .put("id", body.optString("messageId"))
            .put("payloadB64", b64(env))
            .put("ik", sha(Base64.decode(theirId, Base64.NO_WRAP), 16))
        if (ephemeral) req.put("eph", true)
        val call = Request.Builder()
            .url("${ApiClient.BASE_URL}/api/sealed")
            .post(req.toString().toRequestBody(JSON_TYPE))
            .build()
        return try {
            ApiClient.httpClient(ctx).newCall(call).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> Outcome.Sent(runCatching { JSONObject(s) }.getOrElse { JSONObject() })
                    resp.code == 401 || resp.code == 403 || resp.code == 404 -> {
                        refuse(ctx, to); Outcome.NoAccess
                    }
                    resp.code == 409 -> {
                        try {
                            ApiClient.getObject("/api/user/$to", ctx)?.optString("e2e_public_key")
                                ?.takeIf { it.isNotEmpty() && it != "null" }
                                ?.let { pl.sinusoide.sinuschat.messenger.MessageListenerService.syncPeerKey(ctx, to, it) }
                        } catch (_: Exception) {}
                        Outcome.NoAccess
                    }
                    resp.code == 429 -> Outcome.Limited
                    resp.code in 400..499 -> Outcome.NoAccess
                    else -> Outcome.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wysyłka: ${e.message}")
            Outcome.Failed
        }
    }

    private fun refusedUntil(ctx: Context, to: String): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("r_" + sha(to), 0L)

    private fun refuse(ctx: Context, to: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("r_" + sha(to), System.currentTimeMillis() + 3600_000L).apply()
    }

    fun forgetRefusal(ctx: Context, hash: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("r_" + sha(hash)).apply()
    }

    fun unseal(ctx: Context, frame: JSONObject): JSONObject? {
        val blob = try { Base64.decode(frame.optString("payloadB64"), Base64.NO_WRAP) } catch (_: Exception) { return null }
        val myPrivBytes = E2EManager.myPrivateKeyBytes(ctx) ?: return null
        val myPub = E2EManager.getMyPublicKey(ctx) ?: return null
        val (senderId, innerBytes) = openRaw(ECPrivateKey(myPrivBytes), Base64.decode(myPub, Base64.NO_WRAP), blob)
            ?: return null
        val inner = runCatching { JSONObject(String(innerBytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        val sender = inner.optString("s").takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: return null
        if (sender == SetupActivity.getUserPhoneHash(ctx)) return null
        if (!identityMatches(ctx, sender, b64(senderId))) {
            Log.w(TAG, "Koperta z cudzym kluczem — odrzucona")
            pl.sinusoide.sinuschat.util.LinkLog.add(ctx, "sealed", "nadawca niezweryfikowany, koperta pominięta")
            return null
        }
        return JSONObject()
            .put("type", "message")
            .put("kind", inner.optString("k", "msg"))
            .put("senderHash", sender)
            .put("conversationId", inner.optString("c"))
            .put("payloadB64", inner.optString("p"))
            .put("id", frame.optString("id").removePrefix("s_"))
            .put("createdAt", frame.optLong("createdAt", System.currentTimeMillis()))
            .put("sealed", true)
    }

    private fun identityMatches(ctx: Context, sender: String, keyB64: String): Boolean {
        if (PeerKeyCache.getKey(ctx, sender) == keyB64) return true
        val fresh = try {
            ApiClient.getObject("/api/user/$sender", ctx)?.optString("e2e_public_key")
        } catch (_: Exception) { null }
        if (fresh.isNullOrEmpty() || fresh == "null" || fresh != keyB64) return false
        pl.sinusoide.sinuschat.messenger.MessageListenerService.syncPeerKey(ctx, sender, fresh)
        return true
    }

    private fun sha(s: String): String = sha(s.toByteArray(), 24)

    private fun sha(b: ByteArray, hexLen: Int): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(hexLen)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(32)
    }

    private fun gcmSeal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    private fun gcmOpen(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(aad)
        c.doFinal(blob.copyOfRange(12, blob.size))
    } catch (_: Exception) { null }
}
