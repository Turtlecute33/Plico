package app.plico.mail.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A connected, encrypted byte stream to a mail server. [MailSocket] is the real one; tests use fakes. */
interface MailStream : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Socket read timeout in milliseconds. 0 means no timeout (used during IDLE). */
    var readTimeoutMs: Int

    /** Starts RFC 4978 compression (raw DEFLATE both ways). Every flush of [output] is a SYNC_FLUSH. */
    fun startCompression()

    /** Idempotent and safe to call from any thread. */
    override fun close()
}
