package app.plico.account

import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.net.MailStream
import app.plico.mail.smtp.SmtpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A server that has all its replies ready; the test reads what the client wrote afterwards. */
internal class CannedStream(vararg serverLines: String) : MailStream {
    override val input = ByteArrayInputStream(serverLines.joinToString("") { "$it\r\n" }.toByteArray())
    override val output = ByteArrayOutputStream()
    override var readTimeoutMs = 0
    override val encrypted = true

    override fun startCompression() = error("Not used")

    override fun startTls() = error("Not used")

    override fun close() {}

    /** The lines the client wrote, without CRLF. */
    fun written(): List<String> = output.toString(Charsets.UTF_8).split("\r\n").dropLast(1)

    /** The decoded SASL initial response of the first line, e.g. `A1 AUTHENTICATE PLAIN <base64>`. */
    fun saslResponse(): String = String(Base64.getDecoder().decode(written().first().substringAfterLast(' ')), Charsets.UTF_8)
}

internal fun imap(vararg serverLines: String): Pair<ImapClient, CannedStream> {
    val stream = CannedStream(*serverLines)
    return ImapClient(ImapConnection(stream)) to stream
}

internal fun smtp(vararg serverLines: String): Pair<SmtpClient, CannedStream> {
    val stream = CannedStream(*serverLines)
    return SmtpClient(stream) to stream
}

internal const val IMAP_OK = "A1 OK [CAPABILITY IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH] Logged in"
internal const val SMTP_OK = "235 2.7.0 Accepted"
