package app.plico.account

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthCredentialsTest {
    private val minute = 60_000L
    private var now = 1_000 * minute

    private class FakeEndpoint(private val now: () -> Long) : TokenEndpoint {
        val refreshed = ArrayList<String>()
        val revoked = ArrayList<String>()
        var failure: Exception? = null

        /** The refresh token that the next refresh sends back (rotation); null sends none. */
        var rotateTo: String? = null

        override suspend fun refresh(refreshToken: String): AccessToken = refreshGrant(refreshToken).accessToken

        override suspend fun refreshGrant(refreshToken: String): Refreshed {
            failure?.let { throw it }
            refreshed += refreshToken
            return Refreshed(AccessToken("access-${refreshed.size}", now() + 60 * 60_000L), rotateTo)
        }

        override suspend fun revoke(token: String) {
            failure?.let { throw it }
            revoked += token
        }
    }

    private val endpoint = FakeEndpoint { now }

    private fun credentials(initial: AccessToken? = null) =
        OAuthCredentials("me@gmail.com", { "refresh-1" }, endpoint, initial, clock = { now }, hosts = OAuthIssuer.GOOGLE.hosts)

    private val smtpOff = "Microsoft does not let apps send mail"

    private fun microsoft(initial: AccessToken? = null) = OAuthCredentials(
        "me@outlook.com", { "refresh-1" }, endpoint, initial, clock = { now },
        hosts = OAuthIssuer.MICROSOFT.hosts, smtpOff = smtpOff,
    )

    private fun fresh() = AccessToken("setup", now + 60 * minute)

    private suspend fun OAuthCredentials.imapLogin(): String {
        val (client, stream) = imap(IMAP_OK)
        authImap(client)
        return stream.saslResponse()
    }

    private suspend fun OAuthCredentials.smtpLogin(): String {
        val (client, stream) = smtp(SMTP_OK)
        authSmtp(client)
        return stream.saslResponse()
    }

    @Test
    fun `first login refreshes and sends XOAUTH2`() = runTest {
        val credentials = credentials()
        assertNull(credentials.expiresAt)
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-1\u0001\u0001", credentials.imapLogin())
        assertEquals(listOf("refresh-1"), endpoint.refreshed)
        assertEquals(now + 55 * minute, credentials.expiresAt)
    }

    @Test
    fun `IMAP needs 45 minutes left on the token`() = runTest {
        val credentials = credentials()
        credentials.imapLogin()
        now += 15 * minute
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-1\u0001\u0001", credentials.imapLogin())
        assertEquals(1, endpoint.refreshed.size)

        now += 1
        val expected = now + 55 * minute
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-2\u0001\u0001", credentials.imapLogin())
        assertEquals(2, endpoint.refreshed.size)
        assertEquals(expected, credentials.expiresAt)
    }

    @Test
    fun `SMTP accepts a token with 5 minutes left and keeps the IMAP expiry`() = runTest {
        val credentials = credentials()
        credentials.imapLogin()
        val sessionEnd = credentials.expiresAt
        now += 50 * minute
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-1\u0001\u0001", credentials.smtpLogin())
        now += 6 * minute
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-2\u0001\u0001", credentials.smtpLogin())
        assertEquals(sessionEnd, credentials.expiresAt, "only an IMAP login changes the session end")
    }

    @Test
    fun `a fresh initial token avoids the first refresh`() = runTest {
        val credentials = credentials(AccessToken("setup", now + 60 * minute))
        assertEquals("user=me@gmail.com\u0001auth=Bearer setup\u0001\u0001", credentials.imapLogin())
        assertEquals(emptyList(), endpoint.refreshed)
    }

    @Test
    fun `invalidate forces a refresh`() = runTest {
        val credentials = credentials()
        credentials.imapLogin()
        credentials.invalidate()
        credentials.imapLogin()
        assertEquals(2, endpoint.refreshed.size)
    }

    @Test
    fun `invalid_grant needs sign-in`() = runTest {
        val credentials = credentials()
        endpoint.failure = MailError.AuthRequired("invalid_grant")
        assertEquals("invalid_grant", assertFailsWith<MailError.AuthRequired> { credentials.imapLogin() }.message)
        assertNull(credentials.expiresAt)
    }

    @Test
    fun `a refused token is dropped`() = runTest {
        val credentials = credentials()
        val (client, _) = imap("+ eyJzdGF0dXMiOiI0MDAifQ==", "A1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        assertFailsWith<MailError.AuthRequired> { credentials.authImap(client) }
        assertNull(credentials.expiresAt)
        credentials.imapLogin()
        assertEquals(2, endpoint.refreshed.size)
    }

    @Test
    fun `a rotated refresh token is stored before the new token is used`() = runTest {
        val (client, stream) = imap(IMAP_OK)
        val rotations = ArrayList<Pair<String, String>>()
        val credentials = OAuthCredentials(
            "me@gmail.com", { "refresh-1" }, endpoint, clock = { now }, hosts = OAuthIssuer.GOOGLE.hosts,
            onRotated = { old, new ->
                assertEquals(emptyList(), stream.written(), "nothing sent before the rotation is stored")
                rotations += old to new
            },
        )
        endpoint.rotateTo = "refresh-2"
        credentials.authImap(client)
        assertEquals(listOf("refresh-1" to "refresh-2"), rotations)
        assertEquals("user=me@gmail.com\u0001auth=Bearer access-1\u0001\u0001", stream.saslResponse())
    }

    @Test
    fun `no rotation, no write`() = runTest {
        var writes = 0
        val credentials = OAuthCredentials(
            "me@gmail.com", { "refresh-1" }, endpoint, clock = { now }, hosts = OAuthIssuer.GOOGLE.hosts,
            onRotated = { _, _ -> writes++ },
        )
        credentials.imapLogin()
        credentials.invalidate()
        endpoint.rotateTo = "refresh-1"
        credentials.imapLogin()
        credentials.invalidate()
        endpoint.rotateTo = ""
        credentials.imapLogin()
        assertEquals(0, writes)
        assertEquals(3, endpoint.refreshed.size)
    }

    @Test
    fun `a failed rotation write stops the login`() = runTest {
        val credentials = OAuthCredentials(
            "me@gmail.com", { "refresh-1" }, endpoint, clock = { now }, hosts = OAuthIssuer.GOOGLE.hosts,
            onRotated = { _, _ -> throw IOException("disk full") },
        )
        endpoint.rotateTo = "refresh-2"
        val (client, stream) = imap(IMAP_OK)
        assertFailsWith<IOException> { credentials.authImap(client) }
        assertEquals(emptyList(), stream.written())
    }

    @Test
    fun `a server that is not a host of the issuer gets no token`() = runTest {
        for (credentials in listOf(credentials(fresh()), microsoft(fresh()))) {
            val (imapClient, imapStream) = imap(IMAP_OK, host = "imap.evil.net")
            val error = assertFailsWith<MailError.Setup> { credentials.authImap(imapClient) }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is WrongHostException })
            val (smtpClient, smtpStream) = smtp(SMTP_OK, host = "smtp.evil.net")
            assertFailsWith<MailError.Setup> { credentials.authSmtp(smtpClient) }
            assertEquals(emptyList(), imapStream.written())
            assertEquals(emptyList(), smtpStream.written())
        }
        val (client, _) = imap(IMAP_OK, host = "outlook.office365.com")
        assertFailsWith<MailError.Setup> { credentials(fresh()).authImap(client) }
        val (gmail, _) = imap(IMAP_OK)
        assertFailsWith<MailError.Setup> { microsoft(fresh()).authImap(gmail) }
        assertEquals(emptyList(), endpoint.refreshed, "no refresh either")
    }

    @Test
    fun `no hosts refuse every server`() = runTest {
        val credentials = OAuthCredentials("me@gmail.com", { "refresh-1" }, endpoint, clock = { now })
        assertFailsWith<MailError.Setup> { credentials.authImap(imap(IMAP_OK).first) }
        assertEquals(emptyList(), endpoint.refreshed)
    }

    @Test
    fun `Microsoft SMTP refused twice is a setup problem after one refresh`() = runTest {
        val credentials = microsoft(fresh())
        val (client, stream) = smtp(
            "535 5.7.3 Authentication unsuccessful [AM0PR01CA0001.eurprd01.prod.exchangelabs.com]",
            "535 5.7.3 Authentication unsuccessful [AM0PR01CA0001.eurprd01.prod.exchangelabs.com]",
            host = "smtp-mail.outlook.com",
        )
        val error = assertFailsWith<MailError.Setup> { credentials.authSmtp(client) }
        assertEquals(smtpOff, error.message)
        assertTrue(MailErrors.hasSmtpCause(error))
        assertEquals(1, endpoint.refreshed.size)
        assertEquals(2, stream.written().count { it.startsWith("AUTH XOAUTH2") })
    }

    @Test
    fun `Microsoft SMTP accepts the refreshed token`() = runTest {
        val credentials = microsoft(fresh())
        val (client, stream) = smtp("535 5.7.3 Authentication unsuccessful", SMTP_OK, host = "smtp.office365.com")
        credentials.authSmtp(client)
        assertEquals(1, endpoint.refreshed.size)
        assertEquals("user=me@outlook.com\u0001auth=Bearer access-1\u0001\u0001", decode(stream.written()[1]))
    }

    @Test
    fun `Microsoft SMTP AUTH off is a setup problem at once`() = runTest {
        val credentials = microsoft(fresh())
        val (client, stream) = smtp(
            "535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Tenant.",
            host = "smtp-mail.outlook.com",
        )
        val error = assertFailsWith<MailError.Setup> { credentials.authSmtp(client) }
        assertEquals(smtpOff, error.message)
        assertEquals(emptyList(), endpoint.refreshed)
        assertEquals(1, stream.written().size)
    }

    @Test
    fun `a failed refresh during Microsoft SMTP needs sign-in`() = runTest {
        val credentials = microsoft(fresh())
        val (client, _) = smtp("535 5.7.3 Authentication unsuccessful", host = "smtp-mail.outlook.com")
        endpoint.failure = MailError.AuthRequired("invalid_grant")
        val error = assertFailsWith<MailError.AuthRequired> { credentials.authSmtp(client) }
        assertEquals("invalid_grant", error.message)
    }

    @Test
    fun `Gmail SMTP refusal still needs sign-in`() = runTest {
        val credentials = credentials(fresh())
        val (client, stream) = smtp("535 5.7.8 Username and Password not accepted")
        assertFailsWith<MailError.AuthRequired> { credentials.authSmtp(client) }
        assertEquals(emptyList(), endpoint.refreshed)
        assertEquals(1, stream.written().size)
    }

    @Test
    fun `Microsoft IMAP logs in with the sign-in name`() = runTest {
        val credentials = OAuthCredentials(
            "jdoe@contoso.onmicrosoft.com", { "refresh-1" }, endpoint, fresh(), clock = { now },
            hosts = OAuthIssuer.MICROSOFT.hosts, smtpOff = smtpOff,
        )
        val (client, stream) = imap(IMAP_OK, host = "outlook.office365.com")
        credentials.authImap(client)
        assertEquals("user=jdoe@contoso.onmicrosoft.com\u0001auth=Bearer setup\u0001\u0001", stream.saslResponse())
    }

    private fun decode(line: String) = String(Base64.getDecoder().decode(line.substringAfterLast(' ')), Charsets.UTF_8)

    @Test
    fun `revoke posts the refresh token and ignores failures`() = runTest {
        credentials().revoke()
        assertEquals(listOf("refresh-1"), endpoint.revoked)
        endpoint.failure = IOException("offline")
        credentials().revoke()
    }
}
