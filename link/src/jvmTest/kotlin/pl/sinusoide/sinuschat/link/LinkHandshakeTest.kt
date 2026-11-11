package pl.sinusoide.sinuschat.link

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkHandshakeTest {

    private val phone = LinkIdentity.generate()
    private val pc = LinkIdentity.generate()

    private fun <T> withServer(
        authorize: (Int, ByteArray) -> LinkAuthorization,
        tokens: List<ByteArray> = emptyList(),
        client: (Socket) -> T
    ): Pair<Result<LinkChannel>, Result<T>> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serverSide = CompletableFuture<Result<LinkChannel>>()
            thread {
                server.accept().use { s ->
                    val r = runCatching { LinkHandshake.accept(s, phone, { tokens }, authorize) }
                    serverSide.complete(r)
                    r.getOrNull()?.let { ch -> runCatching { ch.write(ch.read() ?: LinkFrame.Bye("none")) } }
                }
            }
            val clientResult = Socket(server.inetAddress, server.localPort).use { s -> runCatching { client(s) } }
            return serverSide.get(5, TimeUnit.SECONDS) to clientResult
        }
    }

    @Test
    fun pairingThenEcho() {
        val code = PairingCode.create(pc.publicBytes)
        val decoded = assertNotNull(PairingCode.decode(code.encode()))
        val (server, client) = withServer({ mode, key ->
            if (mode == LinkHandshake.MODE_PAIR && LinkCrypto.equal(key, decoded.pcKey)) LinkAuthorization.Accept(decoded.token)
            else LinkAuthorization.Reject(LinkHandshake.REJECT_NOT_PAIRING)
        }, listOf(decoded.token)) { s ->
            val ch = LinkHandshake.connect(s, pc, LinkMode.Pair(code.token))
            ch.write(LinkFrame.Hello("PC", "test"))
            ch.peerKey to ch.read()
        }
        assertTrue(server.isSuccess)
        val (peerKey, echo) = client.getOrThrow()
        assertContentEquals(phone.publicBytes, peerKey)
        assertContentEquals(pc.publicBytes, server.getOrThrow().peerKey)
        assertEquals(LinkFrame.Hello("PC", "test"), echo)
    }

    @Test
    fun resumeWithKnownKeys() {
        val (server, client) = withServer({ mode, key ->
            if (mode == LinkHandshake.MODE_RESUME && LinkCrypto.equal(key, pc.publicBytes)) LinkAuthorization.Accept(ByteArray(0))
            else LinkAuthorization.Reject(LinkHandshake.REJECT_UNKNOWN_DEVICE)
        }) { s ->
            val ch = LinkHandshake.connect(s, pc, LinkMode.Resume(phone.publicBytes))
            ch.write(LinkFrame.Ping(7))
            ch.read()
        }
        assertTrue(server.isSuccess)
        assertEquals(LinkFrame.Ping(7), client.getOrThrow())
    }

    @Test
    fun unknownPcIsRejected() {
        val stranger = LinkIdentity.generate()
        val (server, client) = withServer({ _, key ->
            if (LinkCrypto.equal(key, pc.publicBytes)) LinkAuthorization.Accept(ByteArray(0))
            else LinkAuthorization.Reject(LinkHandshake.REJECT_UNKNOWN_DEVICE)
        }) { s -> LinkHandshake.connect(s, stranger, LinkMode.Resume(phone.publicBytes)) }
        assertTrue(server.isFailure)
        val e = assertFailsWith<LinkRejected> { client.getOrThrow() }
        assertEquals(LinkHandshake.REJECT_UNKNOWN_DEVICE, e.code)
    }

    @Test
    fun wrongPairingTokenFails() {
        val code = PairingCode.create(pc.publicBytes)
        val (server, client) = withServer({ _, _ -> LinkAuthorization.Accept(code.token) }, listOf(code.token)) { s ->
            LinkHandshake.connect(s, pc, LinkMode.Pair(LinkCrypto.randomBytes(16)))
        }
        val e = assertFailsWith<LinkRejected> { client.getOrThrow() }
        assertEquals(LinkHandshake.REJECT_NOT_PAIRING, e.code)
        assertTrue(server.isFailure)
    }

    @Test
    fun impostorPhoneFailsResume() {
        val impostor = LinkIdentity.generate()
        val (_, client) = withServer({ _, _ -> LinkAuthorization.Accept(ByteArray(0)) }) { s ->
            LinkHandshake.connect(s, pc, LinkMode.Resume(impostor.publicBytes))
        }
        assertFailsWith<java.io.IOException> { client.getOrThrow() }
    }

    @Test
    fun identityRoundTrip() {
        val restored = LinkIdentity.restore(pc.privateBytes, pc.publicBytes)
        assertEquals(pc.id, restored.id)
        assertEquals(LinkProtocol.ID_LENGTH, pc.id.length)
    }

    @Test
    fun unknownFrameTypeIsIgnored() {
        assertEquals(null, LinkFrame.decode("""{"t":"future_thing","x":1}"""))
        assertEquals(LinkFrame.Pong(3), LinkFrame.decode("""{"t":"pong","id":3,"extra":true}"""))
    }
}
