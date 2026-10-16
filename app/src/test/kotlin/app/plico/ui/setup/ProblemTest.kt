package app.plico.ui.setup

import app.plico.account.MailError
import app.plico.data.Provider
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.RespCode
import app.plico.mail.smtp.SmtpException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemTest {
    private fun riseup(e: Throwable) = Problem.of(e, Provider.RISEUP, Method.PASSWORD)

    private fun gmail(e: Throwable, method: Method = Method.PASSWORD) = Problem.of(e, Provider.GMAIL, method)

    @Test
    fun `a refused Riseup login is a wrong password`() {
        val refused = MailError.AuthRequired("[AUTHENTICATIONFAILED] Authentication failed.")
        assertEquals(Problem(Problem.Kind.WRONG_PASSWORD), riseup(refused))
        // Raw :mail exceptions are classified first.
        assertEquals(
            Problem(Problem.Kind.WRONG_PASSWORD),
            riseup(ImapNoException(RespCode("AUTHENTICATIONFAILED", emptyList()), "Authentication failed.")),
        )
        assertEquals(
            Problem(Problem.Kind.WRONG_PASSWORD),
            riseup(SmtpException(535, "5.7.8", "Authentication failed", SmtpException.Stage.AUTH)),
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
    fun `OAuth and device refusals keep their text`() {
        assertEquals(
            Problem(Problem.Kind.SIGN_IN_REFUSED, "You did not give access."),
            gmail(MailError.AuthRequired("You did not give access."), Method.BROWSER),
        )
        assertEquals(Problem(Problem.Kind.SIGN_IN_REFUSED), gmail(MailError.AuthRequired(""), Method.DEVICE))
    }

    @Test
    fun `setup problems show the account module's text`() {
        val hint = "Gmail does not show All Mail to IMAP."
        assertEquals(Problem(Problem.Kind.SETUP, hint), gmail(MailError.Setup(hint), Method.BROWSER))
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
    fun `other refusals show the server text`() {
        assertEquals(Problem(Problem.Kind.SERVER, "Mailbox is locked"), riseup(MailError.Server("Mailbox is locked")))
    }
}
