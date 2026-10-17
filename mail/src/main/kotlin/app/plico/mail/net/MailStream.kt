package app.plico.mail.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A connected byte stream to a mail server. [MailSocket] is the real one; tests use fakes. */
interface MailStream : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Socket read timeout in milliseconds. 0 means no timeout (used during IDLE). */
    var readTimeoutMs: Int

    /** True once TLS is up (always for implicit TLS). Credentials and tokens are sent only when true. */
    val encrypted: Boolean

    /** Starts RFC 4978 compression (raw DEFLATE both ways). Every flush of [output] is a SYNC_FLUSH. */
    fun startCompression()

    /**
     * Upgrades to TLS after the server accepted STARTTLS (IMAP OK / SMTP 220). Throws [StartTlsException]
     * when bytes follow that reply (pipelining or injection); every buffered plaintext byte is dropped.
     */
    fun startTls()

    /** Idempotent and safe to call from any thread. */
    override fun close()
}
