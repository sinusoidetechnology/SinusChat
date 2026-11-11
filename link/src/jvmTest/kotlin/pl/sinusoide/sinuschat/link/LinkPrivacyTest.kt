package pl.sinusoide.sinuschat.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinkPrivacyTest {

    private val phone = LinkIdentity.generate()
    private val pc = LinkIdentity.generate()

    private class Tap(target: Int) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val recorded = ByteArrayOutputStream()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                val a = runCatching { server.accept() }.getOrNull() ?: return@thread
                val b = Socket(InetAddress.getLoopbackAddress(), target)
                thread(isDaemon = true) { pump(a.getInputStream(), b.getOutputStream()) }
                pump(b.getInputStream(), a.getOutputStream())
            }
        }

        private fun pump(input: InputStream, output: OutputStream) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(recorded) { recorded.write(buf, 0, n) }
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }

        fun wire(): ByteArray = synchronized(recorded) { recorded.toByteArray() }

        override fun close() = server.close()
    }

    private fun ByteArray.indexOf(part: ByteArray): Int {
        outer@ for (i in 0..size - part.size) {
            for (j in part.indices) if (this[i + j] != part[j]) continue@outer
            return i
        }
        return -1
    }

    private fun assertHidden(wire: ByteArray, identity: LinkIdentity) {
        val key = identity.publicBytes
        assertEquals(-1, wire.indexOf(key))
        assertEquals(-1, wire.indexOf(key.copyOfRange(key.size - 64, key.size)))
        assertEquals(-1, wire.indexOf(identity.id.toByteArray()))
    }

    private fun session(
        tokens: List<ByteArray>,
        authorize: (Int, ByteArray) -> LinkAuthorization,
        client: (Socket) -> LinkChannel
    ): ByteArray {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { phoneServer ->
            val done = CompletableFuture<Unit>()
            thread(isDaemon = true) {
                phoneServer.accept().use { s ->
                    runCatching {
                        val ch = LinkHandshake.accept(s, phone, { tokens }, authorize)
                        ch.write(ch.read() ?: LinkFrame.Bye("none"))
                    }
                    done.complete(Unit)
                }
            }
            Tap(phoneServer.localPort).use { tap ->
                Socket(InetAddress.getLoopbackAddress(), tap.port).use { s ->
                    val ch = client(s)
                    ch.write(LinkFrame.Hello("Komputer", "test"))
                    assertEquals(LinkFrame.Hello("Komputer", "test"), ch.read())
                }
                done.get(5, TimeUnit.SECONDS)
                return tap.wire()
            }
        }
    }

    @Test
    fun identitiesNeverTravelInClear() {
        val code = PairingCode.create(pc.publicBytes)
        val pairing = session(listOf(code.token), { mode, key ->
            if (mode == LinkHandshake.MODE_PAIR && LinkCrypto.equal(key, pc.publicBytes)) LinkAuthorization.Accept(code.token)
            else LinkAuthorization.Reject(LinkHandshake.REJECT_NOT_PAIRING)
        }) { s -> LinkHandshake.connect(s, pc, LinkMode.Pair(code.token)) }
        assertHidden(pairing, pc)
        assertHidden(pairing, phone)

        val resume = session(emptyList(), { mode, key ->
            if (mode == LinkHandshake.MODE_RESUME && LinkCrypto.equal(key, pc.publicBytes)) LinkAuthorization.Accept(ByteArray(0))
            else LinkAuthorization.Reject(LinkHandshake.REJECT_UNKNOWN_DEVICE)
        }) { s -> LinkHandshake.connect(s, pc, LinkMode.Resume(phone.publicBytes)) }
        assertHidden(resume, pc)
        assertHidden(resume, phone)
    }

    @Test
    fun legacyPcStillConnects() {
        val wire = session(emptyList(), { _, key ->
            if (LinkCrypto.equal(key, pc.publicBytes)) LinkAuthorization.Accept(ByteArray(0))
            else LinkAuthorization.Reject(LinkHandshake.REJECT_UNKNOWN_DEVICE)
        }) { s -> LinkHandshake.connect(s, pc, LinkMode.Resume(phone.publicBytes), legacy = true) }
        assertTrue(wire.indexOf(pc.publicBytes) >= 0)
    }

    @Test
    fun framesArePaddedAndStillDecode() {
        val key = LinkCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val writer = LinkChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(wire),
            key, key, ByteArray(0), LinkHandshake.MODE_RESUME)
        val small = listOf(LinkFrame.Ping(1), LinkFrame.Hello("Komputer", "SinusChat Desktop"), LinkFrame.Ping(123456789))
        val sizes = small.map { f -> val before = wire.size(); writer.write(f); wire.size() - before }
        assertEquals(1, sizes.distinct().size)
        val big = LinkFrame.Hello("x".repeat(70_000), "test")
        val before = wire.size()
        writer.write(big)
        assertEquals(0, (wire.size() - before - 4 - 16) % (64 * 1024))

        val reader = LinkChannel(DataInputStream(ByteArrayInputStream(wire.toByteArray())), DataOutputStream(ByteArrayOutputStream()),
            key, key, ByteArray(0), LinkHandshake.MODE_RESUME)
        (small + big).forEach { assertEquals(it, reader.read()) }
    }

    @Test
    fun beaconMatchesOnlyPairedDevices() {
        val other = LinkIdentity.generate()
        val first = LinkBeacon.create(phone, listOf(pc.publicBytes))
        val second = LinkBeacon.create(phone, listOf(pc.publicBytes))
        assertNotEquals(first, second)
        assertTrue(LinkBeacon.matches(pc, phone.publicBytes, first))
        assertTrue(LinkBeacon.matches(pc, phone.publicBytes, second))
        assertFalse(LinkBeacon.matches(other, phone.publicBytes, first))
        assertFalse(LinkBeacon.matches(pc, other.publicBytes, first))
        assertFalse(LinkBeacon.matches(pc, phone.publicBytes, phone.id))
        assertFalse(first.contains(phone.id))
        assertEquals(LinkBeacon.create(phone, emptyList()).length, first.length)
        assertTrue(first.length + LinkProtocol.TXT_ID.length + 1 <= 255)
        val many = LinkBeacon.create(phone, List(30) { LinkIdentity.generate().publicBytes })
        assertTrue(many.length + LinkProtocol.TXT_ID.length + 1 <= 255)
    }
}
