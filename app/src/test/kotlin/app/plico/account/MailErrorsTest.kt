package app.plico.account

import app.plico.R
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapMissingCapabilityException
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.ImapProtocolException
import app.plico.mail.imap.RespCode
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsHandshakeException
import app.plico.mail.smtp.SmtpAuthUnavailableException
import app.plico.mail.smtp.SmtpException
import app.plico.mail.smtp.SmtpException.Stage
import app.plico.mail.smtp.SmtpRecipientException
import app.plico.mail.smtp.SmtpUncertainException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertPathValidatorException.BasicReason
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MailErrorsTest {
    private fun classify(t: Throwable, validated: Boolean = true) = MailErrors.classify(t, validated)

    private fun no(code: String?, text: String) = ImapNoException(code?.let { RespCode(it, emptyList()) }, text)

    private fun handshake(cause: Throwable) = SSLHandshakeException(cause.toString()).apply { initCause(cause) }

    @Test
    fun `MailError passes through`() {
        val error = MailError.Setup("x")
        assertSame(error, classify(error))
    }

    @Test
    fun `certificate failures are Tls on a validated network`() {
        val anchor = CertPathValidatorException("Trust anchor for certification path not found.")
        val untrusted = handshake(CertificateException(anchor))
        assertIs<MailError.Tls>(classify(untrusted))
        assertIs<MailError.Tls>(classify(handshake(CertificateExpiredException("expired"))))
        assertIs<MailError.Tls>(classify(handshake(CertPathValidatorException("bad path"))))
        assertIs<MailError.Tls>(classify(SSLPeerUnverifiedException("Hostname verification failed")))
        assertIs<MailError.Tls>(classify(IOException("wrapped", handshake(CertificateException("x")))))
    }

    @Test
    fun `certificate failures are Network without validation`() {
        assertIs<MailError.Network>(classify(handshake(CertificateException("captive portal")), validated = false))
        assertIs<MailError.Network>(classify(SSLPeerUnverifiedException("Hostname verification failed"), validated = false))
    }

    @Test
    fun `other SSL exceptions are Network`() {
        val reset = SSLException("Read error: ssl=0x7b: I/O error during system call, Connection reset by peer")
        assertIs<MailError.Network>(classify(reset))
        assertIs<MailError.Network>(classify(SSLHandshakeException("Connection closed by peer")))
    }

    @Test
    fun `a cyclic cause chain ends`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertIs<MailError.Network>(classify(a))
    }

    @Test
    fun `IMAP authentication codes need sign-in`() {
        for (code in listOf("AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "EXPIRED")) {
            val error = classify(no(code, "Invalid credentials (Failure)"))
            assertIs<MailError.AuthRequired>(error)
            assertEquals("Invalid credentials (Failure)", error.message)
        }
    }

    @Test
    fun `IMAP temporary codes are Throttled`() {
        for (code in listOf("UNAVAILABLE", "INUSE", "THROTTLED", "OVERQUOTA")) {
            assertIs<MailError.Throttled>(classify(no(code, "Try later")), code)
        }
        assertIs<MailError.Throttled>(classify(no("ALERT", "Too many simultaneous connections. (Failure)")))
        assertIs<MailError.Throttled>(classify(no("ALERT", "Account exceeded command or bandwidth limits. (Failure)")))
    }

    @Test
    fun `other IMAP NO and BAD are Server with the text`() {
        val nonexistent = classify(no("NONEXISTENT", "Unknown Mailbox"))
        assertIs<MailError.Server>(nonexistent)
        assertEquals("Unknown Mailbox", nonexistent.message)
        assertIs<MailError.Server>(classify(no(null, "Command failed")))
        val bad = classify(ImapBadException("Could not parse command"))
        assertIs<MailError.Server>(bad)
        assertEquals("Could not parse command", bad.message)
    }

    @Test
    fun `BYE is Network unless it throttles`() {
        assertIs<MailError.Network>(classify(ImapByeException(null, "Session expired, please login again.")))
        assertIs<MailError.Throttled>(classify(ImapByeException(RespCode("UNAVAILABLE", emptyList()), "Server shutting down")))
        assertIs<MailError.Throttled>(
            classify(ImapByeException(
                    null,
                    "Maximum number of connections from user+IP exceeded (mail_max_userip_connections=20)",
                ),
            ),
        )
    }

    @Test
    fun `missing capabilities are Setup`() {
        val error = classify(ImapMissingCapabilityException(setOf("MOVE", "IDLE")))
        assertIs<MailError.Setup>(error)
        assertTrue("IDLE MOVE" in error.message.orEmpty())
    }

    /** [text] is the reply as SmtpClient reports it; classification needs only the code and the stage. */
    private fun reply(text: String, stage: Stage) = SmtpException(text.take(3).toInt(), null, text, stage)

    @Test
    fun `SMTP auth refusals need sign-in`() {
        assertIs<MailError.AuthRequired>(classify(reply("535 5.7.8 Username and Password not accepted", Stage.AUTH)))
        assertIs<MailError.AuthRequired>(classify(reply("534 5.7.9 Application-specific password required", Stage.AUTH)))
        assertIs<MailError.AuthRequired>(classify(reply("535 Authentication failed", Stage.MAIL)))
    }

    @Test
    fun `SMTP 4xx is Throttled`() {
        assertIs<MailError.Throttled>(classify(reply("454 4.7.0 Too many login attempts", Stage.AUTH)))
        assertIs<MailError.Throttled>(classify(reply("421 4.7.0 Try again later", Stage.CONNECT)))
        assertIs<MailError.Throttled>(classify(reply("451 4.3.0 Mail server temporarily rejected message", Stage.DATA)))
    }

    @Test
    fun `other SMTP refusals are Server`() {
        val tooBig = classify(reply("552 5.3.4 Message size exceeds fixed limit", Stage.FINAL))
        assertIs<MailError.Server>(tooBig)
        assertEquals("552 5.3.4 Message size exceeds fixed limit", tooBig.message)
        assertIs<MailError.Server>(classify(reply("554 No service", Stage.CONNECT)))
        assertIs<MailError.Server>(classify(reply("550 5.7.1 Relaying denied", Stage.MAIL)))
    }

    @Test
    fun `recipient and uncertain send failures are never Network`() {
        assertIs<MailError.Server>(classify(SmtpRecipientException(mapOf("a@example.org" to "550 No such user"))))
        assertIs<MailError.Server>(classify(SmtpUncertainException(SocketTimeoutException("Read timed out"))))
    }

    @Test
    fun `IOExceptions are Network`() {
        val errors = listOf(
            SocketTimeoutException(),
            UnknownHostException("mail.riseup.net"),
            ConnectException(),
            EOFException(),
            ImapProtocolException("Line too long"),
        )
        for (e in errors) {
            assertIs<MailError.Network>(classify(e, validated = false), e.toString())
        }
    }

    @Test
    fun `other exceptions are Server`() {
        val error = classify(IllegalArgumentException("Invalid address"))
        assertIs<MailError.Server>(error)
        assertEquals("Invalid address", error.message)
        assertEquals("IllegalStateException", classify(IllegalStateException()).message)
    }

    @Test
    fun `refused AUTHENTICATE needs sign-in`() {
        assertIs<MailError.AuthRequired>(MailErrors.authFailure(no(null, "Authentication failed.")))
        assertIs<MailError.AuthRequired>(MailErrors.authFailure(no("ALERT", GMAIL_APP_PASSWORD_REQUIRED)))
        assertIs<MailError.AuthRequired>(MailErrors.authFailure(no("AUTHENTICATIONFAILED", "Authentication failed.")))
    }

    @Test
    fun `temporary AUTHENTICATE refusals keep their class`() {
        assertIs<MailError.Throttled>(MailErrors.authFailure(no("UNAVAILABLE", "Temporary authentication failure.")))
        assertIs<MailError.Throttled>(MailErrors.authFailure(no("ALERT", "Too many simultaneous connections. (Failure)")))
        assertIs<MailError.Server>(MailErrors.authFailure(no("SERVERBUG", "Internal error occurred.")))
    }

    @Test
    fun `STARTTLS failures are Tls on a validated network`() {
        val refused = StartTlsException("STARTTLS refused")
        assertIs<MailError.Tls>(classify(refused))
        assertIs<MailError.Network>(classify(refused, validated = false))
    }

    @Test
    fun `a TLS handshake without TLS on the port is Network`() {
        val plaintext = TlsHandshakeException(SSLException("Unrecognized SSL message, plaintext connection?"))
        assertIs<MailError.Network>(classify(plaintext))
        // A certificate cause still wins.
        assertIs<MailError.Tls>(classify(TlsHandshakeException(handshake(CertificateException("untrusted")))))
    }

    @Test
    fun `a TLS problem keeps its cause`() {
        fun reason(t: Throwable) = MailErrors.tlsReason(classify(t))
        val expired = CertPathValidatorException("timestamp check failed", null, null, -1, BasicReason.EXPIRED)
        assertEquals(TlsReason.EXPIRED, reason(handshake(CertificateException(expired))))
        assertEquals(TlsReason.EXPIRED, reason(handshake(CertificateExpiredException("expired"))))
        val early = CertPathValidatorException("timestamp check failed", null, null, -1, BasicReason.NOT_YET_VALID)
        assertEquals(TlsReason.NOT_YET_VALID, reason(handshake(CertificateException(early))))
        assertEquals(TlsReason.NOT_YET_VALID, reason(handshake(CertificateNotYetValidException("not yet"))))
        assertEquals(TlsReason.NAME_MISMATCH, reason(SSLPeerUnverifiedException("Hostname verification failed")))
        val name = CertificateException("No subjectAltNames on the certificate match")
        assertEquals(TlsReason.NAME_MISMATCH, reason(IOException("wrapped", handshake(name))))
        val anchor = CertPathValidatorException("Trust anchor for certification path not found.")
        assertEquals(TlsReason.UNTRUSTED, reason(handshake(CertificateException(anchor))))
        assertEquals(TlsReason.NO_TLS, reason(StartTlsException("STARTTLS refused")))
    }

    @Test
    fun `a stored TLS reason is read back`() {
        assertEquals(TlsReason.EXPIRED, TlsReason.of(TlsReason.EXPIRED.name))
        assertEquals(null, TlsReason.of(null))
        assertEquals(null, TlsReason.of("something else"))
    }

    @Test
    fun `no usable AUTH mechanism is Setup`() {
        val error = classify(SmtpAuthUnavailableException())
        assertIs<MailError.Setup>(error)
        assertIs<SmtpAuthUnavailableException>(error.cause)
        assertIs<ImapMissingCapabilityException>(classify(ImapMissingCapabilityException(setOf("AUTH=PLAIN"))).cause)
    }

    @Test
    fun `Microsoft SMTP AUTH off is Setup before the 535 rule`() {
        for (text in listOf(
            "535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Tenant.",
            "535 5.7.30 Basic authentication is not supported for Client Submission.",
        )) {
            val e = SmtpException(535, text.substring(4).substringBefore(' '), text, Stage.AUTH)
            val error = classify(e)
            assertIs<MailError.Setup>(error, text)
            assertSame(e, error.cause)
        }
        assertIs<MailError.AuthRequired>(classify(SmtpException(535, "5.7.3", "535 5.7.3 Authentication unsuccessful", Stage.AUTH)))
    }

    @Test
    fun `authenticated but not connected is Setup`() {
        val e = no(null, "User is authenticated but not connected.")
        val error = classify(e)
        assertIs<MailError.Setup>(error)
        assertSame(e, error.cause)
        // Before the auth codes: a sign-in again does not help.
        assertIs<MailError.Setup>(classify(no("AUTHORIZATIONFAILED", "User is Authenticated but not connected")))
        assertIs<MailError.Setup>(MailErrors.authFailure(e))
    }

    @Test
    fun `more connection limits are Throttled`() {
        assertIs<MailError.Throttled>(classify(no(null, "Too many connections from your IP")))
        assertIs<MailError.Throttled>(classify(ImapByeException(null, "Connection limit reached")))
        assertTrue(MailErrors.isThrottleText("too many connections"))
    }

    @Test
    fun `localized gives Microsoft text for a mailbox without IMAP`() {
        val text = { id: Int -> "text-$id" }
        val notConnected = classify(no(null, "User is authenticated but not connected."))
        val localized = MailErrors.localized(notConnected, text)
        assertIs<MailError.Setup>(localized)
        assertEquals("text-${R.string.account_error_ms_imap_off}", localized.message)
        assertSame(notConnected.cause, localized.cause)

        val wrongHost = wrongHost()
        assertEquals("text-${R.string.account_error_sign_in_kind}", MailErrors.localized(wrongHost, text).message)

        val other = MailError.Setup("Server lacks IMAP4REV1")
        assertSame(other, MailErrors.localized(other, text))
        val auth = MailError.AuthRequired("x")
        assertSame(auth, MailErrors.localized(auth, text))
    }

    @Test
    fun `SMTP causes are found in the chain`() {
        val smtp = SmtpException(535, "5.7.3", "535 5.7.3 x", Stage.AUTH)
        assertTrue(MailErrors.hasSmtpCause(MailError.Setup("off", MailError.AuthRequired("x", cause = smtp))))
        assertTrue(MailErrors.hasSmtpCause(smtp))
        assertTrue(!MailErrors.hasSmtpCause(MailError.AuthRequired("invalid_grant")))
    }

    private companion object {
        const val GMAIL_APP_PASSWORD_REQUIRED =
            "Application-specific password required: https://support.google.com/accounts/answer/185833 (Failure)"
    }
}
