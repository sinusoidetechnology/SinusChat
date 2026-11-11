package pl.sinusoide.sinuschat.link

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

class LinkRejected(val code: Int) : IOException("link rejected: $code")

class LinkHandshakeException(message: String) : IOException(message)

sealed class LinkMode(val code: Int) {
    class Pair(val token: ByteArray) : LinkMode(LinkHandshake.MODE_PAIR)
    class Resume(val phoneKey: ByteArray) : LinkMode(LinkHandshake.MODE_RESUME)
}

sealed class LinkAuthorization {
    class Accept(val psk: ByteArray) : LinkAuthorization()
    class Reject(val code: Int) : LinkAuthorization()
}

object LinkHandshake {
    const val MODE_PAIR = 1
    const val MODE_RESUME = 2

    const val OK = 0
    const val REJECT_UNKNOWN_DEVICE = 1
    const val REJECT_NOT_PAIRING = 2
    const val REJECT_VERSION = 3

    private const val MAX_KEY_BYTES = 256
    private const val CONFIRM_BYTES = 32
    private val MAGIC = "SCL1".toByteArray()
    private val MAGIC2 = "SCL2".toByteArray()
    private val LABEL = "SinusChatLink/1".toByteArray()
    private val LABEL2 = "SinusChatLink/2".toByteArray()
    private val INFO = "sinuschat-link-v1 keys".toByteArray()
    private val INFO_PC_ID = "sinuschat-link-v2 pc id".toByteArray()
    private val INFO_PHONE_ID = "sinuschat-link-v2 phone id".toByteArray()
    private val PHONE = "phone".toByteArray()
    private val PC = "pc".toByteArray()

    private class Keys(val c2s: ByteArray, val s2c: ByteArray, val confirm: ByteArray)

    private fun derive(th: ByteArray, ikm: ByteArray): Keys {
        val okm = LinkCrypto.hkdf(th, ikm, INFO, 96)
        return Keys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 96))
    }

    private fun transcript(label: ByteArray, mode: Int, sPc: ByteArray, ePc: ByteArray, sPh: ByteArray, ePh: ByteArray): ByteArray =
        LinkCrypto.sha256(LinkCrypto.lengthPrefixed(label, byteArrayOf(mode.toByte()), sPc, ePc, sPh, ePh))

    private fun pcIdKey(mode: Int, ePc: ByteArray, secret: ByteArray): ByteArray =
        LinkCrypto.hkdf(LinkCrypto.sha256(LinkCrypto.lengthPrefixed(LABEL2, byteArrayOf(mode.toByte()), ePc)),
            secret, INFO_PC_ID, 32)

    private fun phoneIdKey(mode: Int, ePc: ByteArray, ePh: ByteArray, ikm: ByteArray): ByteArray =
        LinkCrypto.hkdf(LinkCrypto.sha256(LinkCrypto.lengthPrefixed(LABEL2, byteArrayOf(mode.toByte()), ePc, ePh)),
            ikm, INFO_PHONE_ID, 32)

    private fun writeBlob(out: DataOutputStream, data: ByteArray) {
        out.writeShort(data.size)
        out.write(data)
    }

    private fun readBlob(input: DataInputStream): ByteArray {
        val n = input.readUnsignedShort()
        if (n == 0 || n > MAX_KEY_BYTES) throw LinkHandshakeException("bad key length")
        return ByteArray(n).also { input.readFully(it) }
    }

    private fun streams(socket: Socket) =
        DataInputStream(BufferedInputStream(socket.getInputStream())) to
            DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    private fun reject(output: DataOutputStream, code: Int): Nothing {
        output.writeByte(code)
        output.flush()
        throw LinkRejected(code)
    }

    fun connect(socket: Socket, identity: LinkIdentity, mode: LinkMode, legacy: Boolean = false): LinkChannel =
        if (legacy) connectV1(socket, identity, mode) else connectV2(socket, identity, mode)

    private fun connectV2(socket: Socket, identity: LinkIdentity, mode: LinkMode): LinkChannel {
        socket.soTimeout = LinkProtocol.HANDSHAKE_TIMEOUT_MS
        val (input, output) = streams(socket)
        val eph = LinkCrypto.generateKeyPair()
        val ePc = eph.public.encoded
        val secret = when (mode) {
            is LinkMode.Pair -> mode.token
            is LinkMode.Resume -> LinkCrypto.ecdh(eph.private, LinkCrypto.decodePublic(mode.phoneKey))
        }

        output.write(MAGIC2)
        output.writeByte(mode.code)
        writeBlob(output, ePc)
        writeBlob(output, LinkCrypto.sealOnce(pcIdKey(mode.code, ePc, secret), identity.publicBytes))
        output.flush()

        val status = input.readUnsignedByte()
        if (status != OK) throw LinkRejected(status)
        val ePh = readBlob(input)
        val sealedPh = readBlob(input)
        val confirmPh = ByteArray(CONFIRM_BYTES).also { input.readFully(it) }

        val ePhKey = LinkCrypto.decodePublic(ePh)
        val ee = LinkCrypto.ecdh(eph.private, ePhKey)
        val sPh = LinkCrypto.openOnce(phoneIdKey(mode.code, ePc, ePh, ee + secret), sealedPh)
            ?: throw LinkHandshakeException("phone identity unreadable")
        if (mode is LinkMode.Resume && !LinkCrypto.equal(sPh, mode.phoneKey))
            throw LinkHandshakeException("unexpected phone key")
        val sPhKey = LinkCrypto.decodePublic(sPh)

        val th = transcript(LABEL2, mode.code, identity.publicBytes, ePc, sPh, ePh)
        val psk = when (mode) {
            is LinkMode.Pair -> mode.token
            is LinkMode.Resume -> ByteArray(0)
        }
        val ikm = ee +
            LinkCrypto.ecdh(identity.privateKey, ePhKey) +
            LinkCrypto.ecdh(eph.private, sPhKey) +
            psk
        val keys = derive(th, ikm)
        if (!LinkCrypto.equal(confirmPh, LinkCrypto.hmac(keys.confirm, PHONE, th)))
            throw LinkHandshakeException("phone confirmation failed")

        output.write(LinkCrypto.hmac(keys.confirm, PC, th))
        output.flush()
        socket.soTimeout = LinkProtocol.READ_TIMEOUT_MS
        return LinkChannel(input, output, keys.c2s, keys.s2c, sPh, mode.code)
    }

    private fun connectV1(socket: Socket, identity: LinkIdentity, mode: LinkMode): LinkChannel {
        socket.soTimeout = LinkProtocol.HANDSHAKE_TIMEOUT_MS
        val (input, output) = streams(socket)
        val eph = LinkCrypto.generateKeyPair()
        val ePc = eph.public.encoded

        output.write(MAGIC)
        output.writeByte(mode.code)
        writeBlob(output, identity.publicBytes)
        writeBlob(output, ePc)
        output.flush()

        val status = input.readUnsignedByte()
        if (status != OK) throw LinkRejected(status)
        val sPh = readBlob(input)
        val ePh = readBlob(input)
        val confirmPh = ByteArray(CONFIRM_BYTES).also { input.readFully(it) }

        if (mode is LinkMode.Resume && !LinkCrypto.equal(sPh, mode.phoneKey))
            throw LinkHandshakeException("unexpected phone key")
        val sPhKey = LinkCrypto.decodePublic(sPh)
        val ePhKey = LinkCrypto.decodePublic(ePh)

        val th = transcript(LABEL, mode.code, identity.publicBytes, ePc, sPh, ePh)
        val psk = when (mode) {
            is LinkMode.Pair -> mode.token
            is LinkMode.Resume -> ByteArray(0)
        }
        val ikm = LinkCrypto.ecdh(eph.private, ePhKey) +
            LinkCrypto.ecdh(identity.privateKey, ePhKey) +
            LinkCrypto.ecdh(eph.private, sPhKey) +
            psk
        val keys = derive(th, ikm)
        if (!LinkCrypto.equal(confirmPh, LinkCrypto.hmac(keys.confirm, PHONE, th)))
            throw LinkHandshakeException("phone confirmation failed")

        output.write(LinkCrypto.hmac(keys.confirm, PC, th))
        output.flush()
        socket.soTimeout = LinkProtocol.READ_TIMEOUT_MS
        return LinkChannel(input, output, keys.c2s, keys.s2c, sPh, mode.code)
    }

    fun accept(
        socket: Socket,
        identity: LinkIdentity,
        pairingTokens: () -> List<ByteArray> = { emptyList() },
        authorize: (mode: Int, pcKey: ByteArray) -> LinkAuthorization
    ): LinkChannel {
        socket.soTimeout = LinkProtocol.HANDSHAKE_TIMEOUT_MS
        val (input, output) = streams(socket)

        val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
        return when {
            magic.contentEquals(MAGIC2) -> acceptV2(socket, input, output, identity, pairingTokens, authorize)
            magic.contentEquals(MAGIC) -> acceptV1(socket, input, output, identity, authorize)
            else -> reject(output, REJECT_VERSION)
        }
    }

    private fun acceptV2(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        identity: LinkIdentity,
        pairingTokens: () -> List<ByteArray>,
        authorize: (mode: Int, pcKey: ByteArray) -> LinkAuthorization
    ): LinkChannel {
        val mode = input.readUnsignedByte()
        val ePc = readBlob(input)
        val sealedPc = readBlob(input)
        if (mode != MODE_PAIR && mode != MODE_RESUME) reject(output, REJECT_VERSION)
        val refusal = if (mode == MODE_PAIR) REJECT_NOT_PAIRING else REJECT_UNKNOWN_DEVICE

        val ePcKey = LinkCrypto.decodePublic(ePc)
        val secrets = if (mode == MODE_RESUME) listOf(LinkCrypto.ecdh(identity.privateKey, ePcKey)) else pairingTokens()
        val (secret, sPc) = secrets.firstNotNullOfOrNull { s ->
            LinkCrypto.openOnce(pcIdKey(mode, ePc, s), sealedPc)?.let { s to it }
        } ?: reject(output, refusal)
        val sPcKey = runCatching { LinkCrypto.decodePublic(sPc) }.getOrNull() ?: reject(output, refusal)

        val auth = authorize(mode, sPc)
        if (auth is LinkAuthorization.Reject) reject(output, auth.code)
        val psk = (auth as LinkAuthorization.Accept).psk

        val eph = LinkCrypto.generateKeyPair()
        val ePh = eph.public.encoded
        val ee = LinkCrypto.ecdh(eph.private, ePcKey)

        val th = transcript(LABEL2, mode, sPc, ePc, identity.publicBytes, ePh)
        val ikm = ee +
            LinkCrypto.ecdh(eph.private, sPcKey) +
            LinkCrypto.ecdh(identity.privateKey, ePcKey) +
            psk
        val keys = derive(th, ikm)

        output.writeByte(OK)
        writeBlob(output, ePh)
        writeBlob(output, LinkCrypto.sealOnce(phoneIdKey(mode, ePc, ePh, ee + secret), identity.publicBytes))
        output.write(LinkCrypto.hmac(keys.confirm, PHONE, th))
        output.flush()

        val confirmPc = ByteArray(CONFIRM_BYTES).also { input.readFully(it) }
        if (!LinkCrypto.equal(confirmPc, LinkCrypto.hmac(keys.confirm, PC, th)))
            throw LinkHandshakeException("pc confirmation failed")

        socket.soTimeout = LinkProtocol.READ_TIMEOUT_MS
        return LinkChannel(input, output, keys.s2c, keys.c2s, sPc, mode)
    }

    private fun acceptV1(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        identity: LinkIdentity,
        authorize: (mode: Int, pcKey: ByteArray) -> LinkAuthorization
    ): LinkChannel {
        val mode = input.readUnsignedByte()
        val sPc = readBlob(input)
        val ePc = readBlob(input)

        val auth = if (mode == MODE_PAIR || mode == MODE_RESUME) authorize(mode, sPc)
        else LinkAuthorization.Reject(REJECT_VERSION)
        if (auth is LinkAuthorization.Reject) reject(output, auth.code)
        val psk = (auth as LinkAuthorization.Accept).psk

        val sPcKey = LinkCrypto.decodePublic(sPc)
        val ePcKey = LinkCrypto.decodePublic(ePc)
        val eph = LinkCrypto.generateKeyPair()
        val ePh = eph.public.encoded

        val th = transcript(LABEL, mode, sPc, ePc, identity.publicBytes, ePh)
        val ikm = LinkCrypto.ecdh(eph.private, ePcKey) +
            LinkCrypto.ecdh(eph.private, sPcKey) +
            LinkCrypto.ecdh(identity.privateKey, ePcKey) +
            psk
        val keys = derive(th, ikm)

        output.writeByte(OK)
        writeBlob(output, identity.publicBytes)
        writeBlob(output, ePh)
        output.write(LinkCrypto.hmac(keys.confirm, PHONE, th))
        output.flush()

        val confirmPc = ByteArray(CONFIRM_BYTES).also { input.readFully(it) }
        if (!LinkCrypto.equal(confirmPc, LinkCrypto.hmac(keys.confirm, PC, th)))
            throw LinkHandshakeException("pc confirmation failed")

        socket.soTimeout = LinkProtocol.READ_TIMEOUT_MS
        return LinkChannel(input, output, keys.s2c, keys.c2s, sPc, mode)
    }
}
