package app.plico.mail.smtp

import app.plico.mail.mime.AddrSpec
import app.plico.mail.net.Endpoint
import app.plico.mail.net.MailSocket
import app.plico.mail.net.MailStream
import app.plico.mail.net.SocketHooks
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsMode
import app.plico.mail.sasl.Sasl
import app.plico.mail.smtp.SmtpException.Stage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ProtocolException
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal SMTP submission client over implicit TLS. See DESIGN.md §4.7.
 * [host] is the host of the endpoint ("" only in tests); credentials check it before they send a token.
 */
class SmtpClient(
    private val stream: MailStream,
    private val log: (String) -> Unit = {},
    val host: String = "",
) : Closeable {

    /** EHLO keywords (upper-case) to their parameters, e.g. SIZE -> "25600000". */
    var extensions: Map<String, String> = emptyMap()
        private set

    /** The server's SIZE limit in octets; null when it has none (SIZE absent or 0). */
    val maxSize: Long? get() = extensions["SIZE"]?.toLongOrNull()?.takeIf { it > 0 }

    /** Upper-case mechanisms from the "AUTH A B" and the legacy "AUTH=A B" EHLO lines (post-TLS EHLO only). */
    val authMechanisms: Set<String> get() = emptySet()

    /** Reads the greeting and sends `EHLO [127.0.0.1]`. Called by [connect]. */
    fun start(tls: TlsMode = TlsMode.IMPLICIT) {
        if (tls == TlsMode.STARTTLS) throw StartTlsException("STARTTLS is not available yet")
        readReply().expect(Stage.CONNECT, 220)
        val ehlo = command("EHLO [127.0.0.1]").expect(Stage.EHLO, 250)
        extensions = ehlo.lines.drop(1).associate { line ->
            line.substringBefore(' ').uppercase() to line.substringAfter(' ', "").trim()
        }
    }

    /**
     * Sign-in with a password or an app password: SASL PLAIN with an initial response. A wrong password ->
     * [SmtpException] with stage AUTH (535).
     */
    fun authPassword(user: String, password: String) = authPlain(user, password)

    /** Google XOAUTH2. On failure Gmail sends a 334 JSON challenge; the empty answer gets the final 5xx. */
    fun authXoauth2(user: String, accessToken: String) =
        authenticate("XOAUTH2", Sasl.xoauth2(user, accessToken), cancel = "")

    /**
     * Sends one message. [message] uses CRLF line endings; dot-stuffing is done here.
     * Invalid addresses -> [IllegalArgumentException] before any byte is written. Any RCPT refused with
     * 5xx, or with a 4xx whose enhanced code is an address error (4.1.x, e.g. Riseup's "450 4.1.2 Domain not
     * found" for a mistyped domain) -> RSET and [SmtpRecipientException], so the user can fix the address;
     * else any 4xx at RCPT -> RSET and a transient [SmtpException] at stage RCPT; 421 at once, without RSET.
     * A refused DATA -> RSET and [SmtpException].
     * No reply to the final dot -> [SmtpUncertainException].
     */
    fun send(from: String, recipients: List<String>, message: ByteArray) {
        require(recipients.isNotEmpty()) { "No recipients" }
        val addresses = listOf(from) + recipients
        // The text names no address, so that exception messages carry no personal data.
        require(addresses.all(AddrSpec::isValid)) { "Invalid address" }

        val mailFrom = StringBuilder("MAIL FROM:<").append(from).append('>')
        if ("SIZE" in extensions) mailFrom.append(" SIZE=").append(DotStuffing.size(message))
        if ("SMTPUTF8" in extensions && addresses.any { a -> a.any { it.code > 0x7f } }) mailFrom.append(" SMTPUTF8")
        command(mailFrom.toString()).expect(Stage.MAIL)

        val rejected = LinkedHashMap<String, String>()
        var deferred: Reply? = null
        for (rcpt in recipients) {
            val reply = command("RCPT TO:<$rcpt>")
            when {
                reply.code / 100 == 2 -> Unit
                // The server closes the channel: transient, and nothing more can be sent.
                reply.code == 421 -> reply.expect(Stage.RCPT)
                // An address error is the user's to fix, even when the server calls it temporary.
                reply.code / 100 == 4 && reply.enhanced?.startsWith("4.1.") == true -> rejected[rcpt] = reply.toString()
                // Throttling or another temporary failure (4xx) is transient for the whole send.
                reply.code / 100 == 4 -> if (deferred == null) deferred = reply
                else -> rejected[rcpt] = reply.toString()
            }
        }
        if (rejected.isNotEmpty() || deferred != null) {
            reset()
            if (rejected.isNotEmpty()) throw SmtpRecipientException(rejected)
            deferred?.expect(Stage.RCPT)
        }

        val data = command("DATA")
        if (data.code != 354 && data.code != 421) reset()
        data.expect(Stage.DATA, 354)
        DotStuffing.write(message, stream.output)
        stream.output.flush() // A failure up to here is certain: the server discards a message without its dot.
        finish().expect(Stage.FINAL)
    }

    /** Sends QUIT, waits for 221 and closes. Errors are ignored. */
    fun quit() {
        try {
            command("QUIT")
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    override fun close() = stream.close()

    /** RSET after a refusal, so that the next MAIL does not meet an open transaction. Errors are ignored. */
    private fun reset() {
        try {
            command("RSET")
        } catch (_: IOException) {
            // The refusal is what the caller needs; a broken connection shows on the next command.
        }
    }

    /** Writes the final dot and reads its reply with the RFC 5321 10-minute timeout. */
    private fun finish(): Reply {
        val previous = stream.readTimeoutMs
        stream.readTimeoutMs = FINAL_REPLY_TIMEOUT_MS
        try {
            return try {
                write(".")
                readReply()
            } catch (e: IOException) {
                throw SmtpUncertainException(e)
            }
        } finally {
            try {
                stream.readTimeoutMs = previous
            } catch (_: IOException) {
            }
        }
    }

    private fun authPlain(user: String, password: String) = authenticate("PLAIN", Sasl.plain(user, password), cancel = "*")

    private fun authenticate(mechanism: String, initialResponse: String, cancel: String) {
        var reply = command("AUTH $mechanism $initialResponse")
        // Never log a 334 payload or the answer to it.
        if (reply.code == 334) reply = command(cancel, logAs = "<sasl response>")
        reply.expect(Stage.AUTH, 235)
    }

    /** Sends one command line and reads the reply. Only the verb is logged, never the arguments. */
    private fun command(line: String, logAs: String = line.substringBefore(' ')): Reply {
        write(line, logAs)
        return readReply()
    }

    private fun write(line: String, logAs: String = line.substringBefore(' ')) {
        require('\r' !in line && '\n' !in line) { "Line break in SMTP command" }
        log("C: $logAs")
        stream.output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        stream.output.flush()
    }

    private fun readReply(): Reply {
        val lines = ArrayList<String>()
        var code = -1
        while (true) {
            val match = REPLY_LINE.matchEntire(readLine()) ?: throw ProtocolException("Malformed SMTP reply")
            val (digits, separator, text) = match.destructured
            if (code != -1 && digits.toInt() != code) throw ProtocolException("Mixed SMTP reply codes")
            code = digits.toInt()
            lines += text
            if (separator != "-") break
            if (lines.size >= MAX_REPLY_LINES) throw ProtocolException("SMTP reply too long")
        }
        log("S: $code")
        return Reply(code, lines)
    }

    /** One line without its CRLF. A bare LF also ends the line. */
    private fun readLine(): String {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            when (val b = stream.input.read()) {
                -1 -> throw EOFException("SMTP connection closed")
                '\n'.code -> break
                else -> {
                    if (buffer.size() >= MAX_LINE) throw ProtocolException("SMTP reply line too long")
                    buffer.write(b)
                }
            }
        }
        val bytes = buffer.toByteArray()
        val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, length, Charsets.UTF_8)
    }

    /**
     * One reply. [lines] is the text after `ddd-` / `ddd ` on each line. [enhanced] is an RFC 3463 code
     * at the start of the first line when its class matches [code].
     */
    private class Reply(val code: Int, val lines: List<String>) {
        val enhanced: String? = ENHANCED.find(lines.first())?.value?.takeIf { it[0].digitToInt() == code / 100 }

        /** The text without the enhanced code that most servers repeat on every line. */
        val text: String = lines.joinToString(" ") { line ->
            if (enhanced != null && line.startsWith(enhanced)) line.substring(enhanced.length).trim() else line.trim()
        }.trim()

        /** Throws unless the code is [expected], or any 2xx when [expected] is null. */
        fun expect(stage: Stage, expected: Int? = null): Reply {
            val ok = if (expected == null) code / 100 == 2 else code == expected
            if (!ok) throw SmtpException(code, enhanced, toString(), stage)
            return this
        }

        override fun toString(): String =
            listOfNotNull(code.toString(), enhanced, text.ifEmpty { null }).joinToString(" ")
    }

    companion object {
        private const val FINAL_REPLY_TIMEOUT_MS = 600_000
        private const val MAX_LINE = 8192
        private const val MAX_REPLY_LINES = 200
        private val REPLY_LINE = Regex("""(\d{3})(?:([ -])(.*))?""", RegexOption.DOT_MATCHES_ALL)
        private val ENHANCED = Regex("""^[245]\.\d{1,3}\.\d{1,3}(?=\s|$)""")

        fun connect(endpoint: Endpoint, hooks: SocketHooks, log: (String) -> Unit = {}): SmtpClient =
            connect(endpoint, hooks, log, SSLSocketFactory.getDefault() as SSLSocketFactory)

        internal fun connect(
            endpoint: Endpoint,
            hooks: SocketHooks,
            log: (String) -> Unit,
            factory: SSLSocketFactory,
        ): SmtpClient {
            if (endpoint.tls == TlsMode.STARTTLS) throw StartTlsException("STARTTLS is not available yet")
            val socket = MailSocket.open(endpoint.host, endpoint.port, hooks, factory = factory)
            try {
                return SmtpClient(socket, log, endpoint.host).apply { start(endpoint.tls) }
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }
    }
}

/** DATA payload rules: CRLF line endings, RFC 5321 §4.5.2 dot-stuffing, a final CRLF. */
internal object DotStuffing {
    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private val CRLF = byteArrayOf(CR, LF)

    /**
     * Writes [message] with every bare CR or LF as CRLF and a dot before each line that starts with one.
     * Ends with CRLF, so that the caller only writes the terminating `.` line.
     */
    fun write(message: ByteArray, out: OutputStream) {
        var start = 0
        var lineStart = true
        var i = 0
        while (i < message.size) {
            when (message[i]) {
                CR, LF -> {
                    out.write(message, start, i - start)
                    out.write(CRLF)
                    if (message[i] == CR && i + 1 < message.size && message[i + 1] == LF) i++
                    start = i + 1
                    lineStart = true
                }
                DOT -> {
                    // At a line start nothing is pending, so the extra dot goes straight out.
                    if (lineStart) out.write(DOT.toInt())
                    lineStart = false
                }
                else -> lineStart = false
            }
            i++
        }
        out.write(message, start, message.size - start)
        if (!lineStart) out.write(CRLF)
    }

    /** RFC 1870 message size: the octets after line-ending normalisation, without stuffing dots. */
    fun size(message: ByteArray): Long {
        var size = message.size.toLong()
        for (i in message.indices) {
            val bareCr = message[i] == CR && (i + 1 == message.size || message[i + 1] != LF)
            val bareLf = message[i] == LF && (i == 0 || message[i - 1] != CR)
            if (bareCr || bareLf) size++
        }
        if (message.isNotEmpty() && message.last() != LF && message.last() != CR) size += 2
        return size
    }
}
