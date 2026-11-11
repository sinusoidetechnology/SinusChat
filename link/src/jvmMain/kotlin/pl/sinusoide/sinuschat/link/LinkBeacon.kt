package pl.sinusoide.sinuschat.link

import java.io.ByteArrayOutputStream
import java.util.Base64

object LinkBeacon {
    private const val NONCE_BYTES = 8
    private const val TAG_BYTES = 6
    private const val MIN_TAGS = 4
    private const val MAX_TAGS = 24
    private val INFO = "sinuschat-link-v2 beacon".toByteArray()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    private fun tag(own: LinkIdentity, peer: ByteArray, nonce: ByteArray): ByteArray {
        val shared = LinkCrypto.ecdh(own.privateKey, LinkCrypto.decodePublic(peer))
        val key = LinkCrypto.hkdf(LinkCrypto.sha256(INFO), shared, INFO, 32)
        return LinkCrypto.hmac(key, nonce).copyOf(TAG_BYTES)
    }

    fun create(own: LinkIdentity, peers: List<ByteArray>): String {
        val nonce = LinkCrypto.randomBytes(NONCE_BYTES)
        val tags = peers.take(MAX_TAGS).mapNotNull { runCatching { tag(own, it, nonce) }.getOrNull() }.toMutableList()
        while (tags.size < MIN_TAGS) tags += LinkCrypto.randomBytes(TAG_BYTES)
        val out = ByteArrayOutputStream()
        tags.shuffled().forEach { out.write(it) }
        return b64.encodeToString(nonce) + "." + b64.encodeToString(out.toByteArray())
    }

    fun matches(own: LinkIdentity, peer: ByteArray, beacon: String): Boolean = try {
        val parts = beacon.trim().split('.')
        if (parts.size != 2) false
        else {
            val nonce = b64d.decode(parts[0])
            val tags = b64d.decode(parts[1])
            if (nonce.size != NONCE_BYTES || tags.isEmpty() || tags.size % TAG_BYTES != 0) false
            else {
                val expected = tag(own, peer, nonce)
                var hit = false
                for (i in 0 until tags.size / TAG_BYTES) {
                    if (LinkCrypto.equal(tags.copyOfRange(i * TAG_BYTES, (i + 1) * TAG_BYTES), expected)) hit = true
                }
                hit
            }
        }
    } catch (_: Exception) {
        false
    }
}
