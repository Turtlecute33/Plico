package app.plico.account

import app.plico.data.Provider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PasswordCredentialsTest {

    @Test
    fun `Gmail app passwords lose their spaces`() {
        assertEquals("abcdefghijklmnop", PasswordCredentials.normalize(Provider.GMAIL, "abcd efgh ijkl mnop"))
        assertEquals("abcdefghijklmnop", PasswordCredentials.normalize(Provider.GMAIL, " abcd efgh\tijkl mnop\n"))
        assertEquals("abcdefghijklmnop", PasswordCredentials.normalize(Provider.GMAIL, "abcdefghijklmnop"))
    }

    @Test
    fun `Riseup passwords stay as typed`() {
        assertEquals(" pass word ", PasswordCredentials.normalize(Provider.RISEUP, " pass word "))
    }

    @Test
    fun `IMAP login sends PLAIN with the normalised password`() = runTest {
        val (client, stream) = imap(IMAP_OK)
        PasswordCredentials(Provider.GMAIL, "me@gmail.com", "abcd efgh ijkl mnop").authImap(client)
        assertEquals("A1 AUTHENTICATE PLAIN", stream.written().single().substringBeforeLast(' '))
        assertEquals("\u0000me@gmail.com\u0000abcdefghijklmnop", stream.saslResponse())
    }

    @Test
    fun `the password is read for every login`() = runTest {
        var reads = 0
        val credentials = PasswordCredentials(Provider.RISEUP, "me@riseup.net") { reads++; "pass word" }
        val (first, stream) = imap(IMAP_OK)
        credentials.authImap(first)
        credentials.authSmtp(smtp(SMTP_OK).first)
        assertEquals(2, reads)
        assertEquals("\u0000me@riseup.net\u0000pass word", stream.saslResponse())
        assertNull(credentials.expiresAt)
    }

    @Test
    fun `refused IMAP login needs sign-in`() = runTest {
        val (client, _) = imap("A1 NO [AUTHENTICATIONFAILED] Authentication failed.")
        val error = assertFailsWith<MailError.AuthRequired> {
            PasswordCredentials(Provider.RISEUP, "me@riseup.net", "wrong").authImap(client)
        }
        assertEquals("Authentication failed.", error.message)
    }

    @Test
    fun `Gmail ALERT on login needs sign-in`() = runTest {
        val (client, _) = imap("A1 NO [ALERT] Application-specific password required: https://support.google.com (Failure)")
        assertFailsWith<MailError.AuthRequired> { PasswordCredentials(Provider.GMAIL, "me@gmail.com", "pw").authImap(client) }
    }

    @Test
    fun `temporary IMAP refusal is Throttled`() = runTest {
        val (client, _) = imap("A1 NO [UNAVAILABLE] Temporary authentication failure.")
        assertFailsWith<MailError.Throttled> { PasswordCredentials(Provider.RISEUP, "me@riseup.net", "pw").authImap(client) }
    }

    @Test
    fun `SMTP login sends PLAIN`() = runTest {
        val (client, stream) = smtp(SMTP_OK)
        PasswordCredentials(Provider.RISEUP, "me@riseup.net", "secret").authSmtp(client)
        assertEquals("AUTH PLAIN", stream.written().single().substringBeforeLast(' '))
        assertEquals("\u0000me@riseup.net\u0000secret", stream.saslResponse())
    }

    @Test
    fun `refused SMTP login needs sign-in, 4xx is Throttled`() = runTest {
        val credentials = PasswordCredentials(Provider.GMAIL, "me@gmail.com", "pw")
        assertFailsWith<MailError.AuthRequired> { credentials.authSmtp(smtp("535 5.7.8 Username and Password not accepted").first) }
        assertFailsWith<MailError.Throttled> { credentials.authSmtp(smtp("454 4.7.0 Too many login attempts").first) }
    }

    @Test
    fun `a missing stored secret propagates`() = runTest {
        val credentials = PasswordCredentials(Provider.RISEUP, "me@riseup.net") { throw MailError.AuthRequired("no secret") }
        assertFailsWith<MailError.AuthRequired> { credentials.authImap(imap(IMAP_OK).first) }
    }
}
