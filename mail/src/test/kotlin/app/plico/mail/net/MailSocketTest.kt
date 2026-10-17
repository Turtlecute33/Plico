package app.plico.mail.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MailSocketTest {

    private class Hooks(private val accept: Boolean = true) : SocketHooks {
        val events = mutableListOf<String>()
        var socket: Socket? = null
        var session: SSLSession? = null

        override fun onConnected(socket: Socket) {
            events += "connected"
            this.socket = socket
        }

        override fun verifyHostname(host: String, session: SSLSession): Boolean {
            events += "verify $host"
            this.session = session
            return accept
        }
    }

    @Test
    fun `tls protocols keep only 1_3 and 1_2 in that order`() {
        assertContentEquals(
            arrayOf("TLSv1.3", "TLSv1.2"),
            MailSocket.tlsProtocols(arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3", "SSLv3")),
        )
        assertContentEquals(arrayOf("TLSv1.2"), MailSocket.tlsProtocols(arrayOf("TLSv1.1", "TLSv1.2")))
        assertFailsWith<SSLException> { MailSocket.tlsProtocols(arrayOf("TLSv1", "TLSv1.1")) }
    }

    @Test(timeout = 30_000)
    fun `handshake uses TLS 1_3, SNI and endpoint identification`() {
        val sni = AtomicReference<List<SNIServerName>>()
        TlsServer("localhost") { s ->
            s.startHandshake()
            sni.set((s.session as ExtendedSSLSession).requestedServerNames)
            s.outputStream.write("* OK ready\r\n".toByteArray())
            s.outputStream.flush()
            val line = s.inputStream.readCrlfLine()
            s.outputStream.write("echo $line\r\n".toByteArray())
            s.outputStream.flush()
            runCatching { s.inputStream.read() }
        }.use { server ->
            val hooks = Hooks()
            open(server.port, hooks, readTimeoutMs = 7_000).use { socket ->
                assertEquals(listOf("connected", "verify localhost"), hooks.events)
                assertEquals("TLSv1.3", hooks.session!!.protocol)
                val ssl = hooks.socket as SSLSocket
                assertEquals(setOf("TLSv1.3", "TLSv1.2"), ssl.enabledProtocols.toSet())
                assertEquals("HTTPS", ssl.sslParameters.endpointIdentificationAlgorithm)

                assertEquals(7_000, socket.readTimeoutMs)
                socket.readTimeoutMs = 0
                assertEquals(0, ssl.soTimeout)
                socket.readTimeoutMs = 7_000

                assertEquals("* OK ready", socket.input.readCrlfLine())
                socket.output.write("hello\r\n".toByteArray())
                socket.output.flush()
                assertEquals("echo hello", socket.input.readCrlfLine())
            }
            server.join()
        }
        assertEquals(listOf<SNIServerName>(SNIHostName("localhost")), sni.get())
    }

    @Test(timeout = 30_000)
    fun `hostname hook rejection closes the socket`() {
        TlsServer("localhost") { s -> s.startHandshake() }.use { server ->
            val hooks = Hooks(accept = false)
            assertFailsWith<SSLPeerUnverifiedException> {
                MailSocket.open("localhost", server.port, hooks, factory = TestTls.clientFactory("localhost"))
            }
            assertEquals(listOf("connected", "verify localhost"), hooks.events)
            assertTrue(hooks.socket!!.isClosed)
        }
    }

    @Test(timeout = 30_000)
    fun `certificate for another name fails the handshake`() {
        TlsServer("other.example") { s -> s.startHandshake() }.use { server ->
            val hooks = Hooks()
            assertFailsWith<SSLHandshakeException> {
                MailSocket.open("localhost", server.port, hooks, factory = TestTls.clientFactory("other.example"))
            }
            assertNull(hooks.session)
            assertTrue(hooks.socket!!.isClosed)
        }
    }

    @Test(timeout = 30_000)
    fun `compression round trip keeps bytes read ahead with the OK`() {
        val payload = randomText(200_000)
        TlsServer("localhost") { s ->
            s.startHandshake()
            val rawIn = BufferedInputStream(s.inputStream)
            val rawOut = BufferedOutputStream(s.outputStream)
            assertEquals("C1 COMPRESS DEFLATE", rawIn.readCrlfLine())
            // The OK and the first compressed bytes go out in one TLS record.
            rawOut.write("C1 OK\r\n".toByteArray())
            val zOut = Deflate.deflating(rawOut)
            zOut.write("* compressed hello\r\n".toByteArray())
            zOut.flush()
            val zIn = Deflate.inflating(rawIn)
            zOut.write(zIn.readExactly(payload.size))
            zOut.flush()
            assertEquals("C2 LOGOUT", zIn.readCrlfLine())
            zOut.write("C2 OK bye\r\n".toByteArray())
            zOut.flush()
        }.use { server ->
            open(server.port).use { socket ->
                socket.output.write("C1 COMPRESS DEFLATE\r\n".toByteArray())
                socket.output.flush()
                assertEquals("C1 OK", socket.input.readCrlfLine())
                socket.startCompression()
                assertEquals("* compressed hello", socket.input.readCrlfLine())
                socket.output.write(payload)
                socket.output.flush()
                assertContentEquals(payload, socket.input.readExactly(payload.size))
                socket.output.write("C2 LOGOUT\r\n".toByteArray())
                socket.output.flush()
                assertEquals("C2 OK bye", socket.input.readCrlfLine())
                assertFailsWith<IllegalStateException> { socket.startCompression() }
            }
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `close from another thread ends a blocked read and is idempotent`() {
        TlsServer("localhost") { s ->
            s.startHandshake()
            runCatching { s.inputStream.read() }
        }.use { server ->
            val socket = open(server.port, readTimeoutMs = 0)
            val result = AtomicReference<Any>()
            val reader = thread {
                result.set(
                    try {
                        socket.input.read()
                    } catch (e: IOException) {
                        e
                    },
                )
            }
            Thread.sleep(200)
            assertTrue(reader.isAlive, "read should block until close")
            socket.close()
            socket.close()
            reader.join(5_000)
            assertFalse(reader.isAlive)
            val value = result.get()
            assertTrue(value is IOException || value == -1, "unexpected read result $value")
        }
    }

    // STARTTLS and handshake failures

    @Test(timeout = 30_000)
    fun `STARTTLS upgrade uses TLS 1_3, SNI and the hostname hook`() {
        val sni = AtomicReference<List<SNIServerName>>()
        StartTlsServer("localhost") { c ->
            c.send("* OK ready\r\n")
            assertEquals("A1 STARTTLS", c.line())
            c.send("A1 OK Begin TLS\r\n")
            val ssl = c.upgrade()
            sni.set((ssl.session as ExtendedSSLSession).requestedServerNames)
            val line = c.line()
            c.send("echo $line\r\n")
            c.drain()
        }.use { server ->
            val hooks = Hooks()
            open(server.port, hooks, readTimeoutMs = 7_000, tls = TlsMode.STARTTLS).use { socket ->
                assertFalse(socket.encrypted)
                assertEquals(listOf("connected"), hooks.events)
                assertFalse(hooks.socket is SSLSocket, "STARTTLS starts on a plain socket")
                assertEquals(7_000, socket.readTimeoutMs)
                assertEquals("* OK ready", socket.input.readCrlfLine())
                socket.output.write("A1 STARTTLS\r\n".toByteArray())
                socket.output.flush()
                assertEquals("A1 OK Begin TLS", socket.input.readCrlfLine())

                socket.startTls()
                assertTrue(socket.encrypted)
                assertEquals(listOf("connected", "verify localhost"), hooks.events)
                assertEquals("TLSv1.3", hooks.session!!.protocol)
                assertEquals(7_000, socket.readTimeoutMs)
                socket.output.write("hello\r\n".toByteArray())
                socket.output.flush()
                assertEquals("echo hello", socket.input.readCrlfLine())
                assertFailsWith<IllegalStateException> { socket.startTls() }
            }
            server.join()
        }
        assertEquals(listOf<SNIServerName>(SNIHostName("localhost")), sni.get())
    }

    @Test(timeout = 30_000)
    fun `compression works after STARTTLS`() {
        StartTlsServer("localhost") { c ->
            c.upgrade()
            assertEquals("C1 COMPRESS DEFLATE", c.line())
            c.send("C1 OK\r\n")
            val zOut = Deflate.deflating(c.output)
            val zIn = Deflate.inflating(BufferedInputStream(c.input))
            assertEquals("C2 NOOP", zIn.readCrlfLine())
            zOut.write("C2 OK compressed\r\n".toByteArray())
            zOut.flush()
            zIn.drainQuietly()
        }.use { server ->
            open(server.port, tls = TlsMode.STARTTLS).use { socket ->
                socket.startTls()
                socket.output.write("C1 COMPRESS DEFLATE\r\n".toByteArray())
                socket.output.flush()
                assertEquals("C1 OK", socket.input.readCrlfLine())
                socket.startCompression()
                socket.output.write("C2 NOOP\r\n".toByteArray())
                socket.output.flush()
                assertEquals("C2 OK compressed", socket.input.readCrlfLine())
                assertFailsWith<IllegalStateException> { socket.startTls() }
            }
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `bytes after the STARTTLS reply fail and close the socket`() {
        StartTlsServer("localhost") { c ->
            c.send("* OK ready\r\n")
            c.line()
            // One write: the reply and an injected line arrive together.
            c.send("A1 OK Begin TLS\r\n* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n")
            c.drain()
        }.use { server ->
            val hooks = Hooks()
            val socket = open(server.port, hooks, tls = TlsMode.STARTTLS)
            assertEquals("* OK ready", socket.input.readCrlfLine())
            socket.output.write("A1 STARTTLS\r\n".toByteArray())
            socket.output.flush()
            assertEquals("A1 OK Begin TLS", socket.input.readCrlfLine())
            val deadline = System.nanoTime() + 5_000_000_000
            while (socket.input.available() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertFailsWith<StartTlsException> { socket.startTls() }
            assertFalse(socket.encrypted)
            assertTrue(hooks.socket!!.isClosed)
            assertEquals(listOf("connected"), hooks.events, "no handshake")
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `hostname hook rejection after STARTTLS closes the socket`() {
        StartTlsServer("localhost") { c ->
            runCatching { c.upgrade() }
            c.drain()
        }.use { server ->
            val hooks = Hooks(accept = false)
            val socket = open(server.port, hooks, tls = TlsMode.STARTTLS)
            assertFailsWith<SSLPeerUnverifiedException> { socket.startTls() }
            assertFalse(socket.encrypted)
            assertTrue(hooks.socket!!.isClosed)
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `certificate for another name fails the STARTTLS handshake as a certificate failure`() {
        StartTlsServer("other.example") { c ->
            runCatching { c.upgrade() }
            c.drain()
        }.use { server ->
            val hooks = Hooks()
            val socket = open(server.port, hooks, tls = TlsMode.STARTTLS, factory = TestTls.clientFactory("other.example"))
            val e = assertFailsWith<SSLHandshakeException> { socket.startTls() }
            assertTrue(e.hasCause<CertificateException>(), "certificate failure in the cause chain")
            assertNull(hooks.session)
            assertFalse(socket.encrypted)
            assertTrue(hooks.socket!!.isClosed)
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `an untrusted certificate is a certificate failure, not a TlsHandshakeException`() {
        TlsServer("other.example") { s -> runCatching { s.startHandshake() } }.use { server ->
            val e = assertFailsWith<SSLException> {
                MailSocket.open("localhost", server.port, Hooks(), factory = TestTls.clientFactory("localhost"))
            }
            assertTrue(e.hasCause<CertificateException>(), "certificate failure in the cause chain: $e")
        }
        StartTlsServer("other.example") { c ->
            runCatching { c.upgrade() }
            c.drain()
        }.use { server ->
            val socket = open(server.port, tls = TlsMode.STARTTLS)
            val e = assertFailsWith<SSLException> { socket.startTls() }
            assertTrue(e.hasCause<CertificateException>(), "certificate failure in the cause chain: $e")
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `implicit TLS against a plaintext server is a TlsHandshakeException`() {
        StartTlsServer("localhost") { c ->
            c.send("* OK [CAPABILITY IMAP4rev1 STARTTLS] plaintext server\r\n")
            c.drain()
        }.use { server ->
            val hooks = Hooks()
            val e = assertFailsWith<TlsHandshakeException> { open(server.port, hooks) }
            assertTrue(e.cause is SSLException)
            assertFalse(e.hasCause<CertificateException>())
            assertTrue(hooks.socket!!.isClosed)
            server.join()
        }
    }

    @Test(timeout = 30_000)
    fun `STARTTLS mode against a silent server times out at the greeting`() {
        StartTlsServer("localhost") { c -> c.drain() }.use { server ->
            open(server.port, tls = TlsMode.STARTTLS, readTimeoutMs = 300).use { socket ->
                assertEquals(300, socket.readTimeoutMs)
                assertFailsWith<SocketTimeoutException> { socket.input.read() }
            }
            server.join()
        }
    }

    @Test
    fun `the read timeout before TLS is at most 20 seconds`() {
        assertEquals(20_000, MailSocket.plaintextTimeout(60_000))
        assertEquals(20_000, MailSocket.plaintextTimeout(0))
        assertEquals(20_000, MailSocket.plaintextTimeout(20_000))
        assertEquals(500, MailSocket.plaintextTimeout(500))
    }

    @Test
    fun `a host that is not ASCII is refused before any connection`() {
        for (host in listOf("bücher.example", "", "a b.example", "mail.example\n")) {
            val hooks = Hooks()
            assertFailsWith<IllegalArgumentException>(host) { MailSocket.open(host, 993, hooks) }
            assertEquals(emptyList(), hooks.events)
        }
    }

    private fun open(
        port: Int,
        hooks: SocketHooks = Hooks(),
        readTimeoutMs: Int = 60_000,
        tls: TlsMode = TlsMode.IMPLICIT,
        factory: javax.net.ssl.SSLSocketFactory = TestTls.clientFactory("localhost"),
    ): MailSocket = MailSocket.open("localhost", port, hooks, tls, readTimeoutMs = readTimeoutMs, factory = factory)

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.take(16).any { it is T }

    private fun java.io.InputStream.drainQuietly() {
        runCatching { while (read() != -1) Unit }
    }

    private fun randomText(size: Int): ByteArray {
        val random = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJ0123456789\r\n".toByteArray()
        return ByteArray(size) { alphabet[random.nextInt(alphabet.size)] }
    }
}
