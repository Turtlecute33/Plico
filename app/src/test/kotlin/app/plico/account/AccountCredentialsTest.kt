package app.plico.account

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.data.Servers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountCredentialsTest {
    @Test
    fun `OAuth only for providers with an issuer`() {
        assertEquals(CredentialsKind.OAuth(OAuthIssuer.GOOGLE), kindOf(account(Provider.GMAIL, AuthKind.OAUTH)))
        assertEquals(CredentialsKind.OAuth(OAuthIssuer.MICROSOFT), kindOf(account(Provider.MICROSOFT, AuthKind.OAUTH)))
        assertEquals(CredentialsKind.Refused, kindOf(account(Provider.OTHER, AuthKind.OAUTH)), "never a Google token")
        assertEquals(CredentialsKind.Refused, kindOf(account(Provider.RISEUP, AuthKind.OAUTH)))
    }

    @Test
    fun `the device account only for Gmail`() {
        assertEquals(CredentialsKind.Device, kindOf(account(Provider.GMAIL, AuthKind.DEVICE)))
        for (provider in listOf(Provider.MICROSOFT, Provider.OTHER, Provider.RISEUP)) {
            assertEquals(CredentialsKind.Refused, kindOf(account(provider, AuthKind.DEVICE)), provider.name)
        }
    }

    @Test
    fun `passwords for every provider`() {
        for (provider in Provider.entries) {
            assertEquals(CredentialsKind.Password, kindOf(account(provider, AuthKind.PASSWORD)), provider.name)
        }
    }

    @Test
    fun `refusing credentials refuse both logins without a secret`() = runTest {
        val refusing = Refusing("Not with this server")
        val (client, stream) = imap(IMAP_OK)
        assertEquals("Not with this server", assertFailsWith<MailError.Setup> { refusing.authImap(client) }.message)
        val (smtpClient, smtpStream) = smtp(SMTP_OK)
        assertFailsWith<MailError.Setup> { refusing.authSmtp(smtpClient) }
        assertEquals(emptyList(), stream.written())
        assertEquals(emptyList(), smtpStream.written())
        assertNull(refusing.expiresAt)
    }

    @Test
    fun `a rotated token replaces only the same stored token`() {
        assertTrue(mayRotate(AuthKind.OAUTH, "refresh-1", "refresh-1"))
        assertFalse(mayRotate(AuthKind.OAUTH, "refresh-new", "refresh-1"), "signed in again while the refresh ran")
        assertFalse(mayRotate(AuthKind.PASSWORD, "refresh-1", "refresh-1"), "another sign-in method now")
        assertFalse(mayRotate(AuthKind.DEVICE, null, "refresh-1"))
        assertFalse(mayRotate(null, null, "refresh-1"), "the account is gone")
        assertFalse(mayRotate(AuthKind.OAUTH, null, "refresh-1"), "the secret cannot be read")
    }

    private fun account(provider: Provider, auth: AuthKind) = Account(
        id = 1, provider = provider, email = "me@example.org", senderName = "", label = "Mail", color = 0, auth = auth,
        deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15, gmailCategories = emptySet(),
        archiveFolder = null, signature = "", state = AccountState.OK, stateText = null, serverKeepaliveS = null,
        quotaUsed = null, quotaLimit = null, sort = 0, servers = Servers.NONE,
    )
}
