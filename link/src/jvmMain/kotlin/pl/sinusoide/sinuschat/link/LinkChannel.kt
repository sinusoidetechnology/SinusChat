package pl.sinusoide.sinuschat.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class LinkChannel internal constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
    val peerKey: ByteArray,
    val mode: Int
) {
    private val sendCipher = LinkCrypto.FrameCipher(sendKey)
    private val receiveCipher = LinkCrypto.FrameCipher(receiveKey)
    private val sendLock = Any()

    val peerId: String get() = LinkCrypto.fingerprint(peerKey)

    fun write(frame: LinkFrame) {
        val plain = LinkFrame.encode(frame).toByteArray(Charsets.UTF_8)
        if (plain.size > LinkProtocol.MAX_FRAME_BYTES) throw IOException("frame too large")
        val padded = pad(plain)
        synchronized(sendLock) {
            val sealed = sendCipher.seal(padded)
            output.writeInt(sealed.size)
            output.write(sealed)
            output.flush()
        }
    }

    private fun pad(plain: ByteArray): ByteArray {
        val n = plain.size
        val target = if (n >= PAD_LARGE_STEP) {
            ((n + PAD_LARGE_STEP - 1) / PAD_LARGE_STEP) * PAD_LARGE_STEP
        } else {
            var b = PAD_MIN
            while (b < n) b = b shl 1
            b
        }.coerceAtMost(LinkProtocol.MAX_FRAME_BYTES)
        if (target <= n) return plain
        return plain.copyOf(target).also { it.fill(SPACE, n, target) }
    }

    fun read(): LinkFrame? {
        val n = input.readInt()
        if (n < 16 || n > LinkProtocol.MAX_FRAME_BYTES + 16) throw IOException("bad frame length")
        val sealed = ByteArray(n).also { input.readFully(it) }
        val plain = try {
            receiveCipher.open(sealed)
        } catch (e: Exception) {
            throw IOException("frame authentication failed", e)
        }
        return LinkFrame.decode(String(plain, Charsets.UTF_8))
    }

    private companion object {
        const val PAD_MIN = 512
        const val PAD_LARGE_STEP = 64 * 1024
        const val SPACE: Byte = 0x20
    }
}
