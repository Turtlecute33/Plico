package app.plico.mail.smtp

import app.plico.mail.net.Endpoint
import app.plico.mail.net.SocketHooks
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.StartTlsServer
import app.plico.mail.net.TestTls
import app.plico.mail.net.TlsMode
import app.plico.mail.net.TlsServer
import app.plico.mail.net.readCrlfLine
import app.plico.mail.smtp.ScriptedStream.Step
import app.plico.mail.smtp.SmtpException.Stage
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SmtpClientTest {

    // Real replies, recorded 2026-10-16 (docs/research riseup R14, gmail G28). The Gmail IP is replaced.
    private val riseupGreeting = s("220 fews03-sea.riseup.net ESMTP (spam is not appreciated)")
    private val riseupEhlo = s(
        "250-fews03-sea.riseup.net", "250-PIPELINING", "250-SIZE 25600000", "250-ETRN", "250-AUTH PLAIN LOGIN",
        "250-ENHANCEDSTATUSCODES", "250-8BITMIME", "250-SMTPUTF8", "250 CHUNKING",
    )
    private val gmailGreeting = s("220 smtp.gmail.com ESMTP 5b1f17b1804b1-46e2a9b1b3fsm1234568f8f.12 - gsmtp")
    private val gmailEhlo = s(
        "250-smtp.gmail.com at your service, [1.2.3.4]", "250-SIZE 35882577", "250-8BITMIME",
        "250-AUTH LOGIN PLAIN XOAUTH2 PLAIN-CLIENTTOKEN OAUTHBEARER XOAUTH", "250-ENHANCEDSTATUSCODES",
        "250-PIPELINING", "250-CHUNKING", "250 SMTPUTF8",
    )
    private val gmailBadCredentials = s(
        "535-5.7.8 Username and Password not accepted. For more information, go to",
        "535 5.7.8  https://support.google.com/mail/?p=BadCredentials 5b1f17b1804b1-46e2a9b1b3fsm1234568f8f.12 - gsmtp",
    )
    private val ehlo = c("EHLO [127.0.0.1]")
    private val message = "Subject: hi\r\n\r\nHello\r\n".toByteArray()

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun client(stream: ScriptedStream, log: MutableList<String> = mutableListOf()) =
        SmtpClient(stream, { log += it })

    /** A client after greeting and EHLO with the given extensions (as EHLO lines after the first). */
    private fun started(vararg steps: Step, extensions: List<String> = emptyList()): Pair<SmtpClient, ScriptedStream> {
        val ehloReply = s(*(listOf("mail.example") + extensions).mapIndexed { i, line ->
            if (i == extensions.size) "250 $line" else "250-$line"
        }.toTypedArray())
        val stream = ScriptedStream(s("220 mail.example ESMTP"), ehlo, ehloReply, *steps)
        return client(stream).apply { start() } to stream
    }

    // Greeting and EHLO

    @Test
    fun `riseup EHLO reply is parsed`() {
        val stream = ScriptedStream(riseupGreeting, ehlo, riseupEhlo)
        val client = client(stream)
        client.start()
        assertEquals(
            mapOf(
                "PIPELINING" to "", "SIZE" to "25600000", "ETRN" to "", "AUTH" to "PLAIN LOGIN",
                "ENHANCEDSTATUSCODES" to "", "8BITMIME" to "", "SMTPUTF8" to "", "CHUNKING" to "",
            ),
            client.extensions,
        )
        assertEquals(25_600_000L, client.maxSize)
        stream.assertDone()
    }

    @Test
    fun `gmail EHLO reply is parsed and the greeting line is not an extension`() {
        val stream = ScriptedStream(gmailGreeting, ehlo, gmailEhlo)
        val client = client(stream)
        client.start()
        assertEquals(
            setOf("SIZE", "8BITMIME", "AUTH", "ENHANCEDSTATUSCODES", "PIPELINING", "CHUNKING", "SMTPUTF8"),
            client.extensions.keys,
        )
        assertEquals("LOGIN PLAIN XOAUTH2 PLAIN-CLIENTTOKEN OAUTHBEARER XOAUTH", client.extensions["AUTH"])
        assertEquals(35_882_577L, client.maxSize)
        stream.assertDone()
    }

    @Test
    fun `keywords are upper-cased and SIZE 0 or no SIZE means no limit`() {
        val lowerCase = started(extensions = listOf("size 0", "auth plain")).first
        assertEquals(mapOf("SIZE" to "0", "AUTH" to "plain"), lowerCase.extensions)
        assertNull(started(extensions = listOf("SIZE 0")).first.maxSize)
        assertNull(started(extensions = listOf("8BITMIME")).first.maxSize)
        assertNull(started().first.maxSize)
    }

    @Test
    fun `multi-line greeting and bare LF line ends are accepted`() {
        val stream = ScriptedStream(
            Step.Server("220-mail.example ESMTP\n220 second line\r\n"),
            ehlo,
            Step.Server("250-mail.example\n250 SIZE 10\n"),
        )
        val client = client(stream)
        client.start()
        assertEquals(10L, client.maxSize)
        stream.assertDone()
    }

    @Test
    fun `greeting other than 220 fails at CONNECT without EHLO`() {
        val stream = ScriptedStream(s("554 5.3.2 mail.example Service unavailable"))
        val e = assertFailsWith<SmtpException> { client(stream).start() }
        assertEquals(Stage.CONNECT, e.stage)
        assertEquals(554, e.code)
        assertEquals("5.3.2", e.enhanced)
        assertFalse(e.transient)
        stream.assertDone()

        val busy = ScriptedStream(s("421 4.7.0 Try again later, closing connection."))
        val transient = assertFailsWith<SmtpException> { client(busy).start() }
        assertEquals(Stage.CONNECT, transient.stage)
        assertTrue(transient.transient)
        busy.assertDone()
    }

    @Test
    fun `EHLO rejection fails at EHLO`() {
        val stream = ScriptedStream(riseupGreeting, ehlo, s("502 5.5.2 Error: command not recognized"))
        val e = assertFailsWith<SmtpException> { client(stream).start() }
        assertEquals(Stage.EHLO, e.stage)
        assertEquals(502, e.code)
        stream.assertDone()
    }

    @Test
    fun `malformed replies and EOF are IO errors`() {
        for (bad in listOf("hello", "25", "250x ok", "abc ok", "")) {
            assertFailsWith<ProtocolException>(bad) { client(ScriptedStream(Step.Server("$bad\r\n"))).start() }
        }
        assertFailsWith<ProtocolException> { client(ScriptedStream(s("220-a", "221 b"))).start() }
        assertFailsWith<EOFException> { client(ScriptedStream(Step.Server("220 no line end"))).start() }
        assertFailsWith<EOFException> { client(ScriptedStream()).start() }
    }

    @Test
    fun `enhanced code needs a matching class and the reply text drops repeated codes`() {
        val (client, stream) = started(c("MAIL FROM:<a@b.example>"), s("550 2.0.0 odd"))
        val e = assertFailsWith<SmtpException> { client.send("a@b.example", listOf("c@d.example"), message) }
        assertNull(e.enhanced)
        assertEquals("550 2.0.0 odd", e.message)
        stream.assertDone()
    }

    // AUTH

    @Test
    fun `AUTH PLAIN success`() {
        val stream = ScriptedStream(
            riseupGreeting, ehlo, riseupEhlo,
            c("AUTH PLAIN " + b64("\u0000alice@riseup.net\u0000correct horse battery staple")),
            s("235 2.7.0 Authentication successful"),
        )
        val client = client(stream)
        client.start()
        client.authPassword("alice@riseup.net", "correct horse battery staple")
        stream.assertDone()
    }

    @Test
    fun `AUTH PLAIN failure on Riseup and Gmail`() {
        val (riseupClient, riseup) = started(
            c("AUTH PLAIN " + b64("\u0000alice\u0000wrong")),
            s("535 5.7.8 Error: authentication failed: authentication failure"),
            extensions = listOf("AUTH PLAIN LOGIN"),
        )
        val e = assertFailsWith<SmtpException> { riseupClient.authPassword("alice", "wrong") }
        assertEquals(Stage.AUTH, e.stage)
        assertEquals(535, e.code)
        assertEquals("5.7.8", e.enhanced)
        assertFalse(e.transient)
        assertEquals("535 5.7.8 Error: authentication failed: authentication failure", e.message)
        riseup.assertDone()

        val gmail = ScriptedStream(
            gmailGreeting, ehlo, gmailEhlo,
            c("AUTH PLAIN " + b64("\u0000me@gmail.com\u0000abcdabcdabcdabcd")),
            gmailBadCredentials,
        )
        val gmailClient = client(gmail).apply { start() }
        val g = assertFailsWith<SmtpException> { gmailClient.authPassword("me@gmail.com", "abcdabcdabcdabcd") }
        assertEquals(535, g.code)
        assertEquals("5.7.8", g.enhanced)
        assertEquals(
            "535 5.7.8 Username and Password not accepted. For more information, go to " +
                "https://support.google.com/mail/?p=BadCredentials 5b1f17b1804b1-46e2a9b1b3fsm1234568f8f.12 - gsmtp",
            g.message,
        )
        gmail.assertDone()
    }

    @Test
    fun `AUTH PLAIN answers an unexpected 334 with a cancel`() {
        val (client, stream) = started(
            c("AUTH PLAIN " + b64("\u0000u\u0000p")), s("334 "), c("*"), s("501 5.7.0 Authentication aborted"),
            extensions = listOf("AUTH PLAIN"),
        )
        val e = assertFailsWith<SmtpException> { client.authPassword("u", "p") }
        assertEquals(Stage.AUTH, e.stage)
        assertEquals(501, e.code)
        stream.assertDone()
    }

    @Test
    fun `AUTH XOAUTH2 success`() {
        val stream = ScriptedStream(
            gmailGreeting, ehlo, gmailEhlo,
            c("AUTH XOAUTH2 " + b64("user=me@gmail.com\u0001auth=Bearer ya29.token\u0001\u0001")),
            s("235 2.7.0 Accepted"),
        )
        val client = client(stream)
        client.start()
        client.authXoauth2("me@gmail.com", "ya29.token")
        stream.assertDone()
    }

    @Test
    fun `AUTH XOAUTH2 failure answers the JSON challenge with an empty line`() {
        val challenge = b64("""{"status":"400","schemes":"Bearer","scope":"https://mail.google.com/"}""")
        val stream = ScriptedStream(
            c("AUTH XOAUTH2 " + b64("user=me@gmail.com\u0001auth=Bearer expired\u0001\u0001")),
            s("334 $challenge"), c(""), gmailBadCredentials,
        )
        val e = assertFailsWith<SmtpException> { client(stream).authXoauth2("me@gmail.com", "expired") }
        assertEquals(Stage.AUTH, e.stage)
        assertEquals(535, e.code)
        assertEquals("5.7.8", e.enhanced)
        stream.assertDone()
    }

    @Test
    fun `AUTH mechanisms come from both EHLO forms`() {
        assertEquals(setOf("LOGIN", "PLAIN"), started(extensions = listOf("AUTH=LOGIN PLAIN")).first.authMechanisms)
        assertEquals(
            setOf("LOGIN", "PLAIN", "XOAUTH2"),
            started(extensions = listOf("AUTH plain xoauth2", "AUTH=LOGIN")).first.authMechanisms,
        )
        assertEquals(emptySet(), started(extensions = listOf("SIZE 10", "AUTHX PLAIN")).first.authMechanisms)
    }

    @Test
    fun `AUTH LOGIN when PLAIN is not offered`() {
        val (client, stream) = started(
            c("AUTH LOGIN"), s("334 VXNlcm5hbWU6"),
            c(b64("alice@example.org")), s("334 UGFzc3dvcmQ6"),
            c(b64("pässword")), s("235 2.7.0 Authentication successful"),
            extensions = listOf("AUTH=LOGIN"),
        )
        client.authPassword("alice@example.org", "pässword")
        stream.assertDone()

        val (refused, refusedStream) = started(
            c("AUTH LOGIN"), s("334 VXNlcm5hbWU6"),
            c(b64("alice")), s("334 UGFzc3dvcmQ6"),
            c(b64("wrong")), s("535 5.7.8 Error: authentication failed"),
            extensions = listOf("AUTH LOGIN"),
        )
        val e = assertFailsWith<SmtpException> { refused.authPassword("alice", "wrong") }
        assertEquals(Stage.AUTH, e.stage)
        assertEquals(535, e.code)
        refusedStream.assertDone()
    }

    @Test
    fun `PLAIN is preferred over LOGIN`() {
        val (client, stream) = started(
            c("AUTH PLAIN " + b64("\u0000a\u0000b")), s("235 2.7.0 ok"),
            extensions = listOf("AUTH LOGIN CRAM-MD5 PLAIN"),
        )
        client.authPassword("a", "b")
        stream.assertDone()
    }

    @Test
    fun `no usable AUTH mechanism fails before the password is sent`() {
        for (extensions in listOf(listOf("SIZE 10"), listOf("AUTH CRAM-MD5 GSSAPI"))) {
            val (client, stream) = started(extensions = extensions)
            assertFailsWith<SmtpAuthUnavailableException> { client.authPassword("alice", "pw") }
            stream.assertDone()
            assertFalse("AUTH " in stream.transcript.toString().substringAfter("EHLO"))
        }
    }

    @Test
    fun `a long XOAUTH2 response goes after a 334, a short one stays on one line`() {
        val token = "eyJ0eXAiOiJKV1Qi" + "x".repeat(2_000)
        val ir = b64("user=me@outlook.com\u0001auth=Bearer $token\u0001\u0001")
        val (client, stream) = started(c("AUTH XOAUTH2"), s("334 "), c(ir), s("235 2.7.0 Authentication successful"))
        client.authXoauth2("me@outlook.com", token)
        stream.assertDone()

        // The error challenge after the response gets the empty line, as in the one-line form.
        val (failing, failingStream) = started(
            c("AUTH XOAUTH2"), s("334 "), c(ir), s("334 eyJzdGF0dXMiOiI0MDEifQ=="), c(""),
            s("535 5.7.3 Authentication unsuccessful"),
        )
        val e = assertFailsWith<SmtpException> { failing.authXoauth2("me@outlook.com", token) }
        assertEquals(Stage.AUTH, e.stage)
        assertEquals("5.7.3", e.enhanced)
        failingStream.assertDone()

        // The limit is the 510 octets of the command line (RFC 4954 §4).
        val short = (1..400).map { "t".repeat(it) }
            .last { "AUTH XOAUTH2 ".length + b64("user=a@b.example\u0001auth=Bearer $it\u0001\u0001").length <= 510 }
        val shortIr = b64("user=a@b.example\u0001auth=Bearer $short\u0001\u0001")
        val (oneLine, oneLineStream) = started(c("AUTH XOAUTH2 $shortIr"), s("235 ok"))
        oneLine.authXoauth2("a@b.example", short)
        oneLineStream.assertDone()
        val longer = short + "ttt"
        val longerIr = b64("user=a@b.example\u0001auth=Bearer $longer\u0001\u0001")
        val (twoStep, twoStepStream) = started(c("AUTH XOAUTH2"), s("334 "), c(longerIr), s("235 ok"))
        twoStep.authXoauth2("a@b.example", longer)
        twoStepStream.assertDone()
    }

    @Test
    fun `no password or token before TLS`() {
        val stream = ScriptedStream(riseupGreeting, ehlo, riseupEhlo).apply { encrypted = false }
        val client = client(stream)
        client.start()
        assertFailsWith<IllegalStateException> { client.authPassword("alice@riseup.net", "pw") }
        assertFailsWith<IllegalStateException> { client.authXoauth2("alice@riseup.net", "tok") }
        stream.assertDone()
        assertEquals("EHLO [127.0.0.1]\r\n", stream.transcript.toString())
    }

    // STARTTLS

    @Test
    fun `STARTTLS sends EHLO again and keeps only the extensions after TLS`() {
        val log = mutableListOf<String>()
        val stream = ScriptedStream(
            s("220 mail.example ESMTP"), ehlo,
            s("250-mail.example", "250-SIZE 10", "250-AUTH PLAIN XOAUTH2", "250 STARTTLS"),
            c("STARTTLS"), s("220 2.0.0 Ready to start TLS"), Step.Tls,
            ehlo, s("250-mail.example", "250-SIZE 25600000", "250-AUTH=LOGIN", "250 8BITMIME"),
            c("AUTH LOGIN"), s("334 VXNlcm5hbWU6"), c(b64("alice")), s("334 UGFzc3dvcmQ6"), c(b64("s3cret")),
            s("235 2.7.0 Authentication successful"),
        ).apply { encrypted = false }
        val client = client(stream, log)
        client.start(TlsMode.STARTTLS)
        assertTrue(stream.encrypted)
        assertEquals(1, stream.tlsStarts)
        assertEquals(mapOf("SIZE" to "25600000", "AUTH=LOGIN" to "", "8BITMIME" to ""), client.extensions)
        assertEquals(25_600_000L, client.maxSize)
        assertEquals(setOf("LOGIN"), client.authMechanisms)
        client.authPassword("alice", "s3cret")
        stream.assertDone()
        assertEquals(
            listOf(
                "S: 220", "C: EHLO", "S: 250", "C: STARTTLS", "S: 220", "C: EHLO", "S: 250",
                "C: AUTH", "S: 334", "C: <sasl response>", "S: 334", "C: <sasl response>", "S: 235",
            ),
            log,
        )
    }

    @Test
    fun `STARTTLS without extensions after TLS has no size and no AUTH`() {
        val stream = ScriptedStream(
            s("220 mail.example ESMTP"), ehlo, s("250-mail.example", "250-SIZE 10", "250 AUTH PLAIN"),
            c("STARTTLS"), s("220 go ahead"), Step.Tls, ehlo, s("250 mail.example"),
        ).apply { encrypted = false }
        val client = client(stream)
        client.start(TlsMode.STARTTLS)
        assertNull(client.maxSize)
        assertEquals(emptySet(), client.authMechanisms)
        assertFailsWith<SmtpAuthUnavailableException> { client.authPassword("a", "b") }
        stream.assertDone()
    }

    @Test
    fun `a refused STARTTLS closes the connection and sends no AUTH`() {
        for (reply in listOf("454 4.7.0 TLS not available due to local problem", "502 5.5.1 Unrecognized command")) {
            val stream = ScriptedStream(
                riseupGreeting, ehlo, riseupEhlo, c("STARTTLS"), s(reply),
            ).apply { encrypted = false }
            val client = client(stream)
            assertFailsWith<StartTlsException>(reply) { client.start(TlsMode.STARTTLS) }
            assertTrue(stream.closed)
            assertFalse(stream.encrypted)
            assertFailsWith<IllegalStateException> { client.authPassword("alice", "pw") }
            stream.assertDone()
            assertFalse("AUTH" in stream.transcript.toString())
        }
    }

    @Test
    fun `bytes after the 220 to STARTTLS fail before any secret`() {
        val stream = ScriptedStream(
            riseupGreeting, ehlo, riseupEhlo, c("STARTTLS"),
            Step.Server("220 2.0.0 Ready to start TLS\r\n250-injected\r\n250 AUTH PLAIN\r\n"),
        ).apply { encrypted = false }
        val client = client(stream)
        assertFailsWith<StartTlsException> { client.start(TlsMode.STARTTLS) }
        assertFalse(stream.encrypted)
        assertEquals(0, stream.tlsStarts)
        stream.assertDone()
        assertEquals("EHLO [127.0.0.1]\r\nSTARTTLS\r\n", stream.transcript.toString())
    }

    @Test(timeout = 30_000)
    fun `connect with STARTTLS over real TLS`() {
        StartTlsServer("localhost") { conn ->
            conn.send("220 mail.example ESMTP\r\n")
            assertEquals("EHLO [127.0.0.1]", conn.line())
            conn.send("250-mail.example\r\n250-SIZE 10\r\n250 STARTTLS\r\n")
            assertEquals("STARTTLS", conn.line())
            conn.send("220 2.0.0 Ready to start TLS\r\n")
            conn.upgrade()
            assertEquals("EHLO [127.0.0.1]", conn.line())
            conn.send("250-mail.example\r\n250-SIZE 25600000\r\n250 AUTH PLAIN LOGIN\r\n")
            assertEquals("AUTH PLAIN " + b64("\u0000alice\u0000pw"), conn.line())
            conn.send("235 2.7.0 ok\r\n")
            assertEquals("QUIT", conn.line())
            conn.send("221 2.0.0 Bye\r\n")
        }.use { server ->
            val log = mutableListOf<String>()
            val factory = TestTls.clientFactory("localhost")
            val client = SmtpClient.connect(Endpoint("localhost", server.port, TlsMode.STARTTLS), AcceptAll, { log += it }, factory)
            assertEquals(25_600_000L, client.maxSize)
            assertEquals(setOf("PLAIN", "LOGIN"), client.authMechanisms)
            client.authPassword("alice", "pw")
            client.quit()
            server.join()
            assertEquals(
                listOf(
                    "S: 220", "C: EHLO", "S: 250", "C: STARTTLS", "S: 220", "C: EHLO", "S: 250", "C: AUTH", "S: 235",
                    "C: QUIT", "S: 221",
                ),
                log,
            )
        }
    }

    @Test(timeout = 30_000)
    fun `connect closes the socket when STARTTLS is refused`() {
        StartTlsServer("localhost") { conn ->
            conn.send("220 mail.example ESMTP\r\n")
            conn.line()
            conn.send("250-mail.example\r\n250 AUTH PLAIN\r\n")
            assertEquals("STARTTLS", conn.line())
            conn.send("454 4.7.0 TLS not available\r\n")
            assertEquals(-1, conn.input.read())
        }.use { server ->
            assertFailsWith<StartTlsException> {
                SmtpClient.connect(Endpoint("localhost", server.port, TlsMode.STARTTLS), AcceptAll, {}, TestTls.clientFactory("localhost"))
            }
            server.join()
        }
    }

    // Logging

    @Test
    fun `log has only verbs and codes`() {
        val log = mutableListOf<String>()
        val body = "Subject: secret plans\r\n".toByteArray()
        val stream = ScriptedStream(
            riseupGreeting, ehlo, riseupEhlo,
            c("AUTH PLAIN " + b64("\u0000alice@riseup.net\u0000s3cret")), s("235 2.7.0 Authentication successful"),
            c("MAIL FROM:<alice@riseup.net> SIZE=${body.size}"), s("250 2.1.0 Ok"),
            c("RCPT TO:<bob@example.org>"), s("250 2.1.5 Ok"),
            c("DATA"), s("354 End data with <CR><LF>.<CR><LF>"),
            Step.Client("Subject: secret plans\r\n"), c("."), s("250 2.0.0 Ok: queued as 4cR7Yw0QfZzDqT8"),
            c("QUIT"), s("221 2.0.0 Bye"),
        )
        val client = client(stream, log)
        client.start()
        client.authPassword("alice@riseup.net", "s3cret")
        client.send("alice@riseup.net", listOf("bob@example.org"), body)
        client.quit()
        stream.assertDone()
        assertEquals(
            listOf(
                "S: 220", "C: EHLO", "S: 250", "C: AUTH", "S: 235", "C: MAIL", "S: 250", "C: RCPT", "S: 250",
                "C: DATA", "S: 354", "C: .", "S: 250", "C: QUIT", "S: 221",
            ),
            log,
        )

        val oauthLog = mutableListOf<String>()
        val oauth = ScriptedStream(
            c("AUTH XOAUTH2 " + b64("user=me@gmail.com\u0001auth=Bearer tok\u0001\u0001")), s("334 eyJ9"), c(""),
            gmailBadCredentials,
        )
        assertFailsWith<SmtpException> { client(oauth, oauthLog).authXoauth2("me@gmail.com", "tok") }
        assertEquals(listOf("C: AUTH", "S: 334", "C: <sasl response>", "S: 535"), oauthLog)
    }

    // send

    @Test
    fun `send on Riseup normalises line ends, dot-stuffs and ends with CRLF dot CRLF`() {
        val body = "Subject: hi\n\n.leading dot\nline\n..two\n.".toByteArray()
        val normalized = "Subject: hi\r\n\r\n.leading dot\r\nline\r\n..two\r\n.\r\n"
        val stream = ScriptedStream(
            riseupGreeting, ehlo, riseupEhlo,
            c("MAIL FROM:<alice@riseup.net> SIZE=${normalized.length}"), s("250 2.1.0 Ok"),
            c("RCPT TO:<bob@example.org>"), s("250 2.1.5 Ok"),
            c("RCPT TO:<carol@example.net>"), s("250 2.1.5 Ok"),
            c("DATA"), s("354 End data with <CR><LF>.<CR><LF>"),
            Step.Client("Subject: hi\r\n\r\n..leading dot\r\nline\r\n...two\r\n..\r\n"), c("."),
            s("250 2.0.0 Ok: queued as 4cR7Yw0QfZzDqT8"),
        )
        val client = client(stream)
        client.start()
        client.send("alice@riseup.net", listOf("bob@example.org", "carol@example.net"), body)
        stream.assertDone()
        assertEquals(600_000, stream.timeoutsAtRead.last())
        assertEquals(60_000, stream.readTimeoutMs)
        assertTrue(stream.timeoutsAtRead.dropLast(1).all { it == 60_000 })
    }

    @Test
    fun `send on Gmail adds SMTPUTF8 for a non-ASCII address`() {
        val gmailId = "5b1f17b1804b1-46e2a9b1b3fsm1234568f8f.12 - gsmtp"
        val stream = ScriptedStream(
            gmailGreeting, ehlo, gmailEhlo,
            c("MAIL FROM:<me@gmail.com> SIZE=${message.size} SMTPUTF8"), s("250 2.1.0 OK $gmailId"),
            c("RCPT TO:<用户@例子.广告>"), s("250 2.1.5 OK $gmailId"),
            c("DATA"), s("354 Go ahead $gmailId"),
            Step.Client(String(message)), c("."), s("250 2.0.0 OK  1760610000 $gmailId"),
            c("QUIT"), s("221 2.0.0 closing connection $gmailId"),
        )
        val client = client(stream)
        client.start()
        client.send("me@gmail.com", listOf("用户@例子.广告"), message)
        client.quit()
        stream.assertDone()
        assertTrue(stream.closed)
    }

    @Test
    fun `no SIZE or SMTPUTF8 parameter when the server does not offer them`() {
        val (client, stream) = started(
            c("MAIL FROM:<me@example.com>"), s("250 ok"),
            c("RCPT TO:<josé@exämple.de>"), s("250 ok"),
            c("DATA"), s("354 go"),
            Step.Client(String(message)), c("."), s("250 ok"),
            extensions = listOf("8BITMIME"),
        )
        client.send("me@example.com", listOf("josé@exämple.de"), message)
        stream.assertDone()
    }

    @Test
    fun `a rejected recipient gets RSET, no DATA and the server reply`() {
        val rejection = "550 5.1.1 <bob@typo.example>: Recipient address rejected: User unknown in virtual mailbox table"
        val stream = ScriptedStream(
            riseupGreeting, ehlo, riseupEhlo,
            c("MAIL FROM:<alice@riseup.net> SIZE=${message.size}"), s("250 2.1.0 Ok"),
            c("RCPT TO:<carol@example.net>"), s("250 2.1.5 Ok"),
            c("RCPT TO:<bob@typo.example>"), s(rejection),
            c("RSET"), s("250 2.0.0 Ok"),
        )
        val client = client(stream)
        client.start()
        val e = assertFailsWith<SmtpRecipientException> {
            client.send("alice@riseup.net", listOf("carol@example.net", "bob@typo.example"), message)
        }
        assertEquals(mapOf("bob@typo.example" to rejection), e.rejected)
        assertFalse(e.message!!.contains("typo"), "exception message must not name addresses")
        stream.assertDone()
    }

    @Test
    fun `4xx address error at RCPT is a recipient error the user can fix`() {
        val reply = "450 4.1.2 <bob@typo.example>: Recipient address rejected: Domain not found"
        val (client, stream) = started(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<bob@typo.example>"), s(reply),
            c("RCPT TO:<carol@example.net>"), s("250 2.1.5 Ok"),
            c("RSET"), s("250 2.0.0 Ok"),
        )
        val e = assertFailsWith<SmtpRecipientException> {
            client.send("a@example.org", listOf("bob@typo.example", "carol@example.net"), message)
        }
        assertEquals(setOf("bob@typo.example"), e.rejected.keys)
        stream.assertDone()
    }

    @Test
    fun `other 4xx at RCPT gets RSET and is transient for the whole send`() {
        val replies = listOf(
            "450 4.7.1 <bob@typo.example>: Recipient address rejected: too many recipients from 1.2.3.4",
            "451 4.3.0 Temporary lookup failure",
        )
        for (reply in replies) {
            val (client, stream) = started(
                c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
                c("RCPT TO:<bob@typo.example>"), s(reply),
                c("RCPT TO:<carol@example.net>"), s("250 2.1.5 Ok"),
                c("RSET"), s("250 2.0.0 Ok"),
            )
            val e = assertFailsWith<SmtpException>(reply) {
                client.send("a@example.org", listOf("bob@typo.example", "carol@example.net"), message)
            }
            assertEquals(Stage.RCPT, e.stage)
            assertEquals(reply.take(3).toInt(), e.code)
            assertEquals(reply.substring(4, 9), e.enhanced)
            assertTrue(e.transient)
            stream.assertDone()
        }
    }

    @Test
    fun `5xx at RCPT wins over 4xx and names only the refused recipients`() {
        val refused = "550 5.1.1 <c@example.org>: Recipient address rejected: User unknown"
        val (client, stream) = started(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("451 4.3.0 <b@example.org>: Temporary lookup failure"),
            c("RCPT TO:<c@example.org>"), s(refused),
            c("RSET"), s("250 2.0.0 Ok"),
        )
        val e = assertFailsWith<SmtpRecipientException> {
            client.send("a@example.org", listOf("b@example.org", "c@example.org"), message)
        }
        assertEquals(mapOf("c@example.org" to refused), e.rejected)
        stream.assertDone()
    }

    @Test
    fun `421 at RCPT fails at once without RSET`() {
        val (client, stream) = started(
            c("MAIL FROM:<me@gmail.com>"), s("250 2.1.0 OK"),
            c("RCPT TO:<b@example.org>"),
            s(
                "421-4.7.0 Try again later, closing connection. (RCPT)",
                "421 4.7.0  https://support.google.com/mail/answer/7720 - gsmtp",
            ),
        )
        val e = assertFailsWith<SmtpException> {
            client.send("me@gmail.com", listOf("b@example.org", "c@example.org"), message)
        }
        assertEquals(Stage.RCPT, e.stage)
        assertEquals(421, e.code)
        assertEquals("4.7.0", e.enhanced)
        assertTrue(e.transient)
        stream.assertDone()
    }

    @Test
    fun `every recipient rejected for a sender mismatch`() {
        val reply = "553 5.7.1 <alias@riseup.net>: Sender address rejected: not owned by user alice"
        val (client, stream) = started(
            c("MAIL FROM:<alias@riseup.net>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<a@example.org>"), s(reply),
            c("RCPT TO:<b@example.org>"), s(reply),
            c("RSET"), s("250 2.0.0 Ok"),
        )
        val e = assertFailsWith<SmtpRecipientException> {
            client.send("alias@riseup.net", listOf("a@example.org", "b@example.org"), message)
        }
        assertEquals(listOf("a@example.org", "b@example.org"), e.rejected.keys.toList())
        stream.assertDone()
    }

    @Test
    fun `a failed RSET still reports the rejected recipients`() {
        val (client, stream) = started(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("550 5.1.1 <b@example.org>: Recipient address rejected"),
            c("RSET"),
        )
        val e = assertFailsWith<SmtpRecipientException> { client.sendOne() }
        assertEquals(setOf("b@example.org"), e.rejected.keys)
        stream.assertDone()
    }

    @Test
    fun `4xx and 5xx at MAIL fail at MAIL without RCPT`() {
        val (busy, busyStream) = started(
            c("MAIL FROM:<me@gmail.com>"),
            s(
                "421-4.7.0 Try again later, closing connection. (MAIL)",
                "421 4.7.0  https://support.google.com/mail/answer/7720 - gsmtp",
            ),
        )
        val e = assertFailsWith<SmtpException> { busy.send("me@gmail.com", listOf("b@example.org"), message) }
        assertEquals(Stage.MAIL, e.stage)
        assertEquals(421, e.code)
        assertEquals("4.7.0", e.enhanced)
        assertTrue(e.transient)
        busyStream.assertDone()

        val (big, bigStream) = started(
            c("MAIL FROM:<alice@riseup.net> SIZE=${message.size}"), s("552 5.3.4 Message size exceeds fixed limit"),
            extensions = listOf("SIZE 10"),
        )
        val tooBig = assertFailsWith<SmtpException> { big.send("alice@riseup.net", listOf("b@example.org"), message) }
        assertEquals(Stage.MAIL, tooBig.stage)
        assertEquals(552, tooBig.code)
        assertFalse(tooBig.transient)
        bigStream.assertDone()
    }

    @Test
    fun `4xx and 5xx at DATA fail at DATA without the body and end the transaction`() {
        val replies = listOf(
            "451 4.3.0 Error: queue file write error" to true,
            "554 5.5.1 Error: no valid recipients" to false,
            "421 4.4.2 mail.example Error: timeout exceeded" to true,
        )
        for ((reply, transient) in replies) {
            // RSET, so that the next MAIL does not get 503 nested MAIL; 421 closes the channel instead.
            val reset = if (reply.startsWith("421")) emptyArray<Step>() else arrayOf(c("RSET"), s("250 2.0.0 Ok"))
            val (client, stream) = started(
                c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
                c("RCPT TO:<b@example.org>"), s("250 2.1.5 Ok"),
                c("DATA"), s(reply),
                *reset,
            )
            val e = assertFailsWith<SmtpException> { client.sendOne() }
            assertEquals(Stage.DATA, e.stage)
            assertEquals(transient, e.transient)
            stream.assertDone()
        }
    }

    @Test
    fun `a refused DATA does not block the next send`() {
        val (client, stream) = started(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("250 2.1.5 Ok"),
            c("DATA"), s("554 5.7.1 Error: policy rejection"),
            c("RSET"), s("250 2.0.0 Ok"),
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("250 2.1.5 Ok"),
            c("DATA"), s("354 go"),
            Step.Client(String(message)), c("."), s("250 2.0.0 Ok: queued"),
        )
        assertFailsWith<SmtpException> { client.sendOne() }
        client.sendOne()
        stream.assertDone()
    }

    @Test
    fun `4xx and 5xx after the dot fail at FINAL`() {
        val gmailBlocked = s(
            "552-5.7.0 This message was blocked because its content presents a potential",
            "552-5.7.0 security issue. Please visit",
            "552 5.7.0  https://support.google.com/mail/?p=BlockedMessage 5b1f17b1804b1-46e2a9b1b3fsm12.12 - gsmtp",
        )
        val replies = listOf(gmailBlocked to false, s("451 4.7.1 Service unavailable - try again later") to true)
        for ((reply, transient) in replies) {
            val (client, stream) = sendUntilDot(reply)
            val e = assertFailsWith<SmtpException> { client.sendOne() }
            assertEquals(Stage.FINAL, e.stage)
            assertEquals(transient, e.transient)
            stream.assertDone()
            assertEquals(60_000, stream.readTimeoutMs)
        }
    }

    @Test
    fun `timeout after the dot is uncertain and restores the read timeout`() {
        val (client, stream) = sendUntilDot(Step.Fail(SocketTimeoutException("Read timed out")))
        val e = assertFailsWith<SmtpUncertainException> { client.sendOne() }
        assertIs<SocketTimeoutException>(e.cause)
        assertEquals(600_000, stream.timeoutsAtRead.last())
        assertEquals(60_000, stream.readTimeoutMs)
        stream.assertDone()
    }

    @Test
    fun `connection loss after the dot is uncertain`() {
        val (client, stream) = sendUntilDot()
        val e = assertFailsWith<SmtpUncertainException> { client.sendOne() }
        assertIs<EOFException>(e.cause)
        stream.assertDone()
    }

    @Test
    fun `a write failure before the dot is a plain IO error, at the dot it is uncertain`() {
        // Flushes: 1 MAIL, 2 RCPT, 3 DATA, 4 body, 5 final dot.
        fun stream(failOnFlush: Int) = ScriptedStream(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("250 2.1.5 Ok"),
            c("DATA"), s("354 go"),
            failOnFlush = failOnFlush,
        )
        val body = assertFailsWith<IOException> { client(stream(4)).sendOne() }
        assertFalse(body is SmtpUncertainException)
        assertFailsWith<SmtpUncertainException> { client(stream(5)).sendOne() }
    }

    @Test
    fun `invalid addresses are refused before any byte is written`() {
        val bad = listOf(
            "a@b>\r\nRCPT TO:<spy@evil", "bob@example.org>", "<bob@example.org>", "bob@example.org\r\nDATA",
            "bob@example.org\nRSET", "bob", "bob@example.org ", "", "bob@exa mple.org",
        )
        for (address in bad) {
            val asRecipient = ScriptedStream()
            val e = assertFailsWith<IllegalArgumentException>(address) {
                client(asRecipient).send("alice@riseup.net", listOf("carol@example.net", address), message)
            }
            assertFalse(e.message!!.contains("example"), "exception message must not name addresses")
            asRecipient.assertDone()
            assertEquals("", asRecipient.transcript.toString())

            val asSender = ScriptedStream()
            assertFailsWith<IllegalArgumentException>(address) {
                client(asSender).send(address, listOf("carol@example.net"), message)
            }
            assertEquals("", asSender.transcript.toString())
        }
        val none = ScriptedStream()
        assertFailsWith<IllegalArgumentException> { client(none).send("alice@riseup.net", emptyList(), message) }
        none.assertDone()
    }

    // QUIT and connect

    @Test
    fun `quit ignores errors and always closes`() {
        val stream = ScriptedStream(c("QUIT"))
        client(stream).quit()
        assertTrue(stream.closed)
        stream.assertDone()
    }

    @Test(timeout = 30_000)
    fun `connect opens TLS, reads the greeting and sends EHLO`() {
        TlsServer("localhost") { socket ->
            val input = socket.inputStream.buffered()
            val output = socket.outputStream
            output.write("220 fews03-sea.riseup.net ESMTP (spam is not appreciated)\r\n".toByteArray())
            output.flush()
            assertEquals("EHLO [127.0.0.1]", input.readCrlfLine())
            output.write("250-fews03-sea.riseup.net\r\n250-SIZE 25600000\r\n250 SMTPUTF8\r\n".toByteArray())
            output.flush()
            assertEquals("QUIT", input.readCrlfLine())
            output.write("221 2.0.0 Bye\r\n".toByteArray())
            output.flush()
        }.use { server ->
            val log = mutableListOf<String>()
            val factory = TestTls.clientFactory("localhost")
            val client = SmtpClient.connect(Endpoint("localhost", server.port, TlsMode.IMPLICIT), AcceptAll, { log += it }, factory)
            assertEquals(25_600_000L, client.maxSize)
            assertEquals(setOf("SIZE", "SMTPUTF8"), client.extensions.keys)
            client.quit()
            server.join()
            assertEquals(listOf("S: 220", "C: EHLO", "S: 250", "C: QUIT", "S: 221"), log)
        }
    }

    @Test(timeout = 30_000)
    fun `connect closes the socket when the greeting is refused`() {
        TlsServer("localhost") { socket ->
            socket.outputStream.write("554 5.7.1 No SMTP service here\r\n".toByteArray())
            socket.outputStream.flush()
            assertEquals(-1, socket.inputStream.read())
        }.use { server ->
            val e = assertFailsWith<SmtpException> {
                SmtpClient.connect(Endpoint("localhost", server.port, TlsMode.IMPLICIT), AcceptAll, {}, TestTls.clientFactory("localhost"))
            }
            assertEquals(Stage.CONNECT, e.stage)
            server.join()
        }
    }

    private fun SmtpClient.sendOne() = send("a@example.org", listOf("b@example.org"), message)

    private object AcceptAll : SocketHooks {
        override fun verifyHostname(host: String, session: SSLSession) = true
    }

    /** A started client whose dialog runs to the final dot; [afterDot] is the server's next step (none = EOF). */
    private fun sendUntilDot(vararg afterDot: Step): Pair<SmtpClient, ScriptedStream> {
        val stream = ScriptedStream(
            c("MAIL FROM:<a@example.org>"), s("250 2.1.0 Ok"),
            c("RCPT TO:<b@example.org>"), s("250 2.1.5 Ok"),
            c("DATA"), s("354 End data with <CR><LF>.<CR><LF>"),
            Step.Client(String(message)), c("."),
            *afterDot,
        )
        return client(stream) to stream
    }
}
