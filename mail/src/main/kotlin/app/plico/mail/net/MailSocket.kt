package app.plico.mail.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Implicit-TLS connection (IMAP 993, SMTP 465). TLS 1.3 and 1.2 only, SNI, endpoint identification
 * and an explicit [SocketHooks.verifyHostname] check. No STARTTLS and no cleartext.
 */
class MailSocket private constructor(private val socket: SSLSocket) : MailStream {
    private val closed = AtomicBoolean()
    private var compressed = false

    @Volatile
    override var input: InputStream = BufferedInputStream(socket.inputStream, BUFFER_SIZE)
        private set

    @Volatile
    override var output: OutputStream = BufferedOutputStream(socket.outputStream, BUFFER_SIZE)
        private set

    override var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    override val encrypted: Boolean get() = true

    /** Call after the server accepted COMPRESS. Bytes that the server sent after its reply stay readable. */
    override fun startCompression() {
        check(!compressed) { "Compression already started" }
        compressed = true
        output.flush()
        input = Deflate.inflating(input)
        output = Deflate.deflating(socket.outputStream)
    }

    override fun startTls(): Unit = throw UnsupportedOperationException("STARTTLS is not supported yet")

    // The Inflater and Deflater are not ended here, because another thread can still use them. GC frees them.
    override fun close() {
        if (closed.compareAndSet(false, true)) socket.closeQuietly()
    }

    companion object {
        private const val BUFFER_SIZE = 8192

        fun open(
            host: String,
            port: Int,
            hooks: SocketHooks,
            connectTimeoutMs: Int = 20_000,
            readTimeoutMs: Int = 60_000,
            factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        ): MailSocket {
            val socket = factory.createSocket() as SSLSocket
            try {
                // An unresolved host name keeps SNI and certificate transparency checks on the real name.
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                hooks.onConnected(socket)
                socket.sslParameters = socket.sslParameters.apply {
                    protocols = tlsProtocols(socket.supportedProtocols)
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(host))
                }
                socket.soTimeout = readTimeoutMs
                socket.startHandshake()
                if (!hooks.verifyHostname(host, socket.session)) {
                    throw SSLPeerUnverifiedException("Hostname verification failed")
                }
                return MailSocket(socket)
            } catch (e: Throwable) {
                socket.closeQuietly()
                throw e
            }
        }

        /** TLS 1.3 and 1.2, as far as the platform supports them. */
        internal fun tlsProtocols(supported: Array<String>): Array<String> =
            arrayOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
                .ifEmpty { throw SSLException("Neither TLS 1.3 nor TLS 1.2 is supported") }

        private fun SSLSocket.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
            }
        }
    }
}

/** RFC 4978 raw DEFLATE in both directions. */
internal object Deflate {
    private const val BUFFER_SIZE = 8192

    /** Wraps the existing buffered [input], so that bytes it has already read ahead are not lost. */
    fun inflating(input: InputStream): InputStream =
        BufferedInputStream(NetworkInflaterInputStream(input), BUFFER_SIZE)

    /** Every flush() of the result reaches the deflater as a SYNC_FLUSH. */
    fun deflating(output: OutputStream): OutputStream = BufferedOutputStream(
        DeflaterOutputStream(output, Deflater(Deflater.DEFAULT_COMPRESSION, true), BUFFER_SIZE, true),
        BUFFER_SIZE,
    )

    /**
     * [InflaterInputStream.available] answers 1 until the end of the stream. A [BufferedInputStream] on
     * top then reads on after a complete server reply and blocks on bytes that do not come.
     */
    private class NetworkInflaterInputStream(input: InputStream) :
        InflaterInputStream(input, Inflater(true), BUFFER_SIZE) {
        override fun available(): Int = 0
    }
}
