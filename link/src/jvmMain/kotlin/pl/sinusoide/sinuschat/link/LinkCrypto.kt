package pl.sinusoide.sinuschat.link

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object LinkCrypto {

    private val random = SecureRandom()

    private val curveOrder by lazy { (generateKeyPair().public as ECPublicKey).params.order }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun generateKeyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun decodePublic(bytes: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        require(key is ECPublicKey && key.params.order == curveOrder) { "bad key" }
        return key
    }

    fun decodePrivate(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(pub, true)
            generateSecret()
        }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            block = hmac(prk, block, info, byteArrayOf(i.toByte()))
            out.write(block)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun fingerprint(publicKey: ByteArray): String =
        sha256(publicKey).copyOf(LinkProtocol.ID_LENGTH / 2).joinToString("") { "%02x".format(it) }

    fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) {
            out.write(p.size ushr 24); out.write(p.size ushr 16); out.write(p.size ushr 8); out.write(p.size)
            out.write(p)
        }
        return out.toByteArray()
    }

    fun sealOnce(key: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(plain)
        }

    fun openOnce(key: ByteArray, sealed: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(sealed)
        }
    } catch (_: Exception) {
        null
    }

    class FrameCipher(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var counter = 0L

        private fun nonce(): ByteArray {
            val n = ByteArray(12)
            var c = counter++
            for (i in 11 downTo 4) { n[i] = c.toByte(); c = c ushr 8 }
            return n
        }

        fun seal(plain: ByteArray): ByteArray {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce()))
            return cipher.doFinal(plain)
        }

        fun open(sealed: ByteArray): ByteArray {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce()))
            return cipher.doFinal(sealed)
        }
    }
}
