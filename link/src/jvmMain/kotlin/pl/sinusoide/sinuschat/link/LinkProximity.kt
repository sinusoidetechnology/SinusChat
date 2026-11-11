package pl.sinusoide.sinuschat.link

object LinkProximity {
    const val COMPANY_ID = 0xFFFF
    const val VERSION: Byte = 1
    const val SLOT_MS = 10 * 60_000L
    const val TOKEN_BYTES = 10
    const val PAYLOAD_BYTES = 1 + TOKEN_BYTES
    private val INFO = "sinuschat-link-v2 proximity".toByteArray()

    fun key(own: LinkIdentity, peer: ByteArray): ByteArray {
        val shared = LinkCrypto.ecdh(own.privateKey, LinkCrypto.decodePublic(peer))
        return LinkCrypto.hkdf(LinkCrypto.sha256(INFO), shared, INFO, 32)
    }

    fun slot(nowMs: Long): Long = Math.floorDiv(nowMs, SLOT_MS)

    fun msToNextSlot(nowMs: Long): Long = SLOT_MS - Math.floorMod(nowMs, SLOT_MS)

    private fun token(key: ByteArray, slot: Long): ByteArray {
        val s = ByteArray(8) { i -> (slot ushr (56 - 8 * i)).toByte() }
        return LinkCrypto.hmac(key, s).copyOf(TOKEN_BYTES)
    }

    fun payload(key: ByteArray, nowMs: Long): ByteArray = byteArrayOf(VERSION) + token(key, slot(nowMs))

    fun matches(key: ByteArray, payload: ByteArray, nowMs: Long): Boolean {
        if (payload.size != PAYLOAD_BYTES || payload[0] != VERSION) return false
        val got = payload.copyOfRange(1, PAYLOAD_BYTES)
        val now = slot(nowMs)
        var hit = false
        for (s in now - 1..now + 1) {
            if (LinkCrypto.equal(token(key, s), got)) hit = true
        }
        return hit
    }
}
