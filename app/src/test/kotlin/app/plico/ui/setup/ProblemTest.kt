package app.plico.ui.setup

import app.plico.account.MailError
import app.plico.account.OAuthIssuer
import app.plico.data.Provider
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.RespCode
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsHandshakeException
import app.plico.mail.smtp.SmtpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemTest {
    private fun riseup(e: Throwable) = Problem.of(e, Provider.RISEUP, Method.PASSWORD)

    private fun other(e: Throwable, starttls: Boolean = false) = Problem.of(e, Provider.OTHER, Method.PASSWORD, starttls)

    private fun gmail(e: Throwable, method: Method = Method.PASSWORD) = Problem.of(e, Provider.GMAIL, method)

    @Test
    fun `a refused password is a wrong password on Riseup and other servers`() {
        val refused = MailError.AuthRequired("[AUTHENTICATIONFAILED] Authentication failed.")
        assertEquals(Problem(Problem.Kind.WRONG_PASSWORD), riseup(refused))
        assertEquals(Problem(Problem.Kind.WRONG_PASSWORD), other(refused))
        // Also with Gmail's app-password text on another server: only Gmail has app-password kinds.
        assertEquals(Problem(Problem.Kind.WRONG_PASSWORD), other(MailError.AuthRequired("Application-specific password required")))
        assertEquals(
            Problem(Problem.Kind.WRONG_PASSWORD),
            Problem.of(refused, Provider.MICROSOFT, Method.PASSWORD),
        )
        // Raw :mail exceptions are classified first.
        assertEquals(
            Problem(Problem.Kind.WRONG_PASSWORD),
            riseup(ImapNoException(RespCode("AUTHENTICATIONFAILED", emptyList()), "Authentication failed.")),
        )
        assertEquals(
            Problem(Problem.Kind.WRONG_PASSWORD),
            other(SmtpException(535, "5.7.8", "Authentication failed", SmtpException.Stage.AUTH)),
        )
    }

    @Test
    fun `Gmail asks for an app password`() {
        val imap = MailError.AuthRequired(
            "Application-specific password required: https://support.google.com/accounts/answer/185833 (Failure)",
        )
        assertEquals(Problem(Problem.Kind.APP_PASSWORD_REQUIRED), gmail(imap))
        val smtp = MailError.AuthRequired("5.7.9 Application-specific password required. For more information, go to")
        assertEquals(Problem(Problem.Kind.APP_PASSWORD_REQUIRED), gmail(smtp))
    }

    @Test
    fun `Gmail refuses the app password`() {
        assertEquals(
            Problem(Problem.Kind.APP_PASSWORD_REFUSED),
            gmail(MailError.AuthRequired("[AUTHENTICATIONFAILED] Invalid credentials (Failure)")),
        )
    }

    @Test
    fun `OAuth and device refusals keep their text and name the issuer`() {
        assertEquals(
            Problem(Problem.Kind.SIGN_IN_REFUSED, "You did not give access.", OAuthIssuer.GOOGLE),
            gmail(MailError.AuthRequired("You did not give access."), Method.BROWSER),
        )
        assertEquals(
            Problem(Problem.Kind.SIGN_IN_REFUSED, issuer = OAuthIssuer.GOOGLE),
            gmail(MailError.AuthRequired(""), Method.DEVICE),
        )
        val microsoft = Problem.of(MailError.AuthRequired("Denied."), Provider.MICROSOFT, Method.BROWSER)
        assertEquals(Problem(Problem.Kind.SIGN_IN_REFUSED, "Denied.", OAuthIssuer.MICROSOFT), microsoft)
        assertEquals("Microsoft", microsoft.issuer?.brand)
    }

    @Test
    fun `setup problems show the account module's text`() {
        val hint = "Gmail does not show All Mail to IMAP."
        assertEquals(Problem(Problem.Kind.SETUP, hint), gmail(MailError.Setup(hint), Method.BROWSER))
    }

    @Test
    fun `no encrypted connection through the cause chain`() {
        val refused = StartTlsException("STARTTLS refused")
        assertEquals(Problem(Problem.Kind.NO_TLS), other(refused))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(MailError.Tls(refused)))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(MailError.Network(refused)))
        // SSL/TLS chosen on a STARTTLS port: the handshake meets plain text.
        val plain = TlsHandshakeException(SSLException("Unrecognized SSL message, plaintext connection?"))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(plain))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(MailError.Network(plain)))
    }

    @Test
    fun `a missing greeting with STARTTLS means the port expects SSL TLS`() {
        val timeout = SocketTimeoutException("Read timed out")
        assertEquals(Problem(Problem.Kind.NO_TLS), other(MailError.Network(timeout), starttls = true))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(timeout, starttls = true))
        // With implicit TLS, or when the connection itself timed out, it is a network problem.
        assertEquals(Problem(Problem.Kind.NETWORK), other(MailError.Network(timeout), starttls = false))
        val connect = SocketTimeoutException("failed to connect to imap.example.org/192.0.2.1 (port 143) after 20000ms")
        assertEquals(Problem(Problem.Kind.NETWORK), other(MailError.Network(connect), starttls = true))
    }

    @Test
    fun `network, TLS and throttling`() {
        assertEquals(Problem(Problem.Kind.NETWORK), riseup(MailError.Network(IOException())))
        assertEquals(Problem(Problem.Kind.NETWORK), riseup(UnknownHostException("mail.riseup.net")))
        assertEquals(Problem(Problem.Kind.NETWORK), gmail(IOException("Token endpoint: HTTP 503"), Method.BROWSER))
        val certificate = SSLHandshakeException("bad").apply { initCause(CertificateException("untrusted")) }
        assertEquals(Problem(Problem.Kind.TLS), riseup(certificate))
        assertEquals(Problem(Problem.Kind.TLS), riseup(MailError.Tls(certificate)))
        // A certificate failure with STARTTLS is still a certificate failure.
        assertEquals(Problem(Problem.Kind.TLS), other(MailError.Tls(certificate), starttls = true))
        assertEquals(Problem(Problem.Kind.THROTTLED), riseup(MailError.Throttled("Maximum number of connections")))
    }

    @Test
    fun `other refusals show the server text`() {
        assertEquals(Problem(Problem.Kind.SERVER, "Mailbox is locked"), riseup(MailError.Server("Mailbox is locked")))
    }

    @Test
    fun `a typo names the domain that was probably meant`() {
        val typo = SetupState().typo("gmail.com", "jo@gmial.com").problem
        assertEquals(Problem(Problem.Kind.TYPO, "gmail.com"), typo)
    }
}
