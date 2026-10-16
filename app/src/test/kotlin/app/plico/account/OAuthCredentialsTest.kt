package app.plico.account

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OAuthCredentialsTest {
    private val minute = 60_000L
    private var now = 1_000 * minute

    private class FakeEndpoint(private val now: () -> Long) : TokenEndpoint {
        val refreshed = ArrayList<String>()
        val revoked = ArrayList<String>()
        var failure: Exception? = null

        override suspend fun refresh(refreshToken: String): AccessToken {
            failure?.let { throw it }
            refreshed += refreshToken
            return AccessToken("access-${refreshed.size}", now() + 60 * 60_000L)
        }

        override suspend fun revoke(token: String) {
            failure?.let { throw it }
            revoked += token
        }
    }

    private val endpoint = FakeEndpoint { now }

    private fun credentials(initial: AccessToken? = null) =
        OAuthCredentials("me@gmail.com", { "refresh-1" }, endpoint, initial) { now }

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
    fun `revoke posts the refresh token and ignores failures`() = runTest {
        credentials().revoke()
        assertEquals(listOf("refresh-1"), endpoint.revoked)
        endpoint.failure = IOException("offline")
        credentials().revoke()
    }
}
