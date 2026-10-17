package app.plico.ui.setup

import app.plico.account.MailError
import app.plico.account.OAuthIssuer
import app.plico.account.ProbeException
import app.plico.data.Provider
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.RespCode
import app.plico.mail.net.Endpoint
import app.plico.mail.net.PlaintextTimeoutException
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsHandshakeException
import app.plico.mail.net.TlsMode
import app.plico.mail.smtp.SmtpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemTest {
    private fun riseup(e: Throwable) = Problem.of(e, Provider.RISEUP, Method.PASSWORD)

    private fun other(e: Throwable) = Problem.of(e, Provider.OTHER, Method.PASSWORD)

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
        // Microsoft refused the new token at IMAP (AccountProbe): the IMAP text, not "did not accept the sign-in".
        val imapOff = "Microsoft did not open the mailbox."
        val refused = ImapNoException(null, "AUTHENTICATE failed.")
        val probe = failedAt(TlsMode.IMPLICIT, 993, MailError.Setup(imapOff, MailError.AuthRequired("AUTHENTICATE failed.", cause = refused)))
        assertEquals(Problem(Problem.Kind.SETUP, imapOff), Problem.of(probe, Provider.MICROSOFT, Method.BROWSER))
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

    private fun failedAt(tls: TlsMode, port: Int, error: MailError) =
        ProbeException(Endpoint("mail.example.org", port, tls), error)

    @Test
    fun `a missing greeting from a STARTTLS server means the port expects SSL TLS`() {
        val beforeTls = MailError.Network(PlaintextTimeoutException(SocketTimeoutException("Read timed out")))
        // IMAP with STARTTLS, also on 993, and SMTP with STARTTLS on 465: the server waits for a TLS handshake.
        assertEquals(Problem(Problem.Kind.NO_TLS), other(failedAt(TlsMode.STARTTLS, 143, beforeTls)))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(failedAt(TlsMode.STARTTLS, 993, beforeTls)))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(failedAt(TlsMode.STARTTLS, 465, beforeTls)))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(beforeTls))
        // A read timeout after the switch to TLS (LOGIN, LIST, EXAMINE INBOX, SMTP AUTH): a slow server or network.
        val afterTls = MailError.Network(SocketTimeoutException("Read timed out"))
        assertEquals(Problem(Problem.Kind.NETWORK), other(failedAt(TlsMode.STARTTLS, 143, afterTls)))
        assertEquals(Problem(Problem.Kind.NETWORK), other(failedAt(TlsMode.STARTTLS, 587, afterTls)))
        assertEquals(Problem(Problem.Kind.NETWORK), other(failedAt(TlsMode.IMPLICIT, 993, afterTls)))
        assertEquals(Problem(Problem.Kind.NETWORK), other(afterTls))
        // The connection itself timed out: a network problem, also with STARTTLS.
        val connect = SocketTimeoutException("failed to connect to imap.example.org/192.0.2.1 (port 143) after 20000ms")
        assertEquals(Problem(Problem.Kind.NETWORK), other(failedAt(TlsMode.STARTTLS, 143, MailError.Network(connect))))
    }

    @Test
    fun `built-in servers have known TLS modes, so a failed handshake there is a network problem`() {
        val reset = MailError.Network(TlsHandshakeException(SSLException("Connection reset by peer")))
        assertEquals(Problem(Problem.Kind.NETWORK), riseup(failedAt(TlsMode.IMPLICIT, 993, reset)))
        assertEquals(Problem(Problem.Kind.NETWORK), gmail(failedAt(TlsMode.IMPLICIT, 465, reset)))
        assertEquals(Problem(Problem.Kind.NO_TLS), other(failedAt(TlsMode.IMPLICIT, 993, reset)))
        // Microsoft SMTP uses STARTTLS on 587: a slow server is a network problem, not a wrong setting.
        val timeout = MailError.Network(PlaintextTimeoutException(SocketTimeoutException("Read timed out")))
        assertEquals(
            Problem(Problem.Kind.NETWORK),
            Problem.of(failedAt(TlsMode.STARTTLS, 587, timeout), Provider.MICROSOFT, Method.BROWSER),
        )
        // A refused STARTTLS is never a network problem.
        val refused = MailError.Tls(StartTlsException("STARTTLS refused"))
        assertEquals(
            Problem(Problem.Kind.NO_TLS),
            Problem.of(failedAt(TlsMode.STARTTLS, 587, refused), Provider.MICROSOFT, Method.BROWSER),
        )
    }

    @Test
    fun `a failed connection test is classified by its error`() {
        val refused = MailError.AuthRequired("[AUTHENTICATIONFAILED] Authentication failed.")
        assertEquals(Problem(Problem.Kind.WRONG_PASSWORD), other(failedAt(TlsMode.IMPLICIT, 993, refused)))
        assertEquals(Problem(Problem.Kind.SETUP, "hint"), other(failedAt(TlsMode.IMPLICIT, 465, MailError.Setup("hint"))))
    }

    @Test
    fun `network, TLS and throttling`() {
        assertEquals(Problem(Problem.Kind.NETWORK), riseup(MailError.Network(IOException())))
        assertEquals(Problem(Problem.Kind.NETWORK), riseup(UnknownHostException("mail.riseup.net")))
        assertEquals(Problem(Problem.Kind.NETWORK), gmail(IOException("Token endpoint: HTTP 503"), Method.BROWSER))
        val certificate = SSLHandshakeException("bad").apply { initCause(CertificateException("untrusted")) }
        assertEquals(Problem(Problem.Kind.TLS), riseup(certificate))
        assertEquals(Problem(Problem.Kind.TLS), riseup(MailError.Tls(certificate)))
        assertEquals(Problem(Problem.Kind.THROTTLED), riseup(MailError.Throttled("Maximum number of connections")))
    }

    @Test
    fun `a certificate failure of a server that the user can change asks for the server name`() {
        // Shared hosting: imap.<domain> has the certificate of the hosting server (MailSocket's hostname check).
        val name = MailError.Tls(SSLPeerUnverifiedException("Hostname verification failed"))
        assertEquals(Problem(Problem.Kind.TLS_NAME), other(failedAt(TlsMode.IMPLICIT, 993, name)))
        // A certificate failure with STARTTLS is still a certificate failure.
        val untrusted = MailError.Tls(SSLHandshakeException("bad").apply { initCause(CertificateException("untrusted")) })
        assertEquals(Problem(Problem.Kind.TLS_NAME), other(failedAt(TlsMode.STARTTLS, 587, untrusted)))
        // The built-in servers have fixed names: there a network that examines connections is the usual cause.
        assertEquals(Problem(Problem.Kind.TLS), riseup(failedAt(TlsMode.IMPLICIT, 993, name)))
        assertEquals(Problem(Problem.Kind.TLS), gmail(failedAt(TlsMode.IMPLICIT, 993, name)))
        assertEquals(Problem(Problem.Kind.TLS), Problem.of(failedAt(TlsMode.IMPLICIT, 993, name), Provider.MICROSOFT, Method.BROWSER))
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
