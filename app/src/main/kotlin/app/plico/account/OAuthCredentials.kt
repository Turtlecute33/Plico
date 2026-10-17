package app.plico.account

import android.os.SystemClock
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An OAuth access token. [expiresAt] is SystemClock.elapsedRealtime() at expiry. toString() hides the value. */
class AccessToken(val value: String, val expiresAt: Long) {
    override fun toString() = "AccessToken(expiresAt=$expiresAt)"
}

/** The token endpoint of an OAuth issuer as [OAuthCredentials] uses it; [BrowserOAuth] is the real one. */
interface TokenEndpoint {
    /** Throws [MailError.AuthRequired] for invalid_grant and [MailError.Setup] for a rejected client. */
    suspend fun refresh(refreshToken: String): AccessToken

    suspend fun revoke(token: String)
}

/**
 * Gmail XOAUTH2 with a browser-OAuth grant (DESIGN.md §6, push PB17). The refresh token stays in the Vault
 * and is read only to refresh; the access token lives in memory. Gmail ends an OAuth IMAP session when its
 * token expires, so every IMAP login uses a token with at least 45 min left.
 *
 * [user] is the account address, which came from the id_token `email` claim.
 *
 * @param onRotated writes a new refresh token that the issuer sent in place of the old one.
 * @param hosts the only mail servers that get a token (the hosts of the issuer).
 * @param smtpOff the text for an account whose provider does not let apps send mail; null for Google.
 */
class OAuthCredentials(
    private val user: String,
    private val refreshToken: suspend () -> String,
    private val endpoint: TokenEndpoint,
    initial: AccessToken? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onRotated: suspend (old: String, new: String) -> Unit = { _, _ -> },
    private val hosts: Set<String> = emptySet(),
    private val smtpOff: String? = null,
) : Credentials {
    private val mutex = Mutex()

    @Volatile
    private var token: AccessToken? = initial

    /** The token of the last IMAP login: it decides when that session ends. */
    @Volatile
    private var session: AccessToken? = null

    override val expiresAt: Long? get() = session?.let { it.expiresAt - EXPIRY_MARGIN_MS }

    override suspend fun authImap(client: ImapClient) = withContext(Dispatchers.IO) {
        val t = token(IMAP_MIN_LEFT_MS)
        login(t) { client.authenticateXoauth2(user, t.value) }
        session = t
    }

    override suspend fun authSmtp(client: SmtpClient) = withContext(Dispatchers.IO) {
        val t = token(SMTP_MIN_LEFT_MS)
        login(t) { client.authXoauth2(user, t.value) }
    }

    override suspend fun invalidate() {
        token = null
    }

    override suspend fun revoke() {
        token = null
        try {
            withContext(Dispatchers.IO) { endpoint.revoke(refreshToken()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the account is removed either way.
        }
    }

    private suspend fun token(minLeft: Long): AccessToken = mutex.withLock {
        token?.takeIf { it.expiresAt - clock() >= minLeft }
            ?: endpoint.refresh(refreshToken()).also { token = it }
    }

    /** A refused token is dropped, so that the next login refreshes. */
    private inline fun login(t: AccessToken, block: () -> Unit) {
        try {
            authenticating(block)
        } catch (e: MailError.AuthRequired) {
            if (token === t) token = null
            throw e
        }
    }

    private companion object {
        const val IMAP_MIN_LEFT_MS = 45 * 60_000L
        const val SMTP_MIN_LEFT_MS = 5 * 60_000L
        const val EXPIRY_MARGIN_MS = 5 * 60_000L
    }
}
