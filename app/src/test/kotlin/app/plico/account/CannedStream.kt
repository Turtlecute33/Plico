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

/**
 * A client after the greeting, so that its capabilities are known ([IMAP_GREETING]); [serverLines] answer the
 * first command. [host] is the endpoint host that credentials check before they send a token.
 */
internal fun imap(vararg serverLines: String, host: String = GMAIL_IMAP): Pair<ImapClient, CannedStream> {
    val stream = CannedStream(IMAP_GREETING, *serverLines)
    val conn = ImapConnection(stream)
    conn.greeting()
    return ImapClient(conn, host) to stream
}

/** A client after the greeting and EHLO ([SMTP_START]); the output starts empty, so [serverLines] answer the first command. */
internal fun smtp(vararg serverLines: String, host: String = GMAIL_SMTP): Pair<SmtpClient, CannedStream> {
    val stream = CannedStream(*SMTP_START, *serverLines)
    val client = SmtpClient(stream, host = host)
    client.start()
    stream.output.reset()
    return client to stream
}

internal const val IMAP_GREETING = "* OK [CAPABILITY IMAP4rev1 SASL-IR AUTH=PLAIN] ready"
private val SMTP_START = arrayOf("220 smtp.example ESMTP", "250-smtp.example", "250 AUTH PLAIN LOGIN XOAUTH2")

internal const val GMAIL_IMAP = "imap.gmail.com"
internal const val GMAIL_SMTP = "smtp.gmail.com"

internal const val IMAP_OK = "A1 OK [CAPABILITY IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH] Logged in"
internal const val SMTP_OK = "235 2.7.0 Accepted"
