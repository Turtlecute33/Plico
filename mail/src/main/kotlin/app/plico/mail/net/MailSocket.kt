package app.plico.mail.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
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
 * Implicit TLS or STARTTLS (always required). TLS 1.3/1.2, SNI, endpoint identification, explicit hostname
 * check ([SocketHooks.verifyHostname]). No cleartext session ever carries a command after STARTTLS or any
 * credential: the protocol clients check [encrypted] before they send a secret.
 */
class MailSocket private constructor(
    @Volatile private var socket: Socket,
    private val host: String,
    private val port: Int,
    private val hooks: SocketHooks,
    private val factory: SSLSocketFactory,
    private val readTimeout: Int,
) : MailStream {
    private val closed = AtomicBoolean()
    private var compressed = false

    @Volatile
    private var tls = socket is SSLSocket

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

    override val encrypted: Boolean get() = tls

    /** Call after the server accepted COMPRESS. Bytes that the server sent after its reply stay readable. */
    override fun startCompression() {
        check(!compressed) { "Compression already started" }
        compressed = true
        output.flush()
        input = Deflate.inflating(input)
        output = Deflate.deflating(socket.outputStream)
    }

    /**
     * Call after the server accepted STARTTLS. Any byte that the server sent after its reply is an injection
     * (or pipelining, which STARTTLS forbids): [StartTlsException]. The plaintext buffers are dropped unread.
     * Bytes that arrive after the check go into the TLS layer and fail the handshake. Any failure closes the
     * connection.
     */
    override fun startTls() {
        check(!tls && !compressed) { "STARTTLS after TLS or compression" }
        val raw = socket
        try {
            if (input.available() > 0) throw StartTlsException("Data after the STARTTLS reply")
            // autoClose: closing the TLS socket closes the raw one. A close() from another thread during the
            // handshake closes the raw socket, which ends the handshake.
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            secure(ssl, host, hooks, readTimeout)
            input = BufferedInputStream(ssl.inputStream, BUFFER_SIZE)
            output = BufferedOutputStream(ssl.outputStream, BUFFER_SIZE)
            socket = ssl
            tls = true
        } catch (e: Throwable) {
            close()
            raw.closeQuietly()
            throw e
        }
        if (closed.get()) socket.closeQuietly()
    }

    // The Inflater and Deflater are not ended here, because another thread can still use them. GC frees them.
    override fun close() {
        if (closed.compareAndSet(false, true)) socket.closeQuietly()
    }

    companion object {
        private const val BUFFER_SIZE = 8192

        /** Read timeout before TLS is up: a server that waits for a ClientHello sends no greeting. */
        private const val PLAINTEXT_TIMEOUT_MS = 20_000

        /**
         * Connects to [host] (ASCII; callers normalise IDN names) and returns a stream that is [encrypted] for
         * [TlsMode.IMPLICIT]. For [TlsMode.STARTTLS] the stream is plaintext until [startTls]; its read timeout
         * is at most 20 s until then.
         */
        fun open(
            host: String,
            port: Int,
            hooks: SocketHooks,
            tls: TlsMode = TlsMode.IMPLICIT,
            connectTimeoutMs: Int = 20_000,
            readTimeoutMs: Int = 60_000,
            factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        ): MailSocket {
            require(host.isNotEmpty() && host.all { it in '!'..'~' }) { "Host is not an ASCII name" }
            val socket = if (tls == TlsMode.IMPLICIT) factory.createSocket() as SSLSocket else Socket()
            try {
                // An unresolved host name keeps SNI and certificate transparency checks on the real name.
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                hooks.onConnected(socket)
                if (socket is SSLSocket) {
                    secure(socket, host, hooks, readTimeoutMs)
                } else {
                    socket.soTimeout = plaintextTimeout(readTimeoutMs)
                }
                return MailSocket(socket, host, port, hooks, factory, readTimeoutMs)
            } catch (e: Throwable) {
                socket.closeQuietly()
                throw e
            }
        }

        /** TLS 1.3 and 1.2, as far as the platform supports them. */
        internal fun tlsProtocols(supported: Array<String>): Array<String> =
            arrayOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
                .ifEmpty { throw SSLException("Neither TLS 1.3 nor TLS 1.2 is supported") }

        /** 0 (no timeout) or more than 20 s become 20 s. */
        internal fun plaintextTimeout(readTimeoutMs: Int): Int =
            if (readTimeoutMs in 1..PLAINTEXT_TIMEOUT_MS) readTimeoutMs else PLAINTEXT_TIMEOUT_MS

        /**
         * The handshake with the checks that both TLS modes share. A handshake failure that is not about the
         * certificate (e.g. a plaintext server on this port) becomes [TlsHandshakeException]; certificate
         * failures stay as they are, so that callers find the certificate in the cause chain.
         */
        private fun secure(ssl: SSLSocket, host: String, hooks: SocketHooks, readTimeoutMs: Int) {
            ssl.sslParameters = ssl.sslParameters.apply {
                protocols = tlsProtocols(ssl.supportedProtocols)
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            ssl.soTimeout = readTimeoutMs
            try {
                ssl.startHandshake()
            } catch (e: SSLException) {
                throw if (certificateFailure(e)) e else TlsHandshakeException(e)
            }
            if (!hooks.verifyHostname(host, ssl.session)) {
                throw SSLPeerUnverifiedException("Hostname verification failed")
            }
        }

        private fun certificateFailure(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(MAX_CAUSES)
                .any { it is CertificateException || it is CertPathValidatorException }

        private const val MAX_CAUSES = 16

        private fun Socket.closeQuietly() {
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
