package app.plico.mail.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/** Self-signed test certificates, made with the JDK keytool at test time. Nothing is stored. */
internal object TestTls {
    private const val PASSWORD = "test-only"
    private val stores = HashMap<String, KeyStore>()

    /** A PKCS12 key store with one EC P-256 key whose certificate has SAN `dns:<name>`. */
    @Synchronized
    fun keyStore(name: String): KeyStore = stores.getOrPut(name) { generate(name) }

    fun serverContext(name: String): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(name), PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** A client factory that trusts only the certificate made for [name]. */
    fun clientFactory(name: String): SSLSocketFactory {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore(name))
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private fun generate(name: String): KeyStore {
        val dir = Files.createTempDirectory("plico-tls").toFile()
        try {
            val file = dir.resolve("key.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=$name", "-ext", "SAN=dns:$name", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-noprompt",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** A one-connection TLS server on the loopback interface. [handler] runs on its own thread. */
internal class TlsServer(name: String, private val handler: (SSLSocket) -> Unit) : Closeable {
    private val server = TestTls.serverContext(name).serverSocketFactory
        .createServerSocket(0, 1, InetAddress.getByName("localhost")) as SSLServerSocket

    @Volatile
    private var error: Throwable? = null

    private val worker = thread(isDaemon = true, name = "tls-test-server") {
        try {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                handler(socket)
            }
        } catch (e: Throwable) {
            error = e
        }
    }

    val port: Int get() = server.localPort

    /** Waits for the handler and rethrows its failure. */
    fun join() {
        worker.join(10_000)
        check(!worker.isAlive) { "Server still running" }
        error?.let { throw AssertionError("Server failed", it) }
    }

    override fun close() {
        server.close()
        worker.join(10_000)
    }
}

/** Reads one line without its CRLF. */
internal fun InputStream.readCrlfLine(): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = read()
        check(b != -1) { "EOF" }
        if (b == '\n'.code) break
        out.write(b)
    }
    return String(out.toByteArray(), Charsets.UTF_8).removeSuffix("\r")
}

internal fun InputStream.readExactly(n: Int): ByteArray {
    val bytes = ByteArray(n)
    var read = 0
    while (read < n) {
        val count = read(bytes, read, n - read)
        check(count > 0) { "EOF after $read of $n bytes" }
        read += count
    }
    return bytes
}
