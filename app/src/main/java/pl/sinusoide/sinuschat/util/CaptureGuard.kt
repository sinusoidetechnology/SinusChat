package pl.sinusoide.sinuschat.util

import android.content.Context

object CaptureGuard {

    private const val PREFS = "sinuschat_contact_cache"

    fun peerBlocks(context: Context, hash: String): Boolean =
        hash.isNotEmpty() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cb_$hash", false)

    fun savePeerBlocks(context: Context, hash: String, on: Boolean) {
        if (hash.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().also {
            if (on) it.putBoolean("cb_$hash", true) else it.remove("cb_$hash")
        }.apply()
    }

    fun notifyAttempt(context: Context, conversationId: String, myHash: String, otherHash: String, duringCall: Boolean) {
        if (conversationId.isEmpty() || myHash.isEmpty() || otherHash.isEmpty()) return
        val app = context.applicationContext
        AppExecutor.io {
            val payload = org.json.JSONObject().put("senderHash", myHash).put("timestamp", System.currentTimeMillis())
            if (duringCall) payload.put("during", "call")
            val body = org.json.JSONObject().apply {
                put("conversationId", conversationId); put("messageId", java.util.UUID.randomUUID().toString())
                put("kind", "screenshot_attempt"); put("recipientHash", otherHash)
                put("payloadB64", android.util.Base64.encodeToString(payload.toString().toByteArray(), android.util.Base64.NO_WRAP))
            }
            pl.sinusoide.sinuschat.crypto.SealedSender.post(app, body)
        }
    }
}
